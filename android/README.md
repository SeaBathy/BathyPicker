# BathyPicker for Android

The same map page as the desktop BathyPicker, as a phone app. Draw a box, press **Check size**,
then **Build GeoTIFF**. The file is saved in **Download/BathyPicker** and can be opened or shared
straight from the app (for example into SeaBathy).

It needs no GDAL. A small engine in Java does what `gdalwarp` does for BathyPicker:

| Step | How |
|---|---|
| Read only the box | HTTP byte ranges from the IMAS Cloud-Optimized GeoTIFFs (BigTIFF, Deflate or LZW tiles) |
| Pick the right detail | The zone overview closest to the output detail, by gdalwarp's `-ovr AUTO` rule |
| Reproject | GDA2020 Australian Albers (EPSG:9473) ↔ WGS84 with Snyder's ellipsoidal formulas |
| Merge | Coarse zones first, the finest last, so it wins where zones overlap (same order as the desktop script) |
| Resample | `near` at full detail, `average` when coarser (also `bilinear` and `cubic` under More options) |
| Write | Tiled float32 GeoTIFF, Deflate + floating-point predictor, NoData 3.4e+38, averaged overviews |

Output was checked pixel by pixel against `gdalwarp -et 0` (exact transforms): identical for
nearest and Albers output, and within float rounding (no difference above 0.01 m) for average.
gdalwarp's default approximate transformer differs from both by an occasional neighbouring pixel.

The size limit is **3 GB** (uncompressed estimate). Builds run in a foreground service with a
progress notification, so they carry on with the screen off. Cancel removes the unfinished file.

Android 7.0 (API 24) or newer.

## Build

Uses only the Android SDK command-line tools, no Gradle:

```bash
bash build.sh
```

Needs a JDK (17+), Android SDK build-tools 36.1.0 and platform android-36, and Python 3.
The first build makes a signing key in `keystore/`. **Back it up and never commit it.** Every
update must be signed with the same key, or phones won't install it over the old version.

`tools/make_page.py` makes `assets/index.html` from the page inside `../bathy_picker.py`, so the
two stay the same. It runs on every build and stops if the desktop page changed in a way it
doesn't expect.

## Check the engine against GDAL (desktop)

```bash
javac -encoding UTF-8 -d build/test $(find src/com/seabathy/bathypicker/core -name '*.java') test/DesktopBuild.java
java -cp build/test DesktopBuild 148.95 -20.30 149.05 -20.22 finest wgs84 auto all test.tif
```

Arguments: west south east north, detail (`auto`, `finest` or metres), `wgs84` or `albers`,
resampling, zones (`all` or a comma list), output file.

## Licences

Leaflet 1.9.4 is bundled in `assets/leaflet` (BSD 2-Clause, `assets/leaflet/LICENSE`).
Map tiles are Esri World Imagery and OpenStreetMap, credited on the map. Depth data is IMAS,
CC BY 4.0, and the page shows the citation. BathyPicker's code is MIT.
