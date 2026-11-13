# Survey catalogue (`catalogue/surveys.json`)

The list of original surveys that are finer than the IMAS composite. BathyPicker reads this list
to offer them on top of the composite. Users never run the builder; you do, a few times a year.

## Rebuild

```bash
pip install rasterio numpy shapely pyproj
python tools/build_catalogue.py
```

It takes about 10–30 minutes. Only file headers and small sample windows are downloaded, never
whole surveys. Results are cached in `catalogue/.cache/` (not committed), so reruns only look at
new or changed files.

## What it checks

- **Depth grids only.** Hillshades, images, backscatter, uncertainty and swath-angle layers are
  skipped by file name.
- **Finer than 10 m** (the IMAS shelf detail).
- **Depth reference.** A few hundred points from each survey are compared with the IMAS
  composite (mean sea level) at the same places:
  - median difference within 0.5 m and a tight spread: kept as is;
  - a steady offset up to 8 m (typically a lowest-tide datum): kept, with `shift_m` to bring it
    to sea level;
  - stored as positive depths: kept, with `sign: -1`;
  - anything else, including heights above the ellipsoid, is left out. The reason is in
    `catalogue/excluded.json`.
- **Re-published copies.** When two folders hold the same area at the same detail, the older
  one gets `default_off: true`.

## Hand edits after a build

Don't edit `surveys.json` by hand; a rebuild overwrites it. Put hand edits in
`catalogue/overrides.json` instead, keyed by survey `id` or source `folder`:

```json
{
  "0364_beagle_park": {"default_off": true, "note": "Same data as Beagle Marine Park 2018."},
  "jervis-bay2-2008-2m-sp0p76": {"note": "Users report a small step at the south edge."}
}
```

Allowed fields are `default_off`, `note` and `name`. The builder warns about any entry that
matches no survey.
