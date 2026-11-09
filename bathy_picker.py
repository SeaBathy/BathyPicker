#!/usr/bin/env python3
"""
bathy_picker.py - pick an area of Australia on a map and get ONE GeoTIFF of its seafloor depth.

What it does
    Opens a small map page. You drag a box over any Australian waters, choose which depth layers
    and where to save, press "Check size", then "Build GeoTIFF". The script streams just that area
    out of the IMAS multi-resolution bathymetry composite (the 8 depth-zone files: shallow,
    mesophotic, rariphotic, upper slope, mid slope, lower slope, abyss, hadal), merges the zones
    into a single Cloud-Optimized GeoTIFF and saves it in the folder you picked.

Data source (plain file listing):
    https://data.imas.utas.edu.au/attachments/69e9ac91-babe-47ed-8c37-0ef08f29338a/bathymetry/

What you need
    * Python 3.8 or newer, and an internet connection. Nothing to pip install.
    * GDAL (the gdalinfo and gdalwarp programs). If it isn't installed the page offers to download
      a private copy for you on first run (a few hundred MB, once, no admin rights, nothing else on
      the computer is touched). It comes from the micromamba installer (micro.mamba.pm) and the
      conda-forge package "libgdal-core". Or install GDAL yourself:
        Ubuntu/Debian: sudo apt install gdal-bin      macOS: brew install gdal
        Windows: install QGIS or OSGeo4W               conda: conda install -c conda-forge gdal

Usage
    python3 bathy_picker.py                      # open the map at http://127.0.0.1:8765
    python3 bathy_picker.py --setup              # just download GDAL, then exit
    python3 bathy_picker.py --check              # test GDAL and the IMAS server, then exit
    python3 bathy_picker.py --host 0.0.0.0       # reach it from your phone (prints a URL with a key)
    python3 bathy_picker.py --bbox 148.70 -20.40 149.10 -20.10 --yes     # no map, straight to a file

Not for navigation. Cite the data as:
    Flukes, E. (2026). Multi-resolution bathymetry composite surface for Australian waters (EEZ)
    [Data set]. Institute for Marine and Antarctic Studies. https://doi.org/10.25959/Y9VF-V723
"""
from __future__ import annotations

import argparse
import hmac
import json
import math
import os
import platform
import re
import secrets
import shutil
import ssl
import string
import subprocess
import sys
import tarfile
import threading
import time
import urllib.error
import urllib.request
import webbrowser
from collections import deque
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlparse

APP_VERSION = "1.1"
BASE_URL = "https://data.imas.utas.edu.au/attachments/69e9ac91-babe-47ed-8c37-0ef08f29338a/bathymetry/"
DOI_URL = "https://doi.org/10.25959/Y9VF-V723"
CITATION = (
    "Flukes, E. (2026). Multi-resolution bathymetry composite surface for Australian waters (EEZ) "
    "[Data set]. Institute for Marine and Antarctic Studies. " + DOI_URL
)
# Official "latest micromamba" address from the mamba docs; {plat} is e.g. linux-64, osx-arm64, win-64.
MAMBA_API = "https://micro.mamba.pm/api/micromamba/{plat}/latest"

# Only the first three depth ranges are stated in the dataset record; the rest are named, not guessed.
ZONES = [
    {"key": "shallow", "file": "01_shallow_bathy.tif", "label": "Shallow (0-30 m)"},
    {"key": "mesophotic", "file": "02_mesophotic_bathy.tif", "label": "Mesophotic (30-70 m)"},
    {"key": "rariphotic", "file": "03_rariphotic_bathy.tif", "label": "Rariphotic (70-200 m)"},
    {"key": "upperslope", "file": "04_upperslope_bathy.tif", "label": "Upper slope"},
    {"key": "midslope", "file": "05_midslope_bathy.tif", "label": "Mid slope"},
    {"key": "lowerslope", "file": "06_lowerslope_bathy.tif", "label": "Lower slope"},
    {"key": "abyss", "file": "07_abyss_bathy.tif", "label": "Abyss"},
    {"key": "hadal", "file": "08_hadal_bathy.tif", "label": "Hadal"},
]
for _i, _z in enumerate(ZONES):
    _z["idx"] = _i

CRS_CODES = {"wgs84": "EPSG:4326", "albers": "EPSG:9473"}
CRS_LABELS = {"wgs84": "WGS84 lat/long", "albers": "GDA2020 Australian Albers (metres)"}
RESAMPLING = ("auto", "near", "average", "bilinear", "cubic")
M_PER_DEG = 111_320.0
GB = 1024 ** 3
DEFAULT_MAX_GB = 8.0
SETUP_FREE_BYTES = int(1.5 * GB)
RES_LADDER = [10, 15, 20, 25, 30, 50, 100, 200, 250, 500, 1000, 2000, 5000]
LOOPBACK = {"127.0.0.1", "localhost", "[::1]", "::1"}
FILE_NAME_RE = re.compile(r"^IMAS-bathymetry_[A-Za-z0-9._-]+\.tif$")
BAD_FOLDER_CHARS = re.compile(r'[\\/:*?"<>|\x00-\x1f]')


# --------------------------------------------------------------------------- helpers

class NotReady(Exception):
    """GDAL isn't installed yet."""


def fmt_bytes(n: float) -> str:
    n = float(n)
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if n < 1024 or unit == "TB":
            return f"{n:.0f} {unit}" if unit in ("B", "KB") else f"{n:.1f} {unit}"
        n /= 1024
    return f"{n:.1f} TB"


def last_line(text: str) -> str:
    lines = [ln.strip() for ln in (text or "").splitlines() if ln.strip()]
    return lines[-1] if lines else ""


def nearest_existing(p) -> Path:
    p = Path(os.path.abspath(p))
    while not p.exists() and p.parent != p:
        p = p.parent
    return p


def is_inside(child, parent) -> bool:
    c, q = os.path.realpath(child), os.path.realpath(parent)
    try:
        return os.path.commonpath([c, q]) == q
    except ValueError:  # different drives on Windows
        return False


def default_home() -> Path:
    """Where the private GDAL copy lives."""
    if os.name == "nt":
        base = Path(os.environ.get("LOCALAPPDATA") or (Path.home() / "AppData" / "Local"))
    elif sys.platform == "darwin":
        base = Path.home() / "Library" / "Application Support"
    else:
        base = Path(os.environ.get("XDG_DATA_HOME") or (Path.home() / ".local" / "share"))
    return base / "bathy_picker"


def box_metres(bbox):
    w, s, e, n = bbox
    mid = math.radians((s + n) / 2)
    return (e - w) * M_PER_DEG * math.cos(mid), (n - s) * M_PER_DEG


def output_pixels(bbox, res_m: float, crs: str):
    """Approximate output raster size for a box at a given resolution."""
    w, s, e, n = bbox
    if crs == "wgs84":
        res_deg = res_m / M_PER_DEG
        return max(1, math.ceil((e - w) / res_deg)), max(1, math.ceil((n - s) / res_deg))
    wm, hm = box_metres(bbox)
    return max(1, math.ceil(wm / res_m)), max(1, math.ceil(hm / res_m))


def boxes_intersect(a, b) -> bool:
    return not (a[2] <= b[0] or a[0] >= b[2] or a[3] <= b[1] or a[1] >= b[3])


def choose_resolution(bbox, finest_m: float, crs: str, mode: str, cap_bytes: float):
    """Return (resolution in metres, short explanation)."""
    finest = max(round(finest_m, 3), 0.5)
    if mode == "finest":
        return finest, "Finest detail in the layers you picked."
    if mode != "auto":
        return float(mode), "As you asked."
    ladder = [finest] + [r for r in RES_LADDER if r > finest * 1.01]
    for r in ladder:
        nx, ny = output_pixels(bbox, r, crs)
        if nx * ny * 4 <= cap_bytes:
            return r, ("Full detail." if r == finest else "Finest detail that fits under the size limit.")
    return ladder[-1], "Even the coarsest option is over the size limit."


def make_filename(bbox, res_m: float, crs: str) -> str:
    w, s, e, n = bbox

    def lat(v):
        return f"{abs(v):.2f}{'N' if v >= 0 else 'S'}"

    def lon(v):
        return f"{abs(v):.2f}{'E' if v >= 0 else 'W'}"

    res = f"{res_m:g}".replace(".", "p")
    suffix = "" if crs == "wgs84" else "_albers"
    stamp = time.strftime("%Y%m%d-%H%M%S")
    return f"IMAS-bathymetry_{lat(s)}-{lat(n)}_{lon(w)}-{lon(e)}_{res}m{suffix}_{stamp}.tif"


def gdal_env() -> dict:
    env = dict(os.environ)
    env.update({
        "GDAL_DISABLE_READDIR_ON_OPEN": "EMPTY_DIR",
        "CPL_VSIL_CURL_ALLOWED_EXTENSIONS": ".tif",
        "GDAL_HTTP_MAX_RETRY": "6",
        "GDAL_HTTP_RETRY_DELAY": "3",
        "GDAL_HTTP_TIMEOUT": "120",
        "GDAL_HTTP_CONNECTTIMEOUT": "30",
        "VSI_CACHE": "TRUE",
        "VSI_CACHE_SIZE": str(256 * 1024 * 1024),
    })
    return env


# --------------------------------------------------------------------------- GDAL access

@dataclass
class Tools:
    gdalinfo: str
    gdalwarp: str
    has_cog: bool
    version: str
    env: dict = field(default_factory=dict)
    managed: bool = False  # True when it's the private copy this script downloaded


def probe_tools(gi: str, gw: str, env: dict, managed: bool = False):
    try:
        r = subprocess.run([gw, "--version"], capture_output=True, text=True, timeout=60, env=env)
    except (OSError, subprocess.SubprocessError):
        return None
    if r.returncode != 0:
        return None
    version = (r.stdout.strip().splitlines() or [""])[0]
    has_cog = False
    try:
        r2 = subprocess.run([gi, "--formats"], capture_output=True, text=True, timeout=60, env=env)
        has_cog = bool(re.search(r"^\s*COG\b", r2.stdout, re.M))
    except (OSError, subprocess.SubprocessError):
        pass
    return Tools(gi, gw, has_cog, version, env, managed)


def find_in_prefix(prefix: Path, name: str):
    for cand in (prefix / "bin" / name, prefix / "Library" / "bin" / (name + ".exe"),
                 prefix / "Scripts" / (name + ".exe"), prefix / "bin" / (name + ".exe"), prefix / (name + ".exe")):
        if cand.exists():
            return str(cand)
    return None


def setup_paths(home: Path) -> dict:
    return {
        "root": home / "mamba-root",
        "prefix": home / "gdal-env",
        "exe": home / ("micromamba.exe" if os.name == "nt" else "micromamba"),
        "envfile": home / "gdal-env.json",
    }


def load_managed_tools(home: Path):
    P = setup_paths(home)
    if not (P["envfile"].exists() and P["prefix"].exists()):
        return None
    try:
        saved = json.loads(P["envfile"].read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    if saved.get("prefix") != str(P["prefix"]):
        return None
    env = gdal_env()
    env.update(saved.get("env", {}))
    entries = saved.get("path", [])
    if entries:
        env["PATH"] = os.pathsep.join(entries + [env.get("PATH", "")])
    gi, gw = find_in_prefix(P["prefix"], "gdalinfo"), find_in_prefix(P["prefix"], "gdalwarp")
    if not (gi and gw):
        return None
    return probe_tools(gi, gw, env, managed=True)


def find_tools(gdal_bin, home: Path):
    """GDAL on the given folder or PATH first, then the private copy from --setup."""
    def which(name):
        if gdal_bin:
            for cand in (name, name + ".exe"):
                p = Path(gdal_bin) / cand
                if p.exists():
                    return str(p)
        return shutil.which(name)

    gi, gw = which("gdalinfo"), which("gdalwarp")
    if gi and gw:
        tools = probe_tools(gi, gw, gdal_env())
        if tools:
            return tools
    return load_managed_tools(home)


def run_capture(cmd, env=None, timeout=180):
    p = subprocess.run(cmd, capture_output=True, text=True, env=env, timeout=timeout)
    return p.returncode, p.stdout, p.stderr


def read_zone_info(tools: Tools, zone: dict) -> dict:
    """Read just the header of a remote zone file (a few KB, not the 20+ GB)."""
    url = "/vsicurl/" + BASE_URL + zone["file"]
    try:
        code, out, err = run_capture([tools.gdalinfo, "-json", "-nomd", url], env=tools.env, timeout=180)
    except subprocess.TimeoutExpired:
        raise RuntimeError(f"Timed out reading {zone['file']} from IMAS. Check your internet connection.")
    if code != 0:
        raise RuntimeError(
            f"Could not read {zone['file']} from IMAS ({last_line(err) or 'no details'}). "
            "Check your internet connection."
        )
    info = json.loads(out)
    gt = info.get("geoTransform")
    if not gt:
        raise RuntimeError(f"{zone['file']} has no geotransform, so it can't be used.")
    px = min(abs(gt[1]), abs(gt[5]))
    if px < 1:  # geographic degrees -> approximate metres
        px *= M_PER_DEG
    extent = None
    ring = (info.get("wgs84Extent") or {}).get("coordinates")
    if ring and ring[0]:
        lons = [p[0] for p in ring[0]]
        lats = [p[1] for p in ring[0]]
        extent = (min(lons), min(lats), max(lons), max(lats))
    crs_name = ""
    m = re.match(r'\s*\w+\["([^"]+)"', (info.get("coordinateSystem") or {}).get("wkt", ""))
    if m:
        crs_name = m.group(1)
    return {"px_m": px, "size": info.get("size"), "extent": extent, "crs": crs_name}


class ZoneInfoCache:
    def __init__(self, tools: Tools):
        self.tools = tools
        self._data = {}
        self._lock = threading.Lock()

    def get_many(self, zones):
        with self._lock:
            missing = [z for z in zones if z["key"] not in self._data]
        if missing:
            with ThreadPoolExecutor(max_workers=4) as pool:
                for z, info in zip(missing, pool.map(lambda z: read_zone_info(self.tools, z), missing)):
                    with self._lock:
                        self._data[z["key"]] = info
        with self._lock:
            return {z["key"]: self._data[z["key"]] for z in zones}


# --------------------------------------------------------------------------- folders

def browse_start(app, raw) -> Path:
    root = app.browse_root()
    if raw and str(raw).strip():
        p = Path(os.path.abspath(Path(str(raw).strip()).expanduser()))
    else:
        p = app.out_dir
    p = nearest_existing(p)
    if not p.is_dir():
        p = p.parent
    if root is not None and not is_inside(p, root):
        p = root
    return p


def list_dirs(app, raw) -> dict:
    base = browse_start(app, raw)
    root = app.browse_root()
    names = []
    try:
        with os.scandir(base) as it:
            for entry in it:
                if entry.name.startswith("."):
                    continue
                try:
                    if entry.is_dir():
                        names.append(entry.name)
                except OSError:
                    continue
    except OSError:
        raise ValueError(f"Can't open {base}.")
    names.sort(key=str.lower)
    parent = None
    if base.parent != base and (root is None or is_inside(base.parent, root)):
        parent = str(base.parent)
    roots = []
    if root is None and os.name == "nt":
        roots = [f"{c}:\\" for c in string.ascii_uppercase if os.path.exists(f"{c}:\\")]
    return {
        "path": str(base), "parent": parent, "roots": roots, "home": str(Path.home()),
        "dirs": [{"name": n, "path": str(base / n)} for n in names[:500]],
        "truncated": len(names) > 500, "writable": os.access(base, os.W_OK),
    }


def make_dir(app, parent_raw, name) -> str:
    name = str(name or "").strip()
    if not name or len(name) > 100 or BAD_FOLDER_CHARS.search(name) or name in (".", "..") or name.endswith("."):
        raise ValueError('Folder names can\'t be empty or contain / \\ : * ? " < > |')
    target = browse_start(app, parent_raw) / name
    try:
        target.mkdir(exist_ok=True)
    except OSError as exc:
        raise ValueError(f"Couldn't create that folder ({exc.strerror or exc}).")
    return str(target)


def resolve_out_dir(value, app) -> Path:
    if value is None or not str(value).strip():
        return app.out_dir
    s = str(value).strip()
    if len(s) > 1000 or "\x00" in s:
        raise ValueError("That save folder isn't valid.")
    p = Path(s).expanduser()
    if not p.is_absolute():
        p = app.out_dir / p
    p = Path(os.path.abspath(p))
    if p.exists() and not p.is_dir():
        raise ValueError(f"{p} is a file, not a folder.")
    root = app.browse_root()
    if root is not None and not is_inside(nearest_existing(p), root):
        raise ValueError("While the script is reachable over the network, files can only be saved inside your home folder.")
    return p


# --------------------------------------------------------------------------- planning

def parse_params(raw: dict, app) -> dict:
    try:
        w, s, e, n = [float(v) for v in raw["bbox"]]
    except (KeyError, TypeError, ValueError):
        raise ValueError("Pick an area first: west, south, east and north are all needed.")
    if not all(math.isfinite(v) for v in (w, s, e, n)):
        raise ValueError("The box edges must be numbers.")
    if not (-180 <= w < e <= 180 and -90 <= s < n <= 90):
        raise ValueError("West must be less than east and south less than north (latitude -90 to 90, longitude -180 to 180).")
    if e - w < 0.0005 or n - s < 0.0005:
        raise ValueError("The box is too small. Drag the corners further apart.")
    if e - w > 90 or n - s > 70:
        raise ValueError("The box is too large. Zoom in and pick a smaller area.")
    wanted = raw.get("zones")
    if wanted is None or wanted == "all":
        wanted = [z["key"] for z in ZONES]
    if not isinstance(wanted, list):
        raise ValueError("Pick at least one depth layer.")
    known = {z["key"] for z in ZONES}
    bad = [k for k in wanted if k not in known]
    if bad:
        raise ValueError("Unknown depth layer: " + ", ".join(map(str, bad)))
    keys = [z["key"] for z in ZONES if z["key"] in wanted]
    if not keys:
        raise ValueError("Pick at least one depth layer.")
    crs = raw.get("crs", "wgs84")
    if crs not in CRS_CODES:
        raise ValueError("Unknown output coordinate system.")
    resolution = str(raw.get("resolution", "auto")).strip().lower()
    if resolution not in ("auto", "finest"):
        try:
            r = float(resolution)
        except ValueError:
            raise ValueError("Resolution must be auto, finest, or a number of metres.")
        if not (1 <= r <= 20000):
            raise ValueError("Resolution must be between 1 and 20000 metres.")
        resolution = f"{r:g}"
    resampling = raw.get("resampling", "auto")
    if resampling not in RESAMPLING:
        raise ValueError("Unknown resampling method.")
    return {"bbox": (w, s, e, n), "zones": keys, "crs": crs, "resolution": resolution,
            "resampling": resampling, "out_dir": resolve_out_dir(raw.get("out_dir"), app)}


def assess(p: dict, app) -> dict:
    tools, cache = app.tools, app.cache
    if tools is None or cache is None:
        raise NotReady("GDAL isn't set up yet. Use the setup card first.")
    zones = [z for z in ZONES if z["key"] in p["zones"]]
    infos = cache.get_many(zones)
    bbox, crs = p["bbox"], p["crs"]
    bw_m, bh_m = box_metres(bbox)
    cap_bytes = app.max_gb * GB
    errors, warnings, rows, covered = [], [], [], []
    for z in zones:
        info = infos[z["key"]]
        hit = info["extent"] is None or boxes_intersect(bbox, info["extent"])
        rows.append({
            "key": z["key"], "label": z["label"], "file": z["file"],
            "native_res_m": round(info["px_m"], 3),
            "window_px": [math.ceil(bw_m / info["px_m"]), math.ceil(bh_m / info["px_m"])],
            "covered": hit,
        })
        if hit:
            covered.append((z, info))
    if not covered:
        errors.append("This box is outside the area the dataset covers (Australia's exclusive economic zone).")
        return {"ok": False, "errors": errors, "warnings": warnings, "zones": rows}

    finest = min(info["px_m"] for _, info in covered)
    res_m, note = choose_resolution(bbox, finest, crs, p["resolution"], cap_bytes)
    nx, ny = output_pixels(bbox, res_m, crs)
    est = nx * ny * 4
    if p["resolution"] not in ("auto", "finest") and res_m < finest * 0.99:
        warnings.append(f"{res_m:g} m is finer than the data itself ({round(finest, 1):g} m). The file gets bigger with no extra detail.")
    if est > cap_bytes:
        errors.append(
            f"About {fmt_bytes(est)} is over the {app.max_gb:g} GB limit. Pick a smaller box or a coarser resolution "
            "(or start the script with a higher --max-gb)."
        )
    skipped = [r["label"] for r in rows if not r["covered"]]
    if skipped:
        warnings.append("No coverage in this box for: " + ", ".join(skipped) + ".")
    warnings.append("Only seafloor inside Australian waters has data. Land and other countries' waters stay empty (NoData).")

    out_dir = p["out_dir"]
    anchor = nearest_existing(out_dir)
    if not os.access(anchor, os.W_OK):
        errors.append(f"Can't write to {out_dir}. Pick another folder.")
    free = shutil.disk_usage(anchor).free
    if free < est * 0.7:
        errors.append(f"Not enough free disk space for {out_dir} ({fmt_bytes(free)} free, roughly {fmt_bytes(est * 0.7)} needed).")
    elif free < est * 1.2:
        warnings.append(f"Disk space is tight for {out_dir} ({fmt_bytes(free)} free).")

    ordered = sorted(covered, key=lambda zi: (-zi[1]["px_m"], -zi[0]["idx"]))  # coarse first, finest last = finest wins
    return {
        "ok": not errors, "errors": errors, "warnings": warnings, "zones": rows,
        "bbox": list(bbox), "box_km": [round(bw_m / 1000, 1), round(bh_m / 1000, 1)],
        "crs": crs, "crs_label": CRS_LABELS[crs],
        "finest_res_m": round(finest, 3), "res_m": res_m, "res_note": note,
        "output_px": [nx, ny], "est_bytes": est, "cap_bytes": cap_bytes, "free_bytes": free,
        "format": "Cloud-Optimized GeoTIFF" if tools.has_cog else "tiled GeoTIFF (this GDAL has no COG driver)",
        "files": [z["file"] for z, _ in ordered],
        "filename": make_filename(bbox, res_m, crs), "out_dir": str(out_dir),
    }


def build_warp_cmd(tools: Tools, plan: dict, resampling: str, out_path: Path) -> list:
    w, s, e, n = plan["bbox"]
    crs_key = plan["crs"]
    res = plan["res_m"] / M_PER_DEG if crs_key == "wgs84" else plan["res_m"]
    if resampling == "auto":
        resampling = "near" if plan["res_m"] <= plan["finest_res_m"] * 1.01 else "average"
    cmd = [
        tools.gdalwarp, "-overwrite", "-multi", "-wo", "NUM_THREADS=ALL_CPUS", "-wm", "512",
        "-te", f"{w:.8f}", f"{s:.8f}", f"{e:.8f}", f"{n:.8f}", "-te_srs", "EPSG:4326",
        "-t_srs", CRS_CODES[crs_key], "-tr", f"{res:.12f}", f"{res:.12f}", "-r", resampling,
    ]
    if tools.has_cog:
        cmd += ["-of", "COG", "-co", "COMPRESS=DEFLATE", "-co", "PREDICTOR=3", "-co", "BLOCKSIZE=256",
                "-co", "RESAMPLING=AVERAGE", "-co", "BIGTIFF=IF_SAFER", "-co", "NUM_THREADS=ALL_CPUS"]
    else:
        cmd += ["-of", "GTiff", "-co", "TILED=YES", "-co", "COMPRESS=DEFLATE", "-co", "PREDICTOR=3",
                "-co", "BIGTIFF=IF_SAFER", "-co", "NUM_THREADS=ALL_CPUS"]
    cmd += ["/vsicurl/" + BASE_URL + f for f in plan["files"]]
    cmd.append(str(out_path))
    return cmd


# --------------------------------------------------------------------------- jobs

class Job:
    """One long-running task (a build or the GDAL setup) that the page polls."""

    def __init__(self):
        self.lock = threading.Lock()
        self.state = "idle"  # idle | running | done | error | cancelled
        self.percent = 0.0   # -1 means "working, no percentage"
        self.passes = 0
        self.stage = ""
        self.log = deque(maxlen=120)
        self.result = None
        self.error = None
        self.proc = None
        self.cancel_requested = False
        self.started = None
        self.ended = None

    def start(self) -> bool:
        with self.lock:
            if self.state == "running":
                return False
            self.state, self.percent, self.passes, self.stage = "running", 0.0, 1, "Starting"
            self.log.clear()
            self.result = self.error = self.proc = None
            self.cancel_requested = False
            self.started, self.ended = time.time(), None
            return True

    def update(self, **kw):
        with self.lock:
            for k, v in kw.items():
                setattr(self, k, v)

    def add_log(self, line: str):
        with self.lock:
            self.log.append(line)

    def snapshot(self) -> dict:
        with self.lock:
            end = self.ended or time.time()
            return {
                "state": self.state, "percent": self.percent, "passes": self.passes, "stage": self.stage,
                "log": list(self.log)[-40:], "result": self.result, "error": self.error,
                "elapsed": round(end - self.started, 1) if self.started else 0,
            }

    def cancel(self) -> bool:
        with self.lock:
            if self.state != "running":
                return False
            self.cancel_requested = True
            proc = self.proc
        if proc and proc.poll() is None:
            try:
                proc.terminate()
            except OSError:
                pass
        return True


PROGRESS_RE = re.compile(r"(\d{1,3})(?:\.\.\.|(?=\s*-\s*done))")
PROGRESS_LINE_RE = re.compile(r"^[0-9.\s]*(-\s*done\.?)?$")


def pump_output(proc, job: Job):
    """Read gdalwarp output: GDAL prints '0...10...20...100 - done.' for progress."""
    acc, line_buf = "", ""
    while True:
        chunk = proc.stdout.read1(4096) if hasattr(proc.stdout, "read1") else proc.stdout.read(1)
        if not chunk:
            break
        text = chunk.decode("utf-8", "replace")
        acc += text
        found = PROGRESS_RE.findall(acc)
        if found:
            pct = min(100, int(found[-1]))
            with job.lock:
                if pct < job.percent:
                    job.passes += 1
                job.percent = float(pct)
        line_buf += text
        while "\n" in line_buf:
            line, line_buf = line_buf.split("\n", 1)
            acc = ""
            line = line.strip()
            if line and not PROGRESS_LINE_RE.match(line):
                job.add_log(line)
    tail = line_buf.strip()
    if tail and not PROGRESS_LINE_RE.match(tail):
        job.add_log(tail)


def pump_lines(proc, job: Job):
    """Read installer output line by line (carriage returns count as line breaks)."""
    buf = ""
    while True:
        chunk = proc.stdout.read1(4096) if hasattr(proc.stdout, "read1") else proc.stdout.read(1)
        if not chunk:
            break
        buf += chunk.decode("utf-8", "replace")
        parts = re.split(r"[\r\n]+", buf)
        buf = parts.pop()
        for line in parts:
            if line.strip():
                job.add_log(line.strip())
    if buf.strip():
        job.add_log(buf.strip())


def cleanup_partials(out_dir: Path, filename: str):
    for p in out_dir.glob(filename + ".part*"):
        try:
            p.unlink()
        except OSError:
            pass


def run_build(app, job: Job, p: dict, plan: dict):
    out_dir = p["out_dir"]
    final = out_dir / plan["filename"]
    part = out_dir / (plan["filename"] + ".part")
    tools = app.tools
    try:
        out_dir.mkdir(parents=True, exist_ok=True)
        app.remember_dir(out_dir)
        cleanup_partials(out_dir, plan["filename"])
        cmd = build_warp_cmd(tools, plan, p["resampling"], part)
        job.add_log("$ " + " ".join(cmd))
        job.update(stage="Reading from IMAS and merging the depth layers")
        try:
            proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=tools.env)
        except OSError as exc:
            job.update(state="error", error=f"Could not start gdalwarp: {exc}", ended=time.time())
            return
        job.update(proc=proc)
        if job.cancel_requested and proc.poll() is None:
            proc.terminate()
        reader = threading.Thread(target=pump_output, args=(proc, job), daemon=True)
        reader.start()
        rc = proc.wait()
        reader.join(timeout=5)

        if job.cancel_requested:
            cleanup_partials(out_dir, plan["filename"])
            job.update(state="cancelled", stage="Cancelled", ended=time.time())
            return
        if rc != 0 or not part.exists():
            cleanup_partials(out_dir, plan["filename"])
            tail = [ln for ln in job.snapshot()["log"] if not ln.startswith("$ ")][-3:]
            job.update(state="error", ended=time.time(),
                       error="gdalwarp failed: " + (" | ".join(tail) if tail else f"exit code {rc}"))
            return

        os.replace(part, final)
        job.update(stage="Checking the result")
        result = {
            "name": final.name, "path": str(final), "bytes": final.stat().st_size,
            "bbox": plan["bbox"], "res_m": plan["res_m"], "crs_label": plan["crs_label"],
            "format": plan["format"], "min": None, "max": None, "valid_percent": None, "size_px": None,
        }
        try:
            code, out, _ = run_capture([tools.gdalinfo, "-json", "-approx_stats", str(final)], env=tools.env, timeout=900)
            if code == 0:
                info = json.loads(out)
                result["size_px"] = info.get("size")
                band = (info.get("bands") or [{}])[0]
                md = (band.get("metadata") or {}).get("", {})
                for key, fld in (("STATISTICS_MINIMUM", "min"), ("STATISTICS_MAXIMUM", "max"),
                                 ("STATISTICS_VALID_PERCENT", "valid_percent")):
                    if key in md:
                        result[fld] = float(md[key])
                if result["min"] is None and "minimum" in band:
                    result["min"], result["max"] = band.get("minimum"), band.get("maximum")
        except (ValueError, OSError, subprocess.SubprocessError):
            pass
        finally:
            try:
                Path(str(final) + ".aux.xml").unlink()
            except OSError:
                pass
        job.update(state="done", stage="Done", percent=100.0, result=result, ended=time.time())
    except Exception as exc:  # keep the server alive whatever happens
        cleanup_partials(out_dir, plan["filename"])
        job.update(state="error", error=f"Unexpected error: {exc}", ended=time.time())


# --------------------------------------------------------------------------- GDAL setup (private copy)

class SetupError(Exception):
    pass


class SetupCancelled(Exception):
    pass


def mamba_platform():
    sysname, machine = platform.system(), platform.machine().lower()
    if sysname == "Linux":
        return {"x86_64": "linux-64", "amd64": "linux-64", "aarch64": "linux-aarch64",
                "arm64": "linux-aarch64", "ppc64le": "linux-ppc64le"}.get(machine)
    if sysname == "Darwin":
        return {"arm64": "osx-arm64", "x86_64": "osx-64"}.get(machine)
    if sysname == "Windows":
        return {"amd64": "win-64", "x86_64": "win-64", "arm64": "win-64"}.get(machine)
    return None


def download_file(url: str, dest: Path, job: Job, lo: float, hi: float):
    req = urllib.request.Request(url, headers={"User-Agent": f"bathy_picker/{APP_VERSION}"})
    try:
        with urllib.request.urlopen(req, timeout=60) as resp, open(dest, "wb") as out:
            total = int(resp.headers.get("Content-Length") or 0)
            done = 0
            while True:
                if job.cancel_requested:
                    raise SetupCancelled()
                block = resp.read(256 * 1024)
                if not block:
                    break
                out.write(block)
                done += len(block)
                if total:
                    job.update(percent=lo + (hi - lo) * done / total)
    except urllib.error.HTTPError as exc:
        raise SetupError(f"The download failed (HTTP {exc.code}) from {url}.")
    except (urllib.error.URLError, ssl.SSLError, OSError) as exc:
        reason = getattr(exc, "reason", exc)
        if "CERTIFICATE_VERIFY_FAILED" in str(reason):
            raise SetupError("Python couldn't verify the download's HTTPS certificate. On macOS, run "
                             "'Install Certificates.command' from your Python folder in Applications, then try again.")
        raise SetupError(f"Couldn't download {url} ({reason}). Check your internet connection.")


def extract_micromamba(archive: Path, exe: Path, plat: str):
    want = "Library/bin/micromamba.exe" if plat.startswith("win") else "bin/micromamba"
    try:
        with tarfile.open(archive, "r:bz2") as tf:
            member = None
            for m in tf.getmembers():
                if m.name.lstrip("./") == want:
                    member = m
                    break
            if member is None:
                raise SetupError(f"The installer download didn't contain {want}.")
            src = tf.extractfile(member)
            if src is None:
                raise SetupError("The installer download was damaged.")
            tmp = exe.with_name(exe.name + ".tmp")
            with src, open(tmp, "wb") as out:
                shutil.copyfileobj(src, out)
            os.replace(tmp, exe)
    except (tarfile.TarError, OSError, EOFError) as exc:
        raise SetupError(f"Couldn't unpack the installer ({exc}).")
    if os.name != "nt":
        os.chmod(exe, 0o755)


def capture_activated_env(exe: Path, root: Path, prefix: Path):
    """Ask micromamba what the environment looks like once activated, using this Python to print it."""
    code = "import os,json,sys;sys.stdout.write('@@ENV@@'+json.dumps(dict(os.environ)))"
    try:
        r = subprocess.run([str(exe), "run", "-r", str(root), "-p", str(prefix), sys.executable, "-c", code],
                           capture_output=True, text=True, timeout=180,
                           env=dict(os.environ, MAMBA_ROOT_PREFIX=str(root)))
        if r.returncode == 0 and "@@ENV@@" in r.stdout:
            return json.loads(r.stdout.split("@@ENV@@", 1)[1])
    except (OSError, subprocess.SubprocessError, ValueError):
        pass
    return None


def compute_overrides(active: dict, prefix: Path):
    """Keep only what activation changed: PATH entries inside the prefix, plus changed variables."""
    pre = os.path.normcase(str(prefix))
    path_entries, overrides = [], {}
    for k, v in active.items():
        if k.upper() == "PATH":
            path_entries = [e for e in v.split(os.pathsep) if e and os.path.normcase(e).startswith(pre)]
        elif os.environ.get(k) != v and not k.startswith("_") and k.upper() not in ("PS1", "SHLVL", "OLDPWD", "PWD"):
            overrides[k] = v
    return overrides, path_entries


def manual_overrides(prefix: Path):
    """Fallback if activation couldn't be captured: what conda activation sets for GDAL."""
    if os.name == "nt":
        dirs = [prefix / "Library" / "mingw-w64" / "bin", prefix / "Library" / "usr" / "bin",
                prefix / "Library" / "bin", prefix / "Scripts", prefix / "bin", prefix]
        data = prefix / "Library" / "share"
    else:
        dirs = [prefix / "bin"]
        data = prefix / "share"
    overrides = {"CONDA_PREFIX": str(prefix)}
    for var, sub in (("GDAL_DATA", "gdal"), ("PROJ_DATA", "proj"), ("PROJ_LIB", "proj")):
        if (data / sub).exists():
            overrides[var] = str(data / sub)
    return overrides, [str(d) for d in dirs if d.exists()]


def run_setup(app, job: Job):
    P = setup_paths(app.home)
    try:
        plat = mamba_platform()
        if not plat:
            raise SetupError(f"Automatic setup isn't available for {platform.system()} {platform.machine()}. "
                             "Install GDAL yourself (see the top of the script) and restart.")
        try:
            app.home.mkdir(parents=True, exist_ok=True)
        except OSError as exc:
            raise SetupError(f"Can't create {app.home} ({exc.strerror or exc}). Use --home to pick another folder.")
        free = shutil.disk_usage(app.home).free
        if free < SETUP_FREE_BYTES:
            raise SetupError(f"About {fmt_bytes(SETUP_FREE_BYTES)} of free space is needed in {app.home} "
                             f"({fmt_bytes(free)} free). Free some space or use --home to pick another drive.")
        if not P["exe"].exists():
            job.update(stage="Downloading the installer", percent=0.0)
            archive = app.home / "micromamba.tar.bz2"
            download_file(MAMBA_API.format(plat=plat), archive, job, 0.0, 12.0)
            job.update(stage="Unpacking the installer", percent=12.0)
            extract_micromamba(archive, P["exe"], plat)
            try:
                archive.unlink()
            except OSError:
                pass
        if P["prefix"].exists():  # a half-finished earlier attempt
            shutil.rmtree(P["prefix"], ignore_errors=True)
        job.update(stage="Installing GDAL (a few minutes, one time)", percent=-1.0)
        env = dict(os.environ, MAMBA_ROOT_PREFIX=str(P["root"]))
        cmd = [str(P["exe"]), "create", "-y", "--no-rc", "-r", str(P["root"]), "-p", str(P["prefix"]),
               "-c", "conda-forge", "--override-channels", "libgdal-core"]
        job.add_log("$ " + " ".join(cmd))
        try:
            proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=env)
        except OSError as exc:
            raise SetupError(f"Couldn't run the installer ({exc}).")
        job.update(proc=proc)
        if job.cancel_requested and proc.poll() is None:
            proc.terminate()
        reader = threading.Thread(target=pump_lines, args=(proc, job), daemon=True)
        reader.start()
        rc = proc.wait()
        reader.join(timeout=5)
        if job.cancel_requested:
            raise SetupCancelled()
        if rc != 0:
            tail = [ln for ln in job.snapshot()["log"] if not ln.startswith("$ ")][-3:]
            raise SetupError("The GDAL install failed: " + (" | ".join(tail) if tail else f"exit code {rc}"))

        job.update(stage="Checking GDAL", percent=96.0)
        active = capture_activated_env(P["exe"], P["root"], P["prefix"])
        overrides, entries = compute_overrides(active, P["prefix"]) if active else manual_overrides(P["prefix"])
        P["envfile"].write_text(json.dumps({"prefix": str(P["prefix"]), "env": overrides, "path": entries}), encoding="utf-8")
        tools = load_managed_tools(app.home)
        if tools is None:
            raise SetupError("GDAL installed, but its programs wouldn't start. The details below may say why.")
        job.update(stage="Tidying up", percent=98.0)
        try:  # drop the installer's package cache; the environment keeps what it needs
            subprocess.run([str(P["exe"]), "clean", "--all", "--yes", "-r", str(P["root"])],
                           capture_output=True, timeout=300, env=env)
        except (OSError, subprocess.SubprocessError):
            pass
        app.install_tools(tools)
        job.update(state="done", stage="GDAL is ready", percent=100.0, ended=time.time(),
                   result={"version": tools.version, "cog": tools.has_cog})
    except SetupCancelled:
        shutil.rmtree(P["prefix"], ignore_errors=True)
        job.update(state="cancelled", stage="Cancelled", ended=time.time())
    except SetupError as exc:
        job.update(state="error", error=str(exc), ended=time.time())
    except Exception as exc:  # keep the server alive whatever happens
        job.update(state="error", error=f"Unexpected error during setup: {exc}", ended=time.time())


# --------------------------------------------------------------------------- web server

class App:
    def __init__(self, tools, home, out_dir, max_gb, host, token):
        self.home = Path(home)
        self.out_dir = Path(out_dir).expanduser().resolve()
        self.out_dirs = [self.out_dir]
        self.max_gb = max_gb
        self.token = token
        self.job = Job()
        self.setup_job = Job()
        self.strict_host = host in LOOPBACK
        self.lock = threading.Lock()
        self.tools = None
        self.cache = None
        if tools:
            self.install_tools(tools)

    def install_tools(self, tools: Tools):
        with self.lock:
            self.tools = tools
            self.cache = ZoneInfoCache(tools)

    def remember_dir(self, d: Path):
        d = Path(os.path.abspath(d))
        with self.lock:
            if d not in self.out_dirs:
                self.out_dirs.append(d)

    def browse_root(self):
        """None means the whole disk. When the page is reachable over the network it's limited to the home folder."""
        return None if self.strict_host else Path.home()

    def config_json(self) -> str:
        plat = mamba_platform()
        tools = self.tools
        cfg = {
            "zones": [{"key": z["key"], "label": z["label"]} for z in ZONES],
            "maxGb": self.max_gb, "outDir": str(self.out_dir), "citation": CITATION, "doi": DOI_URL,
            "gdalVersion": tools.version if tools else "",
            "crs": [{"key": k, "label": v} for k, v in CRS_LABELS.items()],
            "setup": {"needed": tools is None, "home": str(self.home), "supported": plat is not None},
        }
        return json.dumps(cfg).replace("<", "\\u003c")


class Handler(BaseHTTPRequestHandler):
    server_version = f"BathyPicker/{APP_VERSION}"

    @property
    def app(self) -> App:
        return self.server.app  # type: ignore[attr-defined]

    def log_message(self, fmt, *args):  # keep the terminal quiet
        pass

    def _send(self, code, body: bytes, ctype="application/json", extra=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def _json(self, code, obj):
        self._send(code, json.dumps(obj).encode("utf-8"))

    def _host_ok(self) -> bool:
        if not self.app.strict_host:
            return True
        m = re.match(r"^(\[[^\]]+\]|[^:]+)", self.headers.get("Host", ""))
        return bool(m) and m.group(1).lower() in LOOPBACK

    def _token_ok(self, supplied) -> bool:
        return bool(supplied) and hmac.compare_digest(str(supplied), self.app.token)

    def do_GET(self):
        url = urlparse(self.path)
        query = parse_qs(url.query)
        if not self._host_ok():
            return self._json(403, {"error": "Unexpected Host header."})
        if url.path == "/":
            if not self.app.strict_host and not self._token_ok((query.get("t") or [""])[0]):
                return self._json(403, {"error": "Open the URL the script printed, including the ?t= key."})
            page = HTML.replace("__CONFIG__", self.app.config_json()).replace("__TOKEN__", self.app.token)
            return self._send(200, page.encode("utf-8"), "text/html; charset=utf-8")
        if url.path.startswith("/files/"):
            if not self._token_ok((query.get("t") or [""])[0]):
                return self._json(403, {"error": "Missing or wrong key."})
            return self._serve_file(url.path[len("/files/"):])
        if url.path in ("/api/status", "/api/setup/status", "/api/dirs"):
            if not self._token_ok(self.headers.get("X-Token")):
                return self._json(403, {"error": "Missing or wrong key."})
            if url.path == "/api/status":
                return self._json(200, self.app.job.snapshot())
            if url.path == "/api/setup/status":
                snap = self.app.setup_job.snapshot()
                snap["needed"] = self.app.tools is None
                snap["version"] = self.app.tools.version if self.app.tools else ""
                return self._json(200, snap)
            try:
                return self._json(200, list_dirs(self.app, (query.get("path") or [""])[0]))
            except ValueError as exc:
                return self._json(400, {"error": str(exc)})
        return self._json(404, {"error": "Not found."})

    def _serve_file(self, name: str):
        name = unquote(name)
        found = None
        if FILE_NAME_RE.match(name):
            with self.app.lock:
                dirs = list(reversed(self.app.out_dirs))
            for d in dirs:
                path = d / name
                try:
                    if path.is_file() and os.path.realpath(path.parent) == os.path.realpath(d):
                        found = path
                        break
                except OSError:
                    continue
        if found is None:
            return self._json(404, {"error": "No such file."})
        size = found.stat().st_size
        self.send_response(200)
        self.send_header("Content-Type", "image/tiff")
        self.send_header("Content-Length", str(size))
        self.send_header("Content-Disposition", f'attachment; filename="{name}"')
        self.end_headers()
        try:
            with open(found, "rb") as fh:
                while True:
                    block = fh.read(1024 * 1024)
                    if not block:
                        break
                    self.wfile.write(block)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_POST(self):
        url = urlparse(self.path)
        if not self._host_ok():
            return self._json(403, {"error": "Unexpected Host header."})
        if not self._token_ok(self.headers.get("X-Token")):
            return self._json(403, {"error": "Missing or wrong key."})
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = 0
        if length < 0 or length > 65536:
            return self._json(413, {"error": "Request too large."})
        try:
            raw = json.loads(self.rfile.read(length) or b"{}")
            if not isinstance(raw, dict):
                raise ValueError
        except ValueError:
            return self._json(400, {"error": "Request wasn't valid JSON."})

        if url.path == "/api/cancel":
            return self._json(200, {"cancelled": self.app.job.cancel()})
        if url.path == "/api/setup/cancel":
            return self._json(200, {"cancelled": self.app.setup_job.cancel()})
        if url.path == "/api/setup/start":
            if self.app.tools is not None:
                return self._json(200, {"started": False, "already": True})
            if not self.app.setup_job.start():
                return self._json(409, {"error": "Setup is already running."})
            threading.Thread(target=run_setup, args=(self.app, self.app.setup_job), daemon=True).start()
            return self._json(200, {"started": True})
        if url.path == "/api/mkdir":
            try:
                return self._json(200, {"path": make_dir(self.app, raw.get("parent"), raw.get("name"))})
            except ValueError as exc:
                return self._json(400, {"error": str(exc)})
        if url.path not in ("/api/assess", "/api/download"):
            return self._json(404, {"error": "Not found."})
        try:
            p = parse_params(raw, self.app)
            plan = assess(p, self.app)
        except NotReady as exc:
            return self._json(409, {"error": str(exc)})
        except ValueError as exc:
            return self._json(400, {"error": str(exc)})
        except RuntimeError as exc:
            return self._json(502, {"error": str(exc)})
        if url.path == "/api/assess":
            return self._json(200, plan)
        if not plan["ok"]:
            return self._json(400, {"error": " ".join(plan["errors"])})
        if not self.app.job.start():
            return self._json(409, {"error": "A build is already running. Wait for it or cancel it first."})
        threading.Thread(target=run_build, args=(self.app, self.app.job, p, plan), daemon=True).start()
        return self._json(200, {"started": True, "filename": plan["filename"]})


# --------------------------------------------------------------------------- the page

HTML = r"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>Seafloor GeoTIFF picker</title>
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
<style>
:root{
  --paper:#e9f1f3; --paper-2:#dbe7eb; --ink:#10303a; --ink-2:#425f69; --line:#b6cad1;
  --sea:#0a5c6b; --mark:#b3267a; --shoal:#8f5300; --ok:#17674a; --bad:#a1262d;
}
*{box-sizing:border-box}
html,body{height:100%;margin:0}
body{font:15px/1.45 "Avenir Next","Segoe UI",system-ui,-apple-system,Roboto,sans-serif;color:var(--ink);background:#0b2530}
#map{position:absolute;inset:0}
#panel{position:absolute;z-index:1000;display:flex;flex-direction:column;gap:16px;overflow:auto;padding:16px;
  background:var(--paper);box-shadow:0 8px 28px rgba(8,38,48,.35)}
#panel.collapsed{display:none}
@media (min-width:820px){#panel{left:12px;top:12px;bottom:12px;width:392px;border-radius:10px}.maptoggle{display:none!important}}
@media (max-width:819px){#panel{left:0;right:0;bottom:0;max-height:54vh;border-radius:14px 14px 0 0;padding-bottom:calc(18px + env(safe-area-inset-bottom))}}
h1{font-size:18px;line-height:1.25;margin:0 0 4px;font-weight:650}
h2{font-size:13px;margin:0 0 8px;font-weight:650;color:var(--ink-2)}
dialog h2{font-size:16px;color:var(--ink)}
p{margin:0}
.lede{color:var(--ink-2)}
section{padding-top:14px;border-top:1px solid var(--line)}
.grid{display:grid;grid-template-columns:1fr 1fr;gap:8px}
.f{display:flex;flex-direction:column;gap:2px;font-size:13px;color:var(--ink-2)}
input[type=number],input[type=text],select{width:100%;height:42px;padding:0 10px;font:inherit;color:var(--ink);background:#fff;border:1px solid var(--line);border-radius:6px;font-variant-numeric:tabular-nums}
button{font:inherit;min-height:44px;padding:0 16px;border-radius:6px;border:1px solid var(--sea);background:transparent;color:var(--sea);font-weight:600;cursor:pointer}
button.primary{background:var(--sea);color:#fff}
button:disabled{opacity:.45;cursor:not-allowed}
:focus-visible{outline:3px solid var(--mark);outline-offset:2px}
.row{display:flex;gap:8px;flex-wrap:wrap}
.row>*{flex:1 1 auto}
.pick{display:flex;gap:8px}
.pick input{flex:1 1 auto;min-width:0}
.pick button{flex:0 0 auto}
.hint{font-size:13px;color:var(--ink-2);margin-top:6px}
.zone{display:flex;align-items:center;gap:10px;min-height:36px}
.zone input{width:18px;height:18px;accent-color:var(--sea)}
.zone .zres{margin-left:auto;color:var(--ink-2);font-size:13px;font-variant-numeric:tabular-nums}
.box{padding:10px 12px;border-radius:6px;background:var(--paper-2);margin-top:10px}
.box.err{background:#f6dcdc;color:var(--bad)}
.box.warn{background:#f4e6cf;color:var(--shoal)}
.box.ok{background:#d7ebe2;color:var(--ok)}
.stale{opacity:.5}
table{border-collapse:collapse;width:100%;font-size:13px;font-variant-numeric:tabular-nums}
td,th{text-align:left;padding:3px 6px 3px 0;vertical-align:top}
th{color:var(--ink-2);font-weight:600}
progress{width:100%;height:12px;accent-color:var(--sea)}
pre{margin:8px 0 0;max-height:140px;overflow:auto;font-size:12px;white-space:pre-wrap;word-break:break-word;background:#fff;border:1px solid var(--line);border-radius:6px;padding:8px}
footer{font-size:12px;color:var(--ink-2)}
footer a,.box a{color:inherit}
dialog{border:0;border-radius:10px;padding:16px;width:min(520px,92vw);background:var(--paper);color:var(--ink);box-shadow:0 12px 40px rgba(8,38,48,.5)}
dialog::backdrop{background:rgba(8,38,48,.55)}
.dirs{list-style:none;margin:8px 0;padding:0;max-height:34vh;overflow:auto;border:1px solid var(--line);border-radius:6px;background:#fff}
.dirs button{width:100%;min-height:42px;text-align:left;border:0;border-bottom:1px solid var(--line);border-radius:0;color:var(--ink);font-weight:500}
.dirs .empty{padding:10px 12px;color:var(--ink-2)}
.handle{position:absolute;width:26px;height:26px;border:2px solid var(--mark);background:#fff;border-radius:3px;box-shadow:0 0 0 2px rgba(255,255,255,.85);cursor:grab;touch-action:none}
.handle.m{border-radius:50%;background:var(--mark)}
.handle.m::after{content:"";position:absolute;inset:5px;border:2px solid #fff;border-radius:50%}
.tagwrap .tag{position:absolute;transform:translate(-50%,-140%);background:var(--mark);color:#fff;padding:3px 9px;border-radius:3px;font:600 12px/1.3 "Avenir Next","Segoe UI",system-ui,sans-serif;white-space:nowrap;box-shadow:0 1px 4px rgba(0,0,0,.35)}
.maptoggle{display:none;min-height:36px;padding:0 12px;background:var(--paper);border:1px solid var(--line);color:var(--ink);border-radius:6px}
@media (max-width:819px){.maptoggle{display:block}}
@media (prefers-reduced-motion:reduce){*{transition:none!important;animation:none!important}}
</style>
</head>
<body>
<div id="map" role="application" aria-label="Map for choosing an area"></div>

<div id="panel">
  <div>
    <h1>Seafloor GeoTIFF picker</h1>
    <p class="lede">Drag the box over Australian waters. The script pulls every depth layer for it from IMAS and merges them into one GeoTIFF.</p>
  </div>

  <section id="setup" hidden aria-labelledby="h-setup">
    <h2 id="h-setup">One-time setup</h2>
    <p>GDAL does the merging and it isn't installed here. This script can download its own private copy into <strong id="setup-home"></strong>. That's a few hundred MB once, needs no admin rights, and changes nothing else on this computer.</p>
    <p class="hint">It fetches the installer from micro.mamba.pm and GDAL from conda-forge. Prefer your own install? Install GDAL 3.1 or newer and restart the script.</p>
    <div class="row" style="margin-top:10px">
      <button id="setup-go" type="button" class="primary">Download GDAL</button>
      <button id="setup-cancel" type="button" hidden>Cancel</button>
    </div>
    <progress id="setup-bar" max="100" hidden style="margin-top:10px"></progress>
    <p id="setup-text" class="hint"></p>
    <div id="setup-msg" aria-live="polite"></div>
    <details><summary>Details</summary><pre id="setup-log"></pre></details>
  </section>

  <section aria-labelledby="h-area">
    <h2 id="h-area">Area</h2>
    <div class="grid">
      <label class="f">North<input id="in-n" type="number" step="any" inputmode="decimal"></label>
      <label class="f">South<input id="in-s" type="number" step="any" inputmode="decimal"></label>
      <label class="f">West<input id="in-w" type="number" step="any" inputmode="decimal"></label>
      <label class="f">East<input id="in-e" type="number" step="any" inputmode="decimal"></label>
    </div>
    <div class="row" style="margin-top:10px">
      <button id="fit" type="button">Use what's on screen</button>
      <select id="preset" aria-label="Jump to a place">
        <option value="">Jump to&hellip;</option>
        <option value="148.6,-20.5,149.2,-20.0">Whitsunday Islands</option>
        <option value="145.9,-19.4,149.0,-17.4">Cairns to Townsville and the outer reef</option>
        <option value="150.9,-34.4,151.6,-33.5">Sydney coast</option>
        <option value="115.2,-32.4,116.0,-31.6">Perth and Rottnest</option>
        <option value="147.0,-43.4,148.4,-42.6">South-east Tasmania</option>
        <option value="112,-44,154,-10">All of Australia</option>
      </select>
    </div>
    <p class="hint">Corners resize the box. The round handle in the middle moves it.</p>
  </section>

  <section aria-labelledby="h-layers">
    <h2 id="h-layers">Depth layers to merge</h2>
    <div id="zones"></div>
  </section>

  <section aria-labelledby="h-out">
    <h2 id="h-out">Output</h2>
    <div class="grid">
      <label class="f">Detail
        <select id="res">
          <option value="auto">Automatic</option>
          <option value="finest">Full detail</option>
          <option value="10">10 m</option><option value="20">20 m</option><option value="30">30 m</option>
          <option value="50">50 m</option><option value="100">100 m</option><option value="250">250 m</option>
          <option value="500">500 m</option><option value="1000">1 km</option>
        </select>
      </label>
      <label class="f">Coordinates<select id="crs"></select></label>
    </div>
    <div class="f" style="margin-top:10px">
      <label for="outdir">Save to</label>
      <div class="pick"><input id="outdir" type="text" autocomplete="off" spellcheck="false"><button id="browse" type="button">Browse</button></div>
    </div>
    <details style="margin-top:10px">
      <summary>More options</summary>
      <label class="f" style="margin-top:8px">Resampling
        <select id="resamp">
          <option value="auto">Automatic</option><option value="near">Nearest</option><option value="average">Average</option>
          <option value="bilinear">Bilinear</option><option value="cubic">Cubic</option>
        </select>
      </label>
    </details>
    <p class="hint">Automatic picks the finest detail that stays under <span id="cap"></span> GB. WGS84 lat/long is what SeaBathy reads. The folder is on the computer running this script.</p>
  </section>

  <section aria-labelledby="h-go">
    <h2 id="h-go">Build</h2>
    <div class="row">
      <button id="check" type="button">Check size</button>
      <button id="build" type="button" class="primary" disabled>Build GeoTIFF</button>
    </div>
    <div id="assess" aria-live="polite"></div>
    <div id="job" hidden>
      <div class="box" style="margin-top:12px">
        <div id="jobtext"></div>
        <progress id="bar" max="100" value="0" style="margin-top:8px"></progress>
        <div class="row" style="margin-top:8px"><button id="cancel" type="button">Cancel</button></div>
        <details><summary>Details</summary><pre id="joblog"></pre></details>
      </div>
    </div>
    <div id="result" aria-live="polite"></div>
  </section>

  <footer>
    <p id="cite"></p>
    <p style="margin-top:6px">Not for navigation. GDAL: <span id="gdalver"></span></p>
  </footer>
</div>

<dialog id="picker" aria-labelledby="pk-title">
  <h2 id="pk-title">Choose where to save</h2>
  <p id="pk-path" class="hint" style="word-break:break-all"></p>
  <div class="row" id="pk-roots" style="margin-top:8px"></div>
  <div class="row" style="margin-top:8px"><button id="pk-up" type="button">Up one level</button></div>
  <ul id="pk-list" class="dirs"></ul>
  <div class="pick"><input id="pk-new" type="text" placeholder="New folder name" maxlength="100" aria-label="New folder name"><button id="pk-mk" type="button">Create</button></div>
  <p id="pk-msg" class="hint" role="status"></p>
  <div class="row" style="margin-top:10px"><button id="pk-cancel" type="button">Cancel</button><button id="pk-use" type="button" class="primary">Use this folder</button></div>
</dialog>

<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<script>
(function () {
  "use strict";
  const CFG = __CONFIG__;
  const TOKEN = "__TOKEN__";
  const $ = (id) => document.getElementById(id);
  const MIN_SPAN = 0.0005;
  const panel = $("panel");
  let toolsReady = !CFG.setup.needed;
  let busy = false;
  let assessedKey = null;

  function el(tag, cls, text) {
    const n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text !== undefined) n.textContent = text;
    return n;
  }
  const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
  const sz = (b) => b < 1073741824 ? (b / 1048576 < 10 ? (b / 1048576).toFixed(1) : Math.round(b / 1048576)) + " MB" : (b / 1073741824).toFixed(1) + " GB";
  const note = (cls, text) => el("div", "box " + cls, text);
  function reveal(node) { if (node && node.scrollIntoView) node.scrollIntoView({ block: "nearest" }); }

  async function api(path, body) {
    const opt = { headers: { "X-Token": TOKEN } };
    if (body !== undefined) {
      opt.method = "POST";
      opt.headers["Content-Type"] = "application/json";
      opt.body = JSON.stringify(body);
    }
    let res, data = {};
    try { res = await fetch(path, opt); } catch (e) { return { error: "Can't reach the script. Is it still running?" }; }
    try { data = await res.json(); } catch (e) { data = {}; }
    if (!res.ok && !data.error) data.error = "The script answered with HTTP " + res.status + ".";
    return data;
  }
  function refreshButtons() {
    $("check").disabled = busy || !toolsReady;
    $("build").disabled = busy || !toolsReady || !assessedKey;
  }

  // ---------- static bits
  $("cap").textContent = CFG.maxGb;
  $("outdir").value = CFG.outDir;
  $("cite").textContent = "Data: " + CFG.citation;
  $("gdalver").textContent = CFG.gdalVersion || "not installed yet";
  $("setup-home").textContent = CFG.setup.home;
  CFG.crs.forEach((c) => { const o = el("option", "", c.label); o.value = c.key; $("crs").appendChild(o); });
  CFG.zones.forEach((z) => {
    const lab = el("label", "zone");
    const cb = el("input"); cb.type = "checkbox"; cb.value = z.key; cb.checked = true;
    cb.addEventListener("change", markDirty);
    lab.appendChild(cb);
    lab.appendChild(el("span", "", z.label));
    const r = el("span", "zres"); r.dataset.res = z.key; lab.appendChild(r);
    $("zones").appendChild(lab);
  });

  // ---------- map
  const map = L.map("map", { zoomControl: false, minZoom: 3, maxBounds: [[-68, 60], [2, 180]], maxBoundsViscosity: 1.0 });
  const street = L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png",
    { maxZoom: 19, attribution: "&copy; OpenStreetMap contributors" });
  const sat = L.tileLayer("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
    { maxZoom: 19, maxNativeZoom: 18, attribution: "Tiles &copy; Esri &mdash; Source: Esri, Maxar, Earthstar Geographics, and the GIS User Community" });
  map.fitBounds([[-44, 112], [-10, 154]]);
  sat.addTo(map);
  L.control.zoom({ position: "topright" }).addTo(map);
  L.control.layers({ "Satellite": sat, "Street map": street }, null, { position: "topright" }).addTo(map);
  const Toggle = L.Control.extend({
    options: { position: "topright" },
    onAdd: function () {
      const b = L.DomUtil.create("button", "maptoggle");
      b.type = "button"; b.textContent = "Panel";
      L.DomEvent.disableClickPropagation(b);
      b.addEventListener("click", () => panel.classList.toggle("collapsed"));
      return b;
    }
  });
  new Toggle().addTo(map);

  // ---------- the box
  let box = { w: 131, s: -28, e: 137, n: -23 };
  const rect = L.rectangle([[box.s, box.w], [box.n, box.e]],
    { color: "#b3267a", weight: 2, fillColor: "#b3267a", fillOpacity: 0.1 }).addTo(map);
  const mk = (cls) => L.marker([0, 0], { draggable: true, keyboard: false,
    icon: L.divIcon({ className: "handle " + cls, iconSize: [26, 26], iconAnchor: [13, 13] }) }).addTo(map);
  const hSW = mk("c"), hSE = mk("c"), hNE = mk("c"), hNW = mk("c"), hMove = mk("m");
  const tag = L.marker([0, 0], { interactive: false, keyboard: false,
    icon: L.divIcon({ className: "tagwrap", html: '<div class="tag"></div>', iconSize: [0, 0] }) }).addTo(map);

  let lastEstimate = "";
  function dims() {
    const mid = (box.s + box.n) / 2;
    return [(box.e - box.w) * 111.32 * Math.cos(mid * Math.PI / 180), (box.n - box.s) * 111.32];
  }
  function refreshTag() {
    const d = dims();
    const fmt = (v) => (v >= 100 ? v.toFixed(0) : v.toFixed(1));
    const node = tag.getElement();
    if (node && node.firstChild) node.firstChild.textContent = fmt(d[0]) + " \u00d7 " + fmt(d[1]) + " km" + lastEstimate;
  }
  function syncInputs(force) {
    [["n", box.n], ["s", box.s], ["w", box.w], ["e", box.e]].forEach(([k, v]) => {
      const input = $("in-" + k);
      if (force || document.activeElement !== input) input.value = v.toFixed(4);
    });
  }
  function place(skip) {
    rect.setBounds([[box.s, box.w], [box.n, box.e]]);
    if (skip !== hSW) hSW.setLatLng([box.s, box.w]);
    if (skip !== hSE) hSE.setLatLng([box.s, box.e]);
    if (skip !== hNE) hNE.setLatLng([box.n, box.e]);
    if (skip !== hNW) hNW.setLatLng([box.n, box.w]);
    if (skip !== hMove) hMove.setLatLng([(box.s + box.n) / 2, (box.w + box.e) / 2]);
    tag.setLatLng([box.n, (box.w + box.e) / 2]);
    refreshTag();
    syncInputs(false);
  }
  function wireCorner(h, fx, fy) {
    h.on("drag", () => {
      const p = h.getLatLng();
      if (fx === "w") box.w = clamp(Math.min(p.lng, box.e - MIN_SPAN), -180, 180); else box.e = clamp(Math.max(p.lng, box.w + MIN_SPAN), -180, 180);
      if (fy === "s") box.s = clamp(Math.min(p.lat, box.n - MIN_SPAN), -90, 90); else box.n = clamp(Math.max(p.lat, box.s + MIN_SPAN), -90, 90);
      place(h); markDirty();
    });
    h.on("dragend", () => place());
  }
  wireCorner(hSW, "w", "s"); wireCorner(hSE, "e", "s"); wireCorner(hNE, "e", "n"); wireCorner(hNW, "w", "n");
  let half = null;
  hMove.on("dragstart", () => { half = { x: (box.e - box.w) / 2, y: (box.n - box.s) / 2 }; });
  hMove.on("drag", () => {
    const c = hMove.getLatLng();
    const cx = clamp(c.lng, -180 + half.x, 180 - half.x), cy = clamp(c.lat, -90 + half.y, 90 - half.y);
    box = { w: cx - half.x, e: cx + half.x, s: cy - half.y, n: cy + half.y };
    place(hMove); markDirty();
  });
  hMove.on("dragend", () => place());

  function visibleBounds(frac) {
    const m = $("map").getBoundingClientRect();
    const open = !panel.classList.contains("collapsed");
    const p = open ? panel.getBoundingClientRect() : null;
    let left = 0, top = 0, right = m.width, bottom = m.height;
    if (p) { if (window.innerWidth >= 820) left = p.right - m.left + 8; else bottom = p.top - m.top - 8; }
    const dx = (right - left) * frac, dy = (bottom - top) * frac;
    const nw = map.containerPointToLatLng([left + dx, top + dy]);
    const se = map.containerPointToLatLng([right - dx, bottom - dy]);
    return { w: clamp(nw.lng, -180, 180), n: clamp(nw.lat, -90, 90), e: clamp(se.lng, -180, 180), s: clamp(se.lat, -90, 90) };
  }
  $("fit").addEventListener("click", () => { box = visibleBounds(0.12); place(); markDirty(); });

  ["n", "s", "w", "e"].forEach((k) => $("in-" + k).addEventListener("change", () => {
    const v = {}; let good = true;
    ["n", "s", "w", "e"].forEach((q) => { const raw = $("in-" + q).value; v[q] = Number(raw); if (raw === "" || !Number.isFinite(v[q])) good = false; });
    if (!good) { syncInputs(true); return; }
    if (!(v.w < v.e && v.s < v.n)) { alert("West must be less than east, and south less than north."); syncInputs(true); return; }
    box = v; place(); markDirty();
    map.fitBounds(rect.getBounds().pad(0.35));
  }));
  $("preset").addEventListener("change", () => {
    const val = $("preset").value; if (!val) return;
    const [w, s, e, n] = val.split(",").map(Number);
    box = { w, s, e, n }; place(); markDirty();
    map.fitBounds(rect.getBounds().pad(0.25));
    $("preset").value = "";
  });
  ["res", "crs", "resamp", "outdir"].forEach((id) => $(id).addEventListener("change", markDirty));

  // ---------- save-to folder chooser
  let pk = null;
  async function loadDir(path) {
    const d = await api("/api/dirs?path=" + encodeURIComponent(path || ""));
    if (d.error) { $("pk-msg").textContent = d.error; return; }
    pk = d;
    $("pk-path").textContent = d.path;
    $("pk-msg").textContent = d.writable ? (d.truncated ? "Only the first 500 folders are shown." : "") : "This folder is read-only. Pick another one.";
    $("pk-up").hidden = !d.parent;
    $("pk-up").disabled = !d.parent;
    $("pk-roots").textContent = "";
    (d.roots || []).forEach((r) => { const b = el("button", "", r); b.type = "button"; b.addEventListener("click", () => loadDir(r)); $("pk-roots").appendChild(b); });
    const list = $("pk-list"); list.textContent = "";
    if (!d.dirs.length) list.appendChild(el("li", "empty", "No folders here. Create one below, or use this folder."));
    d.dirs.forEach((f) => {
      const li = el("li"); const b = el("button", "", "\ud83d\udcc1  " + f.name); b.type = "button";
      b.addEventListener("click", () => loadDir(f.path)); li.appendChild(b); list.appendChild(li);
    });
    $("pk-use").disabled = !d.writable;
  }
  $("browse").addEventListener("click", async () => { $("pk-new").value = ""; await loadDir($("outdir").value); $("picker").showModal(); });
  $("pk-up").addEventListener("click", () => { if (pk && pk.parent) loadDir(pk.parent); });
  $("pk-cancel").addEventListener("click", () => $("picker").close());
  $("pk-use").addEventListener("click", () => { if (pk) { $("outdir").value = pk.path; markDirty(); } $("picker").close(); });
  $("pk-mk").addEventListener("click", async () => {
    const name = $("pk-new").value.trim(); if (!name || !pk) return;
    const r = await api("/api/mkdir", { parent: pk.path, name: name });
    if (r.error) { $("pk-msg").textContent = r.error; return; }
    $("pk-new").value = ""; loadDir(r.path);
  });

  // ---------- assess
  function payload() {
    return {
      bbox: [box.w, box.s, box.e, box.n],
      zones: Array.from(document.querySelectorAll("#zones input:checked")).map((c) => c.value),
      crs: $("crs").value, resolution: $("res").value, resampling: $("resamp").value, out_dir: $("outdir").value.trim()
    };
  }
  function markDirty() {
    assessedKey = null;
    $("assess").classList.add("stale");
    lastEstimate = ""; refreshTag(); refreshButtons();
  }
  function renderAssess(a, body) {
    const out = $("assess");
    out.textContent = ""; out.classList.remove("stale");
    if (a.error) { out.appendChild(note("err", a.error)); reveal(out.lastElementChild); return; }
    (a.zones || []).forEach((z) => {
      const s = document.querySelector('[data-res="' + z.key + '"]');
      if (s) s.textContent = z.covered ? (z.native_res_m + " m") : "no coverage";
    });
    if (a.res_m) {
      const t = el("table");
      const add = (k, v) => { const tr = el("tr"); tr.appendChild(el("th", "", k)); tr.appendChild(el("td", "", v)); t.appendChild(tr); };
      add("Box", a.box_km[0] + " \u00d7 " + a.box_km[1] + " km");
      add("Detail", a.res_m + " m. " + a.res_note);
      add("Output", a.output_px[0].toLocaleString() + " \u00d7 " + a.output_px[1].toLocaleString() + " pixels, " + a.crs_label);
      add("Size", "up to " + sz(a.est_bytes) + " uncompressed. The finished file is usually 2\u20134\u00d7 smaller.");
      add("Format", a.format);
      add("Save in", a.out_dir);
      const b = el("div", "box"); b.appendChild(t); out.appendChild(b);
      lastEstimate = " \u00b7 \u2264 " + sz(a.est_bytes); refreshTag();
    }
    (a.errors || []).forEach((m) => out.appendChild(note("err", m)));
    (a.warnings || []).forEach((m) => out.appendChild(note("warn", m)));
    if (a.ok) assessedKey = JSON.stringify(body);
    refreshButtons();
    reveal(out.lastElementChild);
  }
  $("check").addEventListener("click", async () => {
    const body = payload();
    $("check").disabled = true; $("check").textContent = "Checking\u2026";
    const a = await api("/api/assess", body);
    $("check").textContent = "Check size";
    renderAssess(a, body);
  });

  // ---------- build
  let timer = null, lastState = "idle";
  function showJob(s) {
    const jobBox = $("job"), res = $("result");
    const changed = s.state !== lastState; lastState = s.state;
    if (s.state === "idle") { jobBox.hidden = true; return; }
    if (s.state === "running") {
      jobBox.hidden = false; res.textContent = "";
      $("jobtext").textContent = s.stage + " \u2014 " + Math.round(s.percent) + "%" + (s.passes > 1 ? " (pass " + s.passes + ", the GeoTIFF is being finished)" : "") + ", " + Math.round(s.elapsed) + " s";
      $("bar").value = s.percent;
      $("joblog").textContent = (s.log || []).join("\n");
      $("cancel").disabled = false;
      busy = true; refreshButtons();
      if (changed) reveal(jobBox);
      return;
    }
    jobBox.hidden = true; busy = false;
    res.textContent = "";
    if (s.state === "error") { res.appendChild(note("err", s.error || "The build failed.")); const d = el("details"); d.appendChild(el("summary", "", "Details")); d.appendChild(el("pre", "", (s.log || []).join("\n"))); res.appendChild(d); }
    else if (s.state === "cancelled") { res.appendChild(note("warn", "Cancelled. Nothing was saved.")); }
    else if (s.state === "done" && s.result) {
      const r = s.result, b = el("div", "box ok");
      b.appendChild(el("p", "", "Saved " + r.name + " (" + sz(r.bytes) + ")"));
      const t = el("table"); t.style.marginTop = "6px";
      const add = (k, v) => { const tr = el("tr"); tr.appendChild(el("th", "", k)); tr.appendChild(el("td", "", v)); t.appendChild(tr); };
      add("Where", r.path);
      if (r.size_px) add("Size", r.size_px[0].toLocaleString() + " \u00d7 " + r.size_px[1].toLocaleString() + " px at " + r.res_m + " m");
      if (r.min !== null && r.max !== null) add("Values", r.min.toFixed(1) + " to " + r.max.toFixed(1));
      if (r.valid_percent !== null) add("Has data", r.valid_percent.toFixed(0) + "% of the box");
      b.appendChild(t);
      if (r.valid_percent !== null && r.valid_percent < 1) b.appendChild(el("p", "", "Almost all of this box is empty. It may be land or outside Australian waters."));
      const dl = el("a", "", "Save a copy to this device");
      dl.href = "/files/" + encodeURIComponent(r.name) + "?t=" + encodeURIComponent(TOKEN); dl.setAttribute("download", r.name);
      dl.style.display = "inline-block"; dl.style.marginTop = "8px";
      b.appendChild(dl);
      b.appendChild(el("p", "", "In SeaBathy, import it by browsing to the folder under This device, not Recent."));
      res.appendChild(b);
    }
    refreshButtons();
    if (changed) reveal(res.lastElementChild);
  }
  async function poll() {
    const s = await api("/api/status");
    if (s.error) { $("result").textContent = ""; $("result").appendChild(note("err", s.error)); stopPolling(); busy = false; refreshButtons(); return; }
    showJob(s);
    if (s.state !== "running") stopPolling();
  }
  function stopPolling() { if (timer) { clearInterval(timer); timer = null; } }
  function startPolling() { stopPolling(); poll(); timer = setInterval(poll, 1500); }

  $("build").addEventListener("click", async () => {
    const body = payload();
    if (!assessedKey || assessedKey !== JSON.stringify(body)) { markDirty(); return; }
    busy = true; refreshButtons();
    const r = await api("/api/download", body);
    if (r.error) { busy = false; refreshButtons(); $("result").textContent = ""; $("result").appendChild(note("err", r.error)); return; }
    startPolling();
  });
  $("cancel").addEventListener("click", async () => { $("cancel").disabled = true; await api("/api/cancel", {}); });

  // ---------- one-time GDAL setup
  let stimer = null;
  function renderSetup(s) {
    const run = s.state === "running";
    $("setup-go").disabled = run;
    $("setup-cancel").hidden = !run; $("setup-cancel").disabled = false;
    const bar = $("setup-bar"); bar.hidden = !run;
    if (s.percent < 0) bar.removeAttribute("value"); else bar.value = s.percent;
    $("setup-text").textContent = run ? s.stage + (s.percent >= 0 ? " \u2014 " + Math.round(s.percent) + "%" : "") + ", " + Math.round(s.elapsed) + " s" : "";
    $("setup-log").textContent = (s.log || []).join("\n");
    const msg = $("setup-msg"); msg.textContent = "";
    if (s.state === "error") msg.appendChild(note("err", s.error || "Setup failed."));
    if (s.state === "cancelled") msg.appendChild(note("warn", "Setup cancelled."));
    if (s.state === "done" || s.needed === false) {
      toolsReady = true; $("setup").hidden = true; busy = false;
      $("gdalver").textContent = s.version || (s.result && s.result.version) || "ready";
      refreshButtons();
    } else {
      busy = run; refreshButtons();
    }
  }
  async function pollSetup() {
    const s = await api("/api/setup/status");
    if (s.error) { $("setup-msg").textContent = ""; $("setup-msg").appendChild(note("err", s.error)); stopSetupPolling(); return; }
    renderSetup(s);
    if (s.state !== "running") stopSetupPolling();
  }
  function stopSetupPolling() { if (stimer) { clearInterval(stimer); stimer = null; } }
  function startSetupPolling() { stopSetupPolling(); pollSetup(); stimer = setInterval(pollSetup, 1500); }
  $("setup-go").addEventListener("click", async () => {
    $("setup-go").disabled = true;
    const r = await api("/api/setup/start", {});
    if (r.error) { $("setup-msg").textContent = ""; $("setup-msg").appendChild(note("err", r.error)); $("setup-go").disabled = false; return; }
    startSetupPolling();
  });
  $("setup-cancel").addEventListener("click", async () => { $("setup-cancel").disabled = true; await api("/api/setup/cancel", {}); });
  if (CFG.setup.needed) {
    $("setup").hidden = false;
    if (!CFG.setup.supported) {
      $("setup-go").hidden = true;
      $("setup-msg").appendChild(note("warn", "Automatic setup isn't available for this computer. Install GDAL 3.1 or newer yourself (see the top of the script), then restart it."));
    }
    api("/api/setup/status").then((s) => { if (s && s.state === "running") startSetupPolling(); });
  }

  place(); syncInputs(true); refreshButtons();
  api("/api/status").then((s) => { if (s && s.state === "running") startPolling(); else if (s && s.state && s.state !== "idle") showJob(s); });
})();
</script>
</body>
</html>
"""


# --------------------------------------------------------------------------- modes

INSTALL_HELP = (
    "GDAL command-line tools (gdalinfo, gdalwarp) were not found.\n"
    "  Easiest: run  python3 bathy_picker.py --setup  and it downloads a private copy for you.\n"
    "  Or install it yourself:\n"
    "    Ubuntu/Debian:  sudo apt install gdal-bin\n"
    "    macOS:          brew install gdal\n"
    "    Windows:        install QGIS or OSGeo4W, then run this from the OSGeo4W Shell\n"
    "    conda:          conda install -c conda-forge gdal\n"
    "  Or point at them with --gdal-bin /path/to/folder"
)


def run_check(tools: Tools) -> int:
    print(f"GDAL tools : {tools.gdalwarp}{'  (private copy downloaded by this script)' if tools.managed else ''}")
    print(f"Version    : {tools.version or 'unknown'}")
    print(f"COG driver : {'yes' if tools.has_cog else 'NO - output will be a tiled GeoTIFF without overviews (GDAL 3.1+ has COG)'}")
    zone = ZONES[-1]  # the smallest file, header read only
    print(f"IMAS server: reading the header of {zone['file']} ...")
    try:
        info = read_zone_info(tools, zone)
    except RuntimeError as exc:
        print("FAILED     : " + str(exc))
        return 1
    print(f"OK         : {info['size'][0]} x {info['size'][1]} px, {info['px_m']:.0f} m pixels, {info['crs'] or 'CRS unknown'}")
    return 0


def run_setup_cli(app: App, assume_yes: bool) -> int:
    plat = mamba_platform()
    print("GDAL (the tool that merges the depth layers) wasn't found.")
    if not plat:
        print(INSTALL_HELP)
        return 2
    print(f"This script can download its own private copy into:\n  {app.home}")
    print("It is a few hundred MB, needs about 1.5 GB free while installing, and changes nothing else on this computer.")
    print("Sources: the micromamba installer from micro.mamba.pm, then GDAL from conda-forge.")
    if not assume_yes and input("Download it now? [y/N] ").strip().lower() not in ("y", "yes"):
        print(INSTALL_HELP)
        return 1
    job = app.setup_job
    job.start()
    worker = threading.Thread(target=run_setup, args=(app, job), daemon=True)
    worker.start()
    try:
        while worker.is_alive():
            s = job.snapshot()
            pct = "" if s["percent"] < 0 else f" {s['percent']:.0f}%"
            print(f"\r{s['stage']}{pct} ({int(s['elapsed'])} s)        ", end="", flush=True)
            time.sleep(1)
    except KeyboardInterrupt:
        job.cancel()
        worker.join(timeout=15)
        print("\nCancelled.")
        return 1
    s = job.snapshot()
    print()
    if s["state"] == "done":
        print("GDAL ready: " + s["result"]["version"])
        return 0
    print("Setup failed: " + (s["error"] or s["state"]))
    return 1


def run_cli(args, app: App) -> int:
    zones = "all" if args.zones == "all" else [z.strip() for z in args.zones.split(",") if z.strip()]
    try:
        p = parse_params({"bbox": args.bbox, "zones": zones, "crs": args.crs, "resolution": args.res,
                          "resampling": args.resampling, "out_dir": None}, app)
        plan = assess(p, app)
    except (ValueError, RuntimeError, NotReady) as exc:
        print("Error: " + str(exc))
        return 2
    print(f"Box        : {plan.get('box_km', ['?', '?'])[0]} x {plan.get('box_km', ['?', '?'])[1]} km")
    for z in plan["zones"]:
        print(f"  {z['label']:<24} {z['native_res_m']:>8} m  {'' if z['covered'] else '(no coverage)'}")
    if "res_m" in plan:
        print(f"Detail     : {plan['res_m']:g} m. {plan['res_note']}")
        print(f"Output     : {plan['output_px'][0]} x {plan['output_px'][1]} px, up to {fmt_bytes(plan['est_bytes'])} uncompressed ({plan['format']})")
        print(f"Save in    : {plan['out_dir']}")
    for msg in plan["warnings"]:
        print("Note       : " + msg)
    for msg in plan["errors"]:
        print("Error      : " + msg)
    if not plan["ok"]:
        return 2
    if not args.yes and input("Build it? [y/N] ").strip().lower() not in ("y", "yes"):
        print("Cancelled.")
        return 1
    job = app.job
    job.start()
    worker = threading.Thread(target=run_build, args=(app, job, p, plan), daemon=True)
    worker.start()
    try:
        while worker.is_alive():
            snap = job.snapshot()
            print(f"\r{snap['stage']}: {snap['percent']:.0f}%  ", end="", flush=True)
            time.sleep(1)
    except KeyboardInterrupt:
        job.cancel()
        worker.join(timeout=10)
        print("\nCancelled.")
        return 1
    snap = job.snapshot()
    print()
    if snap["state"] == "done":
        r = snap["result"]
        print(f"Saved {r['path']} ({fmt_bytes(r['bytes'])})")
        if r["min"] is not None:
            print(f"Values {r['min']:.1f} to {r['max']:.1f}" + (f", data in {r['valid_percent']:.0f}% of the box" if r["valid_percent"] is not None else ""))
        return 0
    print("Failed: " + (snap["error"] or snap["state"]))
    return 1


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Pick an area of Australia and build one seafloor-depth GeoTIFF from the IMAS bathymetry composite.")
    ap.add_argument("--host", default="127.0.0.1", help="address to listen on (use 0.0.0.0 to reach it from a phone)")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--out", default=None, help="default folder for finished GeoTIFFs (you can change it in the page). Default: ~/bathy_exports")
    ap.add_argument("--max-gb", type=float, default=DEFAULT_MAX_GB, help="refuse outputs bigger than this (uncompressed estimate)")
    ap.add_argument("--gdal-bin", help="folder containing gdalinfo and gdalwarp")
    ap.add_argument("--home", help="where the private GDAL copy is kept (default: %s)" % default_home())
    ap.add_argument("--no-browser", action="store_true")
    ap.add_argument("--setup", action="store_true", help="download GDAL if it's missing, then exit")
    ap.add_argument("--check", action="store_true", help="test GDAL and the IMAS server, then exit")
    ap.add_argument("--bbox", nargs=4, type=float, metavar=("WEST", "SOUTH", "EAST", "NORTH"), help="skip the map and build this box")
    ap.add_argument("--zones", default="all", help="comma-separated zone keys (default all): " + ",".join(z["key"] for z in ZONES))
    ap.add_argument("--crs", choices=list(CRS_CODES), default="wgs84")
    ap.add_argument("--res", default="auto", help="auto, finest, or metres")
    ap.add_argument("--resampling", choices=RESAMPLING, default="auto")
    ap.add_argument("--yes", action="store_true", help="don't ask for confirmation (with --bbox or --setup)")
    args = ap.parse_args(argv)

    home = Path(args.home).expanduser() if args.home else default_home()
    tools = find_tools(args.gdal_bin, home)
    out = args.out or str(Path.home() / "bathy_exports")
    token = secrets.token_urlsafe(18)
    app = App(tools, home, out, args.max_gb, args.host, token)

    if args.setup:
        if tools is not None:
            print(f"GDAL is already available: {tools.version or tools.gdalwarp}")
            return 0
        return run_setup_cli(app, args.yes)
    if args.check:
        if tools is None:
            print(INSTALL_HELP)
            return 2
        return run_check(tools)
    if args.bbox:
        if tools is None:
            rc = run_setup_cli(app, args.yes)
            if rc != 0:
                return rc
        return run_cli(args, app)

    try:
        httpd = ThreadingHTTPServer((args.host, args.port), Handler)
    except OSError as exc:
        print(f"Could not listen on {args.host}:{args.port} ({exc}). Try --port 8766.")
        return 2
    httpd.app = app  # type: ignore[attr-defined]
    shown_host = "127.0.0.1" if args.host in ("0.0.0.0", "") else args.host
    url = f"http://{shown_host}:{args.port}/" + ("" if app.strict_host else f"?t={token}")
    print(f"Seafloor picker running. Open: {url}")
    if not app.strict_host:
        print("Listening on the network. Replace the address with this machine's IP or Tailscale name, e.g. "
              f"http://<this-machine>:{args.port}/?t={token}")
        print("Anyone with that link can start downloads on this machine, so only share it with yourself.")
    if tools is None:
        print("GDAL wasn't found. The page will offer to download a private copy (a few hundred MB, once).")
    else:
        print(f"GDAL: {tools.version or tools.gdalwarp}")
    print(f"Saving to: {app.out_dir} (changeable in the page)   Size limit: {args.max_gb:g} GB")
    print("Press Ctrl+C to stop.")
    if app.strict_host and not args.no_browser:
        try:
            webbrowser.open(url)
        except webbrowser.Error:
            pass
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping.")
    finally:
        app.job.cancel()
        app.setup_job.cancel()
        httpd.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
