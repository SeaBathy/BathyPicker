<h1 align="center">BathyPicker</h1>

<p align="center"><b>High-detail seabed maps of anywhere in Australian waters.</b><br>
Draw a box, get one depth GeoTIFF, up to 10 m detail. Free and open source.</p>

<p align="center">
  <img alt="Version 1.0" src="https://img.shields.io/badge/version-1.0-0f766e">
  <img alt="Windows, macOS or Linux" src="https://img.shields.io/badge/runs%20on-Windows%20%7C%20macOS%20%7C%20Linux-0078d4">
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
most mapping software.

> **Not for navigation.** This is survey data for planning and interest, not a nautical chart.

## Contents

- [Features](#features)
- [Download and run](#download-and-run)
- [How to make a GeoTIFF](#how-to-make-a-geotiff)
- [What you get](#what-you-get)
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
| 📱 **From a phone too** | Run it on a PC and open the page from a phone on the same network. |
| 🔧 **Nothing to install** | A single Windows exe, or one Python file with no packages. GDAL is set up for you the first time. |

## Download and run

**Windows:** download **[`BathyPicker.exe`](download/BathyPicker.exe)** and double-click it.

- A small black window opens (leave it open) and the map page opens in your web browser.
- The first time, Windows may say *"Windows protected your PC"*, because the exe isn't
  signed by a big company. Click **More info**, then **Run anyway**.
- To stop it, close the black window.

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

<p align="center">
  <img src="screenshots/in-seabathy.jpg" width="420" alt="The GeoTIFF in SeaBathy's chart, coloured by depth">
  <img src="screenshots/in-seabathy-3d.jpg" width="420" alt="The Whitsundays in SeaBathy's 3D view with satellite imagery">
</p>

Only seafloor inside Australian waters has data. Land and other countries' waters are left
empty (NoData).

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
- **Map in the page:** Esri World Imagery and OpenStreetMap, credited on the map, drawn with
  [Leaflet](https://leafletjs.com) (BSD 2-Clause).
- **GDAL** (MIT), downloaded on first use if needed, not included here.
- **The exe** is built with [PyInstaller](https://pyinstaller.org) and includes Python (PSF licence).
- **BathyPicker's code:** MIT licence, see [LICENSE](LICENSE).

BathyPicker is an independent project, not made or endorsed by IMAS or the University of
Tasmania.

Also from SeaBathy: [SeaBathy for Android](https://github.com/SeaBathy/seabathy-android),
[SeaBathy Desktop](https://github.com/SeaBathy/seabathy-desktop) and
[AT5 Chart Maker](https://github.com/SeaBathy/AT5-Chart-Maker).
