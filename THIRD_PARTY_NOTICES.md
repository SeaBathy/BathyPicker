# Third-party software and data

BathyPicker's own code is under the MIT licence in [`LICENSE`](LICENSE). It uses, or
`BathyPicker.exe` and `BathyPicker.apk` include, the software and data below under their own licences. The full
licence texts are in the [`licenses`](licenses) folder.

## Included in `BathyPicker.exe`

| Component | Version | Licence | Full text |
| --- | --- | --- | --- |
| Python (interpreter and standard library, with its bundled libraries: bzip2, libffi, OpenSSL, SQLite, xz, zlib and others listed in the file) | 3.13 | Python Software Foundation License | [`licenses/Python-3.13-LICENSE.txt`](licenses/Python-3.13-LICENSE.txt) |
| PyInstaller bootloader | — | GPL 2.0 with the bootloader exception, which allows the exe to be distributed under any licence | https://github.com/pyinstaller/pyinstaller/blob/develop/COPYING.txt |

`bathy_picker.py` on its own uses only Python's standard library.

## Included in `BathyPicker.apk` (Android)

| Component | Version | Licence | Full text |
| --- | --- | --- | --- |
| Leaflet (in the app's assets) | 1.9.4 | BSD 2-Clause | [`licenses/Leaflet-1.9.4-LICENSE.txt`](licenses/Leaflet-1.9.4-LICENSE.txt) |

The rest of the app is BathyPicker's own code (MIT) and Android's built-in libraries. It doesn't
use GDAL.

## Loaded by the desktop map page (not included)

| Component | Version | Licence | Full text |
| --- | --- | --- | --- |
| Leaflet (loaded from unpkg.com) | 1.9.4 | BSD 2-Clause | [`licenses/Leaflet-1.9.4-LICENSE.txt`](licenses/Leaflet-1.9.4-LICENSE.txt) |

## Downloaded on first use if needed (not included)

| Component | Licence | Source |
| --- | --- | --- |
| GDAL (`libgdal-core` from conda-forge), with its dependencies; its `gdalinfo`, `gdalwarp` and `gdal` commands are used | MIT, with each dependency under its own licence | https://gdal.org, https://conda-forge.org |
| micromamba (fetches GDAL) | BSD 3-Clause | https://github.com/mamba-org/mamba |

## Maps and data

| Data | Credit | Terms |
| --- | --- | --- |
| Depth data | Flukes, E. (2026). *Multi-resolution bathymetry composite surface for Australian waters (EEZ)* [Data set]. Institute for Marine and Antarctic Studies. https://doi.org/10.25959/Y9VF-V723 | [CC BY 4.0](licenses/CC-BY-4.0.txt): credit IMAS when you share files you make with it. Not for navigation. |
| Original surveys (desktop, on top of the depth data) | © Commonwealth of Australia (Geoscience Australia). AusSeabed bathymetry data. Each GeoTIFF's `_sources.txt` names every survey used, with its link | [CC BY 4.0](licenses/CC-BY-4.0.txt): keep the credit when you share files made with them. Read straight from AusSeabed's public download site; not hosted or changed by BathyPicker, apart from a stated depth shift to sea level where the list says so. Not for navigation. |
| Satellite map in the page | Sentinel-2 cloudless by EOX: "Sentinel-2 cloudless – s2maps.eu by EOX IT Services GmbH (contains modified Copernicus Sentinel data 2024)", shown on the map | [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/): non-commercial use with the credit. Viewed online only |
| Your own map link (optional) | Whatever map you add; Esri links are shown with "Powered by Esri" | That provider's own terms, which are up to you to check |
| Street map in the page | © OpenStreetMap contributors, shown on the map | Viewed online only, under the [OpenStreetMap tile usage policy](https://operations.osmfoundation.org/policies/tiles/) |

BathyPicker is an independent project, not made or endorsed by IMAS, the University of
Tasmania, Geoscience Australia, AusSeabed, EOX, Esri or the OpenStreetMap Foundation.
