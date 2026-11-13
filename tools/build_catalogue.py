#!/usr/bin/env python3
"""Build catalogue/surveys.json: original surveys finer than the IMAS composite.

Maintainer tool, run a few times a year. Users never run it; BathyPicker fetches the result.

What it does:
  1. Lists the public AusSeabed bucket (s3://ausseabed-public-bathymetry/L3/) and keeps depth GeoTIFFs.
  2. Reads each file's header over HTTP (a few KB) for CRS, bounds and pixel size.
  3. Keeps the ones finer than --max-res (default 10 m, the IMAS shelf detail).
  4. Checks the depth reference: reads a few hundred sample points from the survey and compares them
     with the IMAS composite (mean sea level) at the same places. Surveys that match are kept, a steady
     offset (e.g. lowest-tide datum) is recorded as a shift, anything else is left out with the reason.
  5. Writes catalogue/surveys.json (kept) and catalogue/excluded.json (left out, with reasons).

Only headers and small windows are downloaded, never whole surveys. Everything fetched is cached in
--cache so reruns are quick.

Needs: Python 3.10+, rasterio, numpy, shapely, pyproj  (pip install rasterio numpy shapely pyproj)
"""
from __future__ import annotations

import argparse
import concurrent.futures as cf
import datetime as dt
import json
import math
import os
import random
import re
import sys
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
import zlib
from pathlib import Path

import numpy as np
import rasterio
from pyproj import Transformer
from rasterio.windows import Window
from shapely.geometry import box, mapping, shape
from shapely.ops import unary_union

S3 = "https://ausseabed-public-bathymetry.s3.ap-southeast-2.amazonaws.com/"
S3_NS = "{http://s3.amazonaws.com/doc/2006-03-01/}"
IMAS = "https://data.imas.utas.edu.au/attachments/69e9ac91-babe-47ed-8c37-0ef08f29338a/bathymetry/"
# Same files and order as bathy_picker.py ZONES; depth bands are approximate (metres, positive down).
IMAS_ZONES = [
    ("01_shallow_bathy.tif", 0, 30), ("02_mesophotic_bathy.tif", 30, 70),
    ("03_rariphotic_bathy.tif", 70, 200), ("04_upperslope_bathy.tif", 200, 700),
    ("05_midslope_bathy.tif", 700, 2000), ("06_lowerslope_bathy.tif", 2000, 4000),
    ("07_abyss_bathy.tif", 4000, 6000), ("08_hadal_bathy.tif", 6000, 12000),
]
CREDIT_GA = "© Commonwealth of Australia (Geoscience Australia). AusSeabed bathymetry data, CC BY 4.0."

# Not depth: hillshades, images, backscatter, uncertainty, slope etc.
NOT_DEPTH = re.compile(
    r"(hill|_hs\b|_hs_|hs0|shade|_map\b|_map_|_map\.|bksct|backscat|_bks|_bs_|_bs\.|uncert|_std|stddev|"
    r"density|_thu|_tvu|slope|aspect|rgb|colou?r|_img|image|_nadir|_count|hits|_ov\.|overlay|rugos|"
    r"_mosaic|_8bit|8bit|_byte|angle|_beam|_sd\b|_sd_)",
    re.I,
)
DATUM_WORDS = [
    ("ellipsoid", r"ellips|_ell_|_ell\.|wgs84e|_ehgt|ellipsoidal"),
    ("EGM2008", r"egm2008|egm08|_egm"),
    ("LAT", r"(^|[_\-. ])lat([_\-. ]|$)|lowest.?astro"),
    ("AHD", r"\bahd\b|_ahd"),
    ("MSL", r"msl|mean.?sea"),
]

M_PER_DEG = 111_320.0


def log(*a):
    print(*a, flush=True)


def http_json_cached(url: str, path: Path, max_age_days: float):
    if path.exists() and time.time() - path.stat().st_mtime < max_age_days * 86400:
        return json.loads(path.read_text(encoding="utf-8"))
    data = json.loads(urllib.request.urlopen(url, timeout=120).read())
    path.write_text(json.dumps(data), encoding="utf-8")
    return data


# --------------------------------------------------------------------------- 1. list the bucket

def list_bucket(prefix: str, cache: Path, max_age_days: float) -> list[dict]:
    path = cache / "s3_listing.json"
    if path.exists() and time.time() - path.stat().st_mtime < max_age_days * 86400:
        return json.loads(path.read_text(encoding="utf-8"))
    rows, token = [], None
    while True:
        q = {"list-type": "2", "prefix": prefix, "max-keys": "1000"}
        if token:
            q["continuation-token"] = token
        x = ET.fromstring(urllib.request.urlopen(S3 + "?" + urllib.parse.urlencode(q), timeout=120).read())
        for c in x.findall(S3_NS + "Contents"):
            rows.append({"key": c.find(S3_NS + "Key").text, "bytes": int(c.find(S3_NS + "Size").text),
                         "modified": c.find(S3_NS + "LastModified").text[:10]})
        if x.find(S3_NS + "IsTruncated").text != "true":
            break
        token = x.find(S3_NS + "NextContinuationToken").text
    path.write_text(json.dumps(rows), encoding="utf-8")
    return rows


def depth_candidates(rows: list[dict]) -> tuple[list[dict], list[dict]]:
    keep, dropped = [], []
    for r in rows:
        k = r["key"]
        name = k.rsplit("/", 1)[-1]
        if not name.lower().endswith((".tif", ".tiff")):
            continue
        if NOT_DEPTH.search(name) or re.search(r"backscatter|/hillshade|/images?/|/imagery/", k, re.I):
            dropped.append({"key": k, "reason": "not a depth grid (by name)"})
        elif r["bytes"] < 20_000:
            dropped.append({"key": k, "reason": "tiny file"})
        else:
            keep.append(r)
    return keep, dropped


def url_for(key: str) -> str:
    return S3 + urllib.parse.quote(key)


def stated_datum(key: str) -> str | None:
    low = key.lower()
    for label, pat in DATUM_WORDS:
        if re.search(pat, low):
            return label
    return None


# --------------------------------------------------------------------------- 2. headers

def read_header(r: dict) -> dict:
    url = url_for(r["key"])
    with rasterio.open("/vsicurl/" + url) as ds:
        if ds.crs is None:
            raise ValueError("no CRS")
        b = ds.bounds
        tr = Transformer.from_crs(ds.crs, "EPSG:4326", always_xy=True)
        xs = np.linspace(b.left, b.right, 9)
        ys = np.linspace(b.bottom, b.top, 9)
        gx, gy = np.meshgrid(xs, ys)
        lon, lat = tr.transform(gx.ravel(), gy.ravel())
        w, s, e, n = float(np.min(lon)), float(np.min(lat)), float(np.max(lon)), float(np.max(lat))
        rx, ry = abs(ds.res[0]), abs(ds.res[1])
        if ds.crs.is_geographic:
            midlat = math.radians((s + n) / 2)
            res_m = math.sqrt(rx * M_PER_DEG * math.cos(midlat) * ry * M_PER_DEG)
        else:
            res_m = math.sqrt(rx * ry) * (ds.crs.linear_units_factor[1] if ds.crs.linear_units_factor else 1.0)
        bs = ds.block_shapes[0] if ds.count else (0, 0)
        return {
            "key": r["key"], "url": url, "bytes": r["bytes"], "modified": r["modified"],
            "crs": ds.crs.to_string(), "epsg": ds.crs.to_epsg(), "geographic": ds.crs.is_geographic,
            "width": ds.width, "height": ds.height, "bands": ds.count, "dtype": ds.dtypes[0],
            "nodata": ds.nodata, "res_m": round(res_m, 3), "bbox": [round(v, 6) for v in (w, s, e, n)],
            "tiled": bs[0] != 1 and bs[0] < ds.height, "block": list(bs),
            "overviews": len(ds.overviews(1)) if ds.count else 0,
        }


# --------------------------------------------------------------------------- 3. where the data is

def data_cells(h: dict, max_cells: int = 24):
    """Rectangles (lon/lat) where a tiled survey actually has data, from its tile sizes alone.

    Empty (all-NoData) tiles compress to almost nothing or are left out of the file, so the tile
    byte counts in the header show where the data is without downloading it. Returns None when
    that can't be told (strip files, or no compression).
    """
    if not h.get("tiled"):
        return None
    with rasterio.open("/vsicurl/" + h["url"]) as ds:
        bh, bw = ds.block_shapes[0]
        ny, nx = math.ceil(ds.height / bh), math.ceil(ds.width / bw)
        if ny * nx > 400_000:
            return None
        sizes = np.zeros((ny, nx), dtype=np.int64)
        for i in range(ny):
            for j in range(nx):
                sizes[i, j] = ds.block_size(1, i, j) or 0
        nonzero = sizes[sizes > 0]
        if not len(nonzero):
            return []
        floor = int(nonzero.min())
        raw = bh * bw * np.dtype(ds.dtypes[0]).itemsize
        if floor > raw * 0.2:  # (near) uncompressed: every tile about the same size, so it can't tell
            return None
        has = sizes > floor * 1.5 + 256
        if not has.any():
            return []
        # Coarser grid: a cell has data if any tile in it does.
        gy, gx = min(ny, max_cells), min(nx, max_cells)
        cy = np.linspace(0, ny, gy + 1).astype(int)
        cx = np.linspace(0, nx, gx + 1).astype(int)
        grid = np.array([[has[cy[a]:cy[a + 1], cx[b]:cx[b + 1]].any() for b in range(gx)] for a in range(gy)])
        tr = Transformer.from_crs(ds.crs, "EPSG:4326", always_xy=True)
        rects = []
        for a in range(gy):  # merge runs along each row
            b = 0
            while b < gx:
                if not grid[a, b]:
                    b += 1
                    continue
                e = b
                while e + 1 < gx and grid[a, e + 1]:
                    e += 1
                r0, r1 = cy[a] * bh, min(ds.height, cy[a + 1] * bh)
                c0, c1 = cx[b] * bw, min(ds.width, cx[e + 1] * bw)
                xs, ys = [], []
                for r, c in ((r0, c0), (r0, c1), (r1, c0), (r1, c1)):
                    x, y = ds.transform * (c, r)
                    xs.append(x)
                    ys.append(y)
                lon, lat = tr.transform(xs, ys)
                rects.append([round(min(lon), 5), round(min(lat), 5), round(max(lon), 5), round(max(lat), 5)])
                b = e + 1
        return rects


# --------------------------------------------------------------------------- 4. depth reference check

class ImasSampler:
    """Reads IMAS composite values at points (EPSG:4326 lon/lat), finest zone that has data."""

    def __init__(self):
        self.ds = {}
        self.to_imas = None

    def _open(self, f):
        if f not in self.ds:
            self.ds[f] = rasterio.open("/vsicurl/" + IMAS + f)
            if self.to_imas is None:
                self.to_imas = Transformer.from_crs("EPSG:4326", self.ds[f].crs, always_xy=True)
        return self.ds[f]

    def sample(self, lon, lat, depth_hint: float) -> np.ndarray:
        out = np.full(len(lon), np.nan)
        files = [z[0] for z in IMAS_ZONES if z[1] - 40 <= depth_hint <= z[2] * 1.5 + 40] or [IMAS_ZONES[0][0]]
        for f in files:  # shallow zones are finest; first valid wins
            ds = self._open(f)
            x, y = self.to_imas.transform(lon, lat)
            vals = np.array([v[0] for v in ds.sample(zip(x, y))], dtype="float64")
            nd = ds.nodata
            ok = np.isfinite(vals) & (np.abs(vals) < 12000)
            if nd is not None:
                ok &= vals != nd
            fill = np.isnan(out) & ok
            out[fill] = vals[fill]
        return out


def survey_points(h: dict, rng: random.Random, n_windows=10, win=64, want=300):
    """Read a few small windows of the survey and return valid (lon, lat, value, window number) samples."""
    pts_x, pts_y, vals, wins = [], [], [], []
    with rasterio.open("/vsicurl/" + h["url"]) as ds:
        nd = ds.nodata
        W, H = ds.width, ds.height
        # Use an overview to find where the data is, when there is one; otherwise sample a grid.
        centres = []
        if h["overviews"]:
            ov = ds.read(1, out_shape=(min(256, H), min(256, W)), masked=True)
            vy, vx = np.nonzero(~np.ma.getmaskarray(ov) & np.isfinite(ov.filled(np.nan)))
            if len(vx):
                for i in rng.sample(range(len(vx)), min(n_windows, len(vx))):
                    centres.append((int(vx[i] * W / ov.shape[1]), int(vy[i] * H / ov.shape[0])))
        if not centres:
            # Coarse grid first, then finer grids, for sparse surveys (long thin swaths).
            for g in (5, 10, 16):
                ring = [(int((i + 0.5) * W / g), int((j + 0.5) * H / g)) for i in range(g) for j in range(g)]
                rng.shuffle(ring)
                centres += ring
        tried = 0
        for cx, cy in centres:
            if len(vals) >= want or tried >= 120:
                break
            tried += 1
            c0, r0 = max(0, cx - win // 2), max(0, cy - win // 2)
            wnd = Window(c0, r0, min(win, W - c0), min(win, H - r0))
            a = ds.read(1, window=wnd).astype("float64")
            ok = np.isfinite(a) & (np.abs(a) < 12000)
            if nd is not None:
                ok &= a != nd
            ok &= a != 0  # zero is a common unflagged fill value
            rr, cc = np.nonzero(ok)
            if not len(rr):
                continue
            pick = rng.sample(range(len(rr)), min(40, len(rr)))
            for p in pick:
                x, y = ds.xy(r0 + rr[p], c0 + cc[p])
                pts_x.append(x); pts_y.append(y); vals.append(a[rr[p], cc[p]]); wins.append(tried)
        if not vals:
            return None
        lon, lat = Transformer.from_crs(ds.crs, "EPSG:4326", always_xy=True).transform(pts_x, pts_y)
    return np.array(lon), np.array(lat), np.array(vals), np.array(wins)


def check_datum(h: dict, sampler: ImasSampler, seed: int) -> dict:
    rng = random.Random(seed)
    got = survey_points(h, rng)
    if got is None:
        return {"ok": False, "reason": "no valid depths found in sample windows"}
    lon, lat, v, wins = got
    hint = float(np.median(np.abs(v)))
    ref = sampler.sample(lon, lat, hint)
    m = np.isfinite(ref)
    if m.sum() < 20:
        return {"ok": False, "reason": f"too few points overlap IMAS ({int(m.sum())}) to check the depth reference"}
    v, ref, wins = v[m], ref[m], wins[m]
    best = None
    for sign in (1, -1):  # -1: survey stores positive depths
        d = sign * v - ref
        med = float(np.median(d))
        mad = float(np.median(np.abs(d - med)))
        if best is None or abs(med) + mad < abs(best[1]) + best[2]:
            best = (sign, med, mad)
    sign, med, mad = best
    # The gap in each sample window separately: a real datum offset is the same everywhere in the
    # survey; a gap that changes from place to place is IMAS itself varying (it blends sources).
    d = sign * v - ref
    win_meds = [float(np.median(d[wins == w])) for w in np.unique(wins) if (wins == w).sum() >= 10]
    return {"sign": sign, "median_diff_m": round(med, 2), "mad_m": round(mad, 2), "n": int(m.sum()),
            "imas_depth_m": round(float(np.median(-ref)), 1), "windows": len(win_meds),
            "window_range_m": [round(min(win_meds), 2), round(max(win_meds), 2)] if win_meds else None}


def classify(c: dict, stated: str | None) -> dict:
    """Decide from a datum check whether to keep a survey, and with what shift.

    The file's own stated depth reference wins over the IMAS comparison: IMAS is mostly on mean sea
    level, but in places it carries an input survey's own datum (seen at Darwin, Hobart and Rottnest,
    where files labelled MSL differ from it by 1.7-4 m). The comparison still decides the sign,
    whether the grid is depth at all, and the shift for files that don't say what they are.
    """
    if "median_diff_m" not in c:
        return {"ok": False, "reason": c.get("reason", "check failed")}
    med, mad, depth = c["median_diff_m"], c["mad_m"], abs(c["imas_depth_m"])
    # IMAS is coarser in deep water and on steep ground, so allow more spread there.
    spread_ok = mad <= max(1.0, 0.03 * depth)
    steady = mad <= 0.5
    if not (spread_ok or steady) or abs(med) > 8:
        return {"ok": False, "reason": f"doesn't match IMAS: median difference {med:+.1f} m, spread {mad:.1f} m"}
    differs = f" Differs from IMAS here by {med:+.1f} m; IMAS may not be on sea level at this spot." \
        if abs(med) > max(1.0, mad) else ""
    if stated in ("MSL", "AHD", "EGM2008"):
        # AHD and EGM2008 sit within about half a metre of sea level around Australia.
        return {"ok": True, "shift_m": 0.0, "verdict": f"stated {stated}, used as sea level", "note": differs.strip()}
    # A shift is only trusted when every sample window shows about the same gap (and there are at
    # least two to compare). Seen at Jervis Bay: +0.84 m on average, but about 0 m in deeper water.
    wr = c.get("window_range_m")
    consistent = bool(wr) and c.get("windows", 0) >= 2 and (wr[1] - wr[0]) <= max(0.5, 0.25 * abs(med))
    if stated == "LAT":
        if steady and consistent and -8 <= -med < 0:
            return {"ok": True, "shift_m": round(-med, 2), "default_off": True,
                    "verdict": f"stated LAT; estimated shift {-med:+.2f} m from IMAS",
                    "note": "Lowest-tide datum; the shift to sea level is an estimate."}
        return {"ok": False, "reason": f"stated LAT but no plausible shift from IMAS ({med:+.1f} m)"}
    if spread_ok and abs(med) <= max(0.5, mad):
        return {"ok": True, "shift_m": 0.0, "verdict": "no datum stated; matches IMAS"}
    if steady and consistent:
        return {"ok": True, "shift_m": round(-med, 2), "verdict": f"no datum stated; shifted {-med:+.2f} m to match IMAS",
                "note": "Depth reference not stated; matched to the IMAS composite."}
    if spread_ok and wr:
        # Listed either way, but off by default when the gap gets big somewhere (e.g. the 2015 5 m
        # multibeam compilation: +3 to +11 m): users can still tick it if they know it's right.
        return {"ok": True, "shift_m": 0.0, "verdict": "no datum stated; gap to IMAS varies, used as is",
                "default_off": max(abs(wr[0]), abs(wr[1])) > 1.5,
                "note": f"Depth reference not stated. IMAS differs from it by {wr[0]:+.1f} to {wr[1]:+.1f} m "
                        "in different places, so it is used as is; expect small steps at its edges."}
    return {"ok": False, "reason": f"no datum stated and doesn't match IMAS: {med:+.1f} m, spread {mad:.1f} m"}


# --------------------------------------------------------------------------- 5. grouping and output

def survey_id(key: str) -> str:
    return key.split("/")[1]


def nice_name(folder: str) -> str:
    s = re.sub(r"_?e?cat_?\d+|_?geocat_?\d+", "", folder, flags=re.I)
    s = re.sub(r"^(\d{3,4}_|\d{8}S_|ga-?\d{4}_)", "", s, flags=re.I)
    s = re.sub(r"(?<=[a-z])(?=[A-Z])", " ", s.replace("_", " ").replace("-", " "))
    return re.sub(r"\s+", " ", s).strip() or folder


def year_of(text: str) -> int | None:
    # Not after letters (sonar models like EM2040, voyage codes like IN2015) and not in the future.
    ys = [int(y) for y in re.findall(r"(?<![A-Za-z\d])(19[89]\d|20[0-4]\d)(?!\d)", text)]
    ys = [y for y in ys if y <= dt.date.today().year]
    return max(ys) if ys else None


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    here = Path(__file__).resolve().parent.parent
    ap.add_argument("--out", type=Path, default=here / "catalogue")
    ap.add_argument("--cache", type=Path, default=here / "catalogue" / ".cache")
    ap.add_argument("--max-res", type=float, default=9.99, help="keep surveys finer than this (metres)")
    ap.add_argument("--max-age", type=float, default=7, help="days before cached listings are refetched")
    ap.add_argument("--threads", type=int, default=12)
    ap.add_argument("--limit", type=int, default=0, help="only check the first N files (testing)")
    a = ap.parse_args(argv)
    a.out.mkdir(parents=True, exist_ok=True)
    a.cache.mkdir(parents=True, exist_ok=True)

    os.environ.update({"GDAL_DISABLE_READDIR_ON_OPEN": "EMPTY_DIR", "CPL_VSIL_CURL_ALLOWED_EXTENSIONS": ".tif,.tiff",
                       "GDAL_HTTP_MAX_RETRY": "5", "GDAL_HTTP_RETRY_DELAY": "3", "VSI_CACHE": "TRUE"})

    log("1. Listing AusSeabed L3 ...")
    rows = list_bucket("L3/", a.cache, a.max_age)
    cands, excluded = depth_candidates(rows)
    log(f"   {len(rows)} objects, {len(cands)} depth GeoTIFF candidates")
    if a.limit:
        cands = cands[: a.limit]

    log("2. Reading headers ...")
    hdr_path = a.cache / "headers.json"
    headers = json.loads(hdr_path.read_text(encoding="utf-8")) if hdr_path.exists() else {}
    todo = [r for r in cands if r["key"] not in headers or headers[r["key"]].get("modified") != r["modified"]]

    def do_hdr(r):
        try:
            return r["key"], read_header(r)
        except Exception as exc:  # noqa: BLE001 - report and carry on
            return r["key"], {"error": str(exc)[:200], "modified": r["modified"]}

    with cf.ThreadPoolExecutor(a.threads) as ex:
        for i, (k, h) in enumerate(ex.map(do_hdr, todo), 1):
            headers[k] = h
            if i % 100 == 0:
                log(f"   {i}/{len(todo)}")
                hdr_path.write_text(json.dumps(headers), encoding="utf-8")
    hdr_path.write_text(json.dumps(headers), encoding="utf-8")

    fine = []
    for r in cands:
        h = headers.get(r["key"], {})
        if "error" in h:
            excluded.append({"key": r["key"], "reason": "can't read header: " + h["error"]})
        elif h["bands"] != 1 or h["dtype"] not in ("float32", "float64", "int16", "int32"):
            excluded.append({"key": r["key"], "reason": f"not a single-band depth grid ({h['bands']} bands, {h['dtype']})"})
        elif h["res_m"] >= a.max_res:
            excluded.append({"key": r["key"], "reason": f"{h['res_m']:g} m is not finer than IMAS", "quiet": True})
        elif not (90 <= h["bbox"][0] <= 175 and -60 <= h["bbox"][1] <= 0):
            excluded.append({"key": r["key"], "reason": "outside Australian waters"})
        elif stated_datum(r["key"]) == "ellipsoid":
            excluded.append({"key": r["key"], "reason": "heights above the ellipsoid (tens of metres off sea level)"})
        else:
            fine.append(h)
    log(f"   {len(fine)} files finer than {a.max_res:g} m")

    log("3. Checking depth references against IMAS ...")
    chk_path = a.cache / "datum_checks.json"
    checks = json.loads(chk_path.read_text(encoding="utf-8")) if chk_path.exists() else {}
    todo = [h for h in fine if checks.get(h["key"], {}).get("modified") != h["modified"]]

    def do_chk(h):
        sampler = ImasSampler()  # one per call: rasterio datasets aren't thread-safe
        try:
            res = check_datum(h, sampler, seed=zlib.crc32(h["key"].encode()))
        except Exception as exc:  # noqa: BLE001
            res = {"ok": False, "reason": "check failed: " + str(exc)[:200]}
        res["modified"] = h["modified"]
        return h["key"], res

    with cf.ThreadPoolExecutor(max(2, a.threads // 2)) as ex:
        for i, (k, res) in enumerate(ex.map(do_chk, todo), 1):
            checks[k] = res
            if i % 25 == 0:
                log(f"   {i}/{len(todo)}")
                chk_path.write_text(json.dumps(checks), encoding="utf-8")
    chk_path.write_text(json.dumps(checks), encoding="utf-8")

    log("   Finding where each survey has data (tile sizes) ...")
    cell_path = a.cache / "cells.json"
    cells = json.loads(cell_path.read_text(encoding="utf-8")) if cell_path.exists() else {}
    todo = [h for h in fine if checks[h["key"]].get("median_diff_m") is not None
            and cells.get(h["key"], {}).get("modified") != h["modified"]]

    def do_cells(h):
        try:
            return h["key"], {"cells": data_cells(h), "modified": h["modified"]}
        except Exception as exc:  # noqa: BLE001
            return h["key"], {"cells": None, "error": str(exc)[:200], "modified": h["modified"]}

    with cf.ThreadPoolExecutor(a.threads) as ex:
        for k, v in ex.map(do_cells, todo):
            cells[k] = v
    cell_path.write_text(json.dumps(cells), encoding="utf-8")

    log("4. Writing the catalogue ...")
    groups: dict[tuple, list] = {}
    for h in fine:
        c = {**checks[h["key"]], **classify(checks[h["key"]], stated_datum(h["key"]))}
        if not c.get("ok"):
            excluded.append({"key": h["key"], "reason": c.get("reason", "failed check"),
                             "median_diff_m": c.get("median_diff_m"), "mad_m": c.get("mad_m")})
            continue
        res_bucket = round(h["res_m"], 1)
        groups.setdefault((survey_id(h["key"]), res_bucket, c["sign"], c["shift_m"]), []).append((h, c))

    surveys = []
    for (folder, res, sign, shift), items in groups.items():
        hs = [h for h, _ in items]
        cs = [c for _, c in items]
        for h in hs:
            got = (cells.get(h["key"]) or {}).get("cells")
            h["cells"] = got if got else ([] if got == [] else [h["bbox"]])
        hs = [h for h in hs if h["cells"]]  # tiles show no data at all: nothing to offer
        if not hs:
            continue
        foot = unary_union([box(*r) for h in hs for r in h["cells"]]).simplify(0.001)
        newest = max(h["modified"] for h in hs)
        sid = re.sub(r"[^a-z0-9]+", "-", f"{folder}-{res:g}m".lower()).strip("-")
        if sign < 0 or shift:
            sid += f"-s{'n' if sign < 0 else 'p'}{abs(shift):g}".replace(".", "p")
        surveys.append({
            "id": sid,
            "name": nice_name(folder),
            "folder": folder,
            "year": year_of(folder) or year_of(" ".join(h["key"] for h in hs)),
            "res_m": res,
            "files": [{"url": h["url"], "bytes": h["bytes"], "crs": h["crs"], "bbox": h["bbox"],
                       "cells": h["cells"], "tiled": h["tiled"], "overviews": h["overviews"]} for h in hs],
            "bytes": sum(h["bytes"] for h in hs),
            "bbox": [round(v, 5) for v in foot.bounds],
            "footprint": json.loads(json.dumps(mapping(foot))),
            "stated_datum": stated_datum(" ".join(h["key"] for h in hs)),
            "sign": sign,  # 1 = elevation (negative down); -1 = file stores positive depths
            "shift_m": shift,  # add after applying sign, to match sea level (IMAS)
            "check": {"median_diff_m": round(float(np.median([c["median_diff_m"] for c in cs])), 2),
                      "mad_m": round(float(np.median([c["mad_m"] for c in cs])), 2),
                      "points": int(sum(c["n"] for c in cs)), "verdict": cs[0]["verdict"]},
            "updated": newest,
            "source": "AusSeabed (Geoscience Australia)",
            "licence": "CC BY 4.0",
            "credit": CREDIT_GA,
            "default_off": any(c.get("default_off") for c in cs),
            "note": next((c["note"] for c in cs if c.get("note")), ""),
        })

    # Same area, same detail, published twice (re-published copies, or the same survey in several
    # depth references): one stays on. Prefer no shift needed, then the newer survey, then the newer
    # upload. (Not "closest to IMAS": that just favours whichever older survey IMAS already used.)
    def preference(s):
        return (s["shift_m"] == 0, s["stated_datum"] is not None, s["year"] or 0, s["updated"], -s["check"]["mad_m"])

    for i, s in enumerate(surveys):
        fs = shape(s["footprint"])
        for t in surveys[i + 1:]:
            if s["default_off"] or t["default_off"] or abs(t["res_m"] - s["res_m"]) > 0.05:
                continue
            ft = shape(t["footprint"])
            inter = fs.intersection(ft).area
            # Both must largely cover each other: a small site inside a big survey isn't a copy.
            # Within one survey folder, overlapping files at the same detail are copies (e.g. a
            # labelled "egm2008" file and an unlabelled "_cog" export of the same grid).
            same_folder = s["folder"] == t["folder"] and inter > 0.3 * min(fs.area, ft.area)
            if inter and (same_folder or inter / max(fs.area, ft.area) > 0.8):
                loser, winner = (s, t) if preference(s) < preference(t) else (t, s)
                loser["default_off"] = True
                loser["note"] = (f"Same area as {winner['name']} ({winner['res_m']:g} m), "
                                 f"which is on by default.")
    surveys.sort(key=lambda s: (s["bbox"][1], s["bbox"][0]))

    # Hand edits (e.g. a survey users reported as bad) survive rebuilds via overrides.json:
    # {"<survey id or folder>": {"default_off": true, "note": "Spikes near the north edge."}}
    ov_path = a.out / "overrides.json"
    if ov_path.exists():
        overrides = json.loads(ov_path.read_text(encoding="utf-8"))
        used = set()
        for s in surveys:
            for key in (s["id"], s["folder"]):
                if key in overrides:
                    s.update({k: v for k, v in overrides[key].items() if k in ("default_off", "note", "name")})
                    used.add(key)
        for key in sorted(set(overrides) - used):
            log(f"   override '{key}' matched no survey")

    meta = {
        "schema": "bathypicker-surveys/v1",
        "built": dt.date.today().isoformat(),
        "sources": ["s3://ausseabed-public-bathymetry/L3/"],
        "reference": "Depths are matched to the IMAS composite (mean sea level, EPSG:5715) by sign and shift_m.",
        "count": len(surveys),
    }
    (a.out / "surveys.json").write_text(json.dumps({**meta, "surveys": surveys}, indent=1), encoding="utf-8")
    loud = [e for e in excluded if not e.get("quiet")]
    (a.out / "excluded.json").write_text(json.dumps({"built": meta["built"], "count": len(loud), "files": loud},
                                                    indent=1), encoding="utf-8")
    kb = (a.out / "surveys.json").stat().st_size / 1024
    log(f"   {len(surveys)} surveys ({sum(s['bytes'] for s in surveys) / 1e9:.1f} GB at source), "
        f"surveys.json {kb:.0f} KB; {len(loud)} files left out (see excluded.json)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
