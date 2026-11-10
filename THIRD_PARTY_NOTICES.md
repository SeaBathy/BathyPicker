# Third-party software and data

BathyPicker's own code is under the MIT licence in [`LICENSE`](LICENSE). It uses, or
`BathyPicker.exe` includes, the software and data below under their own licences. The full
licence texts are in the [`licenses`](licenses) folder.

## Included in `BathyPicker.exe`

| Component | Version | Licence | Full text |
| --- | --- | --- | --- |
| Python (interpreter and standard library, with its bundled libraries: bzip2, libffi, OpenSSL, SQLite, xz, zlib and others listed in the file) | 3.13 | Python Software Foundation License | [`licenses/Python-3.13-LICENSE.txt`](licenses/Python-3.13-LICENSE.txt) |
| PyInstaller bootloader | — | GPL 2.0 with the bootloader exception, which allows the exe to be distributed under any licence | https://github.com/pyinstaller/pyinstaller/blob/develop/COPYING.txt |

`bathy_picker.py` on its own uses only Python's standard library.

## Loaded by the map page (not included)

| Component | Version | Licence | Full text |
| --- | --- | --- | --- |
| Leaflet (loaded from unpkg.com) | 1.9.4 | BSD 2-Clause | [`licenses/Leaflet-1.9.4-LICENSE.txt`](licenses/Leaflet-1.9.4-LICENSE.txt) |

## Downloaded on first use if needed (not included)

| Component | Licence | Source |
| --- | --- | --- |
| GDAL (`libgdal-core` from conda-forge), with its dependencies | MIT, with each dependency under its own licence | https://gdal.org, https://conda-forge.org |
| micromamba (fetches GDAL) | BSD 3-Clause | https://github.com/mamba-org/mamba |

## Maps and data

| Data | Credit | Terms |
| --- | --- | --- |
| Depth data | Flukes, E. (2026). *Multi-resolution bathymetry composite surface for Australian waters (EEZ)* [Data set]. Institute for Marine and Antarctic Studies. https://doi.org/10.25959/Y9VF-V723 | [CC BY 4.0](licenses/CC-BY-4.0.txt): credit IMAS when you share files you make with it. Not for navigation. |
| Satellite map in the page | Esri World Imagery: "Tiles © Esri — Source: Esri, Maxar, Earthstar Geographics, and the GIS User Community", shown on the map | Viewed online only, never downloaded in bulk |
| Street map in the page | © OpenStreetMap contributors, shown on the map | Viewed online only, under the [OpenStreetMap tile usage policy](https://operations.osmfoundation.org/policies/tiles/) |

BathyPicker is an independent project, not made or endorsed by IMAS, the University of
Tasmania, Esri or the OpenStreetMap Foundation.
