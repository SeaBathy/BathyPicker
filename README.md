<h1 align="center">BathyPicker</h1>

<p align="center"><b>High-detail seabed maps of anywhere in Australian waters.</b><br>
Draw a box, get one depth GeoTIFF, up to 10 m detail. Free and open source.</p>

<p align="center">
  <img alt="Version 1.1" src="https://img.shields.io/badge/version-1.1-0f766e">
  <img alt="Windows, macOS, Linux or Android" src="https://img.shields.io/badge/runs%20on-Windows%20%7C%20macOS%20%7C%20Linux%20%7C%20Android-0078d4">
  <img alt="Python 3.8+" src="https://img.shields.io/badge/Python-3.8%2B-3776ab?logo=python&logoColor=white">
  <img alt="Data IMAS CC BY 4.0" src="https://img.shields.io/badge/data-IMAS%20CC%20BY%204.0-orange">
  <img alt="MIT licence" src="https://img.shields.io/badge/licence-MIT-blue">
</p>

<p align="center"><img src="screenshots/start.jpg" width="860" alt="BathyPicker: a satellite map of Australia with a box to drag over any waters, and the settings panel"></p>

The Institute for Marine and Antarctic Studies (IMAS) has published depth data for all of
Australia's waters, down to 10 m detail along the coast. It's a superb dataset, but it's
about 94 GB in 8 huge files. BathyPicker lets you draw a box on a map and downloads **just
that area**, merging every depth zone into one ready-to-use GeoTIFF. A bay takes seconds.

The result opens in [SeaBathy](https://github.com/SeaBathy/seabathy-android), QGIS and
most mapping software. It runs on a computer, or as an [Android app](#android-app) that
builds the file right on your phone.

> **Not for navigation.** This is survey data for planning and interest, not a nautical chart.

**New here?** The [how-to guide](https://seabathy.com/guide) shows the whole path in a few minutes: get a free depth file with BathyPicker, open it on your phone or PC, and put it on your chartplotter with AT5 Chart Maker.

## Contents

- [Features](#features)
- [Download and run](#download-and-run)
- [Android app](#android-app)
- [How to make a GeoTIFF](#how-to-make-a-geotiff)
- [What you get](#what-you-get)
- [Use your GeoTIFF](#use-your-geotiff)
- [Command line](#command-line)
- [Build the exe yourself](#build-the-exe-yourself)
- [Data, credits and licences](#data-credits-and-licences)

## Features

| | |
|---|---|
| 🗺️ **Pick on a map** | Drag a box over any Australian waters on a satellite or street map, type the corners, or jump to a preset. |
| 🧩 **One merged file** | All 8 depth zones (shallow, mesophotic, rariphotic, upper, mid and lower slope, abyss, hadal) merged into a single Cloud-Optimized GeoTIFF. |
| ⚡ **Only what you need** | Streams just your box from the IMAS servers, never the whole 94 GB. |
| 📏 **Sensible sizes** | *Check size* shows the pixels and file size first. *Automatic* detail picks the finest that fits under 8 GB. |
| 🧭 **Your choice of coordinates** | WGS84 lat/long (what SeaBathy and most apps read) or GDA2020 Australian Albers (metres). |
| 📱 **Android app** | The same map as a phone app. Builds the GeoTIFF on the phone, saves it to Downloads and opens it straight in SeaBathy. |
| 🔧 **Nothing to install** | A single Windows exe, or one Python file with no packages. GDAL is set up for you the first time. |

## Download and run

**Windows:** download **[`BathyPicker.exe`](download/BathyPicker.exe)** and double-click it.

- A small black window opens (leave it open) and the map page opens in your web browser.
- The first time, Windows may say *"Windows protected your PC"*, because the exe isn't
  signed by a big company. Click **More info**, then **Run anyway**.
- To stop it, close the black window.

**Android:** download **[`BathyPicker.apk`](download/BathyPicker.apk)** on your phone and tap it.
See [Android app](#android-app) below.

**macOS, Linux or Windows with Python 3.8 or newer:**

```bash
python3 bathy_picker.py
```

No packages to install. It uses only Python's standard library.

<details>
<summary><b>About GDAL (set up for you)</b></summary>

BathyPicker uses GDAL's `gdalinfo` and `gdalwarp` to read and merge the data. If GDAL isn't
on your computer, the page offers to download a private copy the first time: a few hundred
MB, once, about a minute, with no admin rights needed. It goes in its own folder
(`%LOCALAPPDATA%\bathy_picker` on Windows) and changes nothing else. It comes from the
[micromamba](https://mamba.readthedocs.io) installer and conda-forge's `libgdal-core`.

Or install GDAL yourself: `sudo apt install gdal-bin` (Ubuntu/Debian), `brew install gdal`
(macOS), or QGIS / OSGeo4W (Windows).
</details>

## Android app

The same page as a phone app, for Android 7.0 or newer. Draw your box, press *Check size*,
then *Build GeoTIFF*. The file is saved in **Download/BathyPicker**, and *Open* or *Share*
sends it straight to SeaBathy or anywhere else.

<p align="center">
  <img src="screenshots/android-check.jpg" width="270" alt="BathyPicker on Android: the Whitsundays box with Check size showing 10 m detail and 142 MB">
  <img src="screenshots/android-done.jpg" width="270" alt="BathyPicker on Android: the finished GeoTIFF with Open and Share buttons">
</p>

- **Install:** download [`BathyPicker.apk`](download/BathyPicker.apk) on the phone and tap it.
  Android asks to allow installs from your browser or file app the first time: tap
  **Settings**, turn on **Allow from this source**, go back and tap **Install**. If Play Protect
  says it doesn't recognise the app (it isn't from the Play Store), tap **More details**, then
  **Install anyway**.
- **No GDAL needed.** The app has its own small engine that reads just your box from IMAS,
  reprojects it and merges the depth zones the same way `gdalwarp` does. Its files were checked
  pixel by pixel against `gdalwarp` and match.
- **Size limit 3 GB** (8 GB on a computer). *Automatic* detail picks the finest that fits.
- **Keeps going in the background** with a progress notification, even with the screen off.
  *Cancel* leaves nothing behind.
- In SeaBathy, import the file by browsing to **Download/BathyPicker** under *This device*.

The source and how to build it are in [`android/`](android). You can also still run BathyPicker
on a computer and open its page from your phone with `--host 0.0.0.0` (see
[Command line](#command-line)).

## How to make a GeoTIFF

| | |
|---|---|
| **1. Draw your box.** The map opens on the whole of Australia. Drag the box over any waters by its round handle and pull the corners, or pick a place from *Jump to*. Untick any depth zones you don't want. | <img src="screenshots/start.jpg" width="420"> |
| **2. Press *Check size*.** It shows the detail, pixels, file size and where the file will be saved. Big areas step down in detail by themselves. | <img src="screenshots/check.jpg" width="420"> |
| **3. Press *Build GeoTIFF*.** A small area takes seconds. The result shows the file, its depth range and how much of the box has data. | <img src="screenshots/done.jpg" width="420"> |

Files are saved in `bathy_exports` in your user folder unless you choose another folder on
the page.

## What you get

The Whitsundays, built with BathyPicker and opened in
[SeaBathy Desktop](https://github.com/SeaBathy/seabathy-desktop): coloured by depth, then in
3D with satellite imagery draped over the seabed at Whitehaven Beach and Hill Inlet.

IMAS maps the seafloor only, so land in the file is empty. For the 3D picture, island
heights from [GMRT](https://www.gmrt.org) (free worldwide elevation, CC BY 4.0) were laid
under the IMAS depths with GDAL, so the satellite drapes over the hills too:

```bash
gdalwarp -te <west> <south> <east> <north> -tr <IMAS pixel size> gmrt.tif gmrt_grid.tif
gdalbuildvrt stack.vrt gmrt_grid.tif imas.tif      # IMAS on top wherever it has data
gdal_translate stack.vrt with-land.tif
```

<p align="center">
  <img src="screenshots/in-seabathy.jpg" width="420" alt="The GeoTIFF in SeaBathy's chart, coloured by depth">
  <img src="screenshots/in-seabathy-3d.jpg" width="420" alt="The Whitsundays in SeaBathy's 3D view with satellite imagery">
</p>

Only seafloor inside Australian waters has data. Land and other countries' waters are left
empty (NoData).

## Use your GeoTIFF

| Where | How |
|---|---|
| **Phone** | [SeaBathy for Android](https://github.com/SeaBathy/seabathy-android): **Settings → Load GeoTIFF**, then pick it from *Download/BathyPicker*. Or press **Open** in the BathyPicker app. Colour it by depth, fly over it in 3D, use it offline. |
| **PC** | [SeaBathy Desktop](https://github.com/SeaBathy/seabathy-desktop): drag the file onto the window. |
| **Chartplotter** | [AT5 Chart Maker](https://github.com/SeaBathy/AT5-Chart-Maker): drag the file in, press **Convert**, copy the folder to the SD card. Simrad, Lowrance and B&G units get a shaded depth picture and contour lines. |
| **GIS** | QGIS, ArcGIS, Global Mapper and anything else that reads GeoTIFF. |

For the plotter, a small depth range (such as 0–30 m) in AT5 Chart Maker shows reefs in the most
detail. For the phone, files up to a few hundred MB open quickly; bigger ones use SeaBathy's
built-in GDAL on 64-bit phones.

## Command line

Everything the page does also works without it. The exe takes the same options.

```bash
python3 bathy_picker.py --check                                    # test GDAL and the IMAS server
python3 bathy_picker.py --setup                                    # just download GDAL
python3 bathy_picker.py --bbox 148.70 -20.40 149.10 -20.10 --yes   # build a box, no map
python3 bathy_picker.py --help                                     # every option
```

| Option | What it does |
|---|---|
| `--res` | `auto` (default), `finest`, or a size in metres |
| `--zones` | Which depth zones, e.g. `shallow,mesophotic` (default all) |
| `--crs` | `wgs84` (default) or `albers` |
| `--out` | Folder for finished files |
| `--max-gb` | Size limit, default 8 |
| `--host 0.0.0.0` | Open the page from a phone on the same network (it prints a private link) |

## Build the exe yourself

```bash
pip install pyinstaller
pyinstaller --onefile --console --name BathyPicker launcher.py
```

`launcher.py` starts `bathy_picker.py` unchanged. It only adds what a single exe needs, and
keeps the window open after an error so it can be read.

## Data, credits and licences

- **Depth data:** Flukes, E. (2026). *Multi-resolution bathymetry composite surface for
  Australian waters (EEZ)* [Data set]. Institute for Marine and Antarctic Studies.
  https://doi.org/10.25959/Y9VF-V723. Licensed
  [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/): credit IMAS when you share
  files you make with it. The page shows this citation with every build.
- **Map in the page:** Sentinel-2 cloudless by EOX (CC BY-NC-SA 4.0) and OpenStreetMap, credited on
  the map, drawn with [Leaflet](https://leafletjs.com) (BSD 2-Clause), which is included in the Android
  app. **Your own map link** in the map's layer menu adds any other tile map (Esri's, for example) under
  your own terms with that provider.
- **GDAL** (MIT), downloaded on first use if needed, not included here. The Android app doesn't use it.
- **The exe** is built with [PyInstaller](https://pyinstaller.org) and includes Python (PSF licence).
- **BathyPicker's code:** MIT licence, see [LICENSE](LICENSE).
- **Full licence texts and notices** for everything above: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and the [`licenses`](licenses) folder.

BathyPicker is an independent project, not made or endorsed by IMAS or the University of
Tasmania.

Also from SeaBathy: [SeaBathy for Android](https://github.com/SeaBathy/seabathy-android),
[SeaBathy Desktop](https://github.com/SeaBathy/seabathy-desktop) and
[AT5 Chart Maker](https://github.com/SeaBathy/AT5-Chart-Maker).
