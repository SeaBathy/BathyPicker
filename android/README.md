# BathyPicker for Android

The same map page as the desktop BathyPicker, as a phone app. Draw a box, press **Check size**,
then **Build GeoTIFF**. The file is saved in **Download/BathyPicker** and can be opened or shared
straight from the app (for example into SeaBathy).

It needs no GDAL. A small engine in Java does what `gdalwarp` and `gdal raster` do for the
desktop BathyPicker, including the original surveys on top of IMAS and their blended edges:

| Step | How |
|---|---|
| Read only the box | HTTP byte ranges from the IMAS and AusSeabed GeoTIFFs: tiled or in strips, classic TIFF or BigTIFF, Deflate, LZW or uncompressed, header at the start (COG) or the end |
| Pick the right detail | The overview closest to the output detail, by gdalwarp's `-ovr AUTO` rule |
| Reproject | GDA2020 Australian Albers with Snyder's formulas; the surveys' UTM / MGA zones and transverse Mercator by Krüger's series (Karney 2011); World Mercator; lat/long. The datums are treated as one, as PROJ does for these pairs |
| Merge | IMAS coarse zones first, the finest last; then the ticked surveys, bottom of the list first (the same order as the desktop) |
| Resample | `near` at full detail, `average` when coarser, `bilinear` when surveys are finer than IMAS (also `bilinear` and `cubic` under More options), with gdalwarp's wider kernel when shrinking and its rule that a pixel stays empty where the source pixel under its centre is |
| Blend survey edges | GDAL's proximity algorithm for the distance to each survey's edge, then the same smooth ramp as the desktop, using working files in the app's cache |
| Write | Tiled float32 GeoTIFF, Deflate + floating-point predictor, NoData 3.4e+38, averaged overviews, and the `_sources.txt` credits |

Output was checked pixel by pixel against the desktop BathyPicker run with `gdalwarp -et 0`
(exact transforms, as the engine computes), in 26 test boxes across every state with blended and
hard edges, plus 9 surveys stacked at Darwin (both orders), a 6 km box, output coarser than a
survey, Albers output, surveys only and cubic resampling. The same pixels are filled in every
case, and depths agree to within about a millimetre, mostly within 0.1 mm (float rounding). The
exceptions are a few single pixels whose centre falls within 0.00001 of a source pixel's edge,
where the last digit of the coordinate maths decides, and boxes crossed by the one survey the
phone can't read (Zeehan and Franklin, Zstandard compression), which it lists as needing the
computer version. The desktop's default approximate transformer differs from both by more.

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
resampling, zones (`all` or a comma list), output file. It also writes the `_sources.txt`.

With the original surveys, as the desktop's `--catalogue`, `--no-blend`, `--no-base` and
`--surveys` do:

```bash
java -Dcatalogue=../catalogue/surveys.json -cp build/test DesktopBuild 146.18472 -16.71607 146.19035 -16.71064 1 wgs84 auto all cairns.tif
```

`-Dblend=false` keeps hard edges, `-Dbase=false` leaves IMAS out, `-Dsurveys=none` uses IMAS
only, and `-Dorder=id,id` / `-Dprefs=id:1,id:0` set the list order and ticks.

## Licences

Leaflet 1.9.4 is bundled in `assets/leaflet` (BSD 2-Clause, `assets/leaflet/LICENSE`).
Map tiles are Sentinel-2 cloudless (EOX) and OpenStreetMap, credited on the map; the layer menu's
"Your own map link" adds any other tile map under that provider's terms. Depth data is IMAS and
AusSeabed, CC BY 4.0: the page shows the citation, and each GeoTIFF's `_sources.txt` credits the
surveys in it. The survey list (`../catalogue/surveys.json`) is built into the app, and it
fetches a newer one from this repository when online. BathyPicker's code is MIT.
