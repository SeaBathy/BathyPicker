"""Makes assets/index.html from the page inside ../bathy_picker.py, so the phone and the
desktop page stay the same. Each change below must match exactly once, or this stops.
Run it again whenever the desktop page changes:  python -I tools/make_page.py
"""
import ast
import pathlib
import sys

here = pathlib.Path(__file__).resolve().parent.parent
src = (here.parent / "bathy_picker.py").read_text(encoding="utf-8")
tree = ast.parse(src)
html = next(n.value.value for n in tree.body if isinstance(n, ast.Assign) and getattr(n.targets[0], "id", "") == "HTML")


def sub(old, new, count=1):
    global html
    found = html.count(old)
    if found != count:
        sys.exit(f"make_page: expected {count} match(es), found {found}:\n{old[:200]}")
    html = html.replace(old, new)


def cut(start, end):
    """Remove from start up to (not including) end."""
    global html
    a = html.find(start)
    b = html.find(end, a)
    if a < 0 or b < 0 or html.count(start) != 1:
        sys.exit(f"make_page: can't find block starting {start[:80]!r}")
    html = html[:a] + html[b:]


# Leaflet ships inside the app.
sub('<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">', '<link rel="stylesheet" href="leaflet/leaflet.css">')
sub('<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>', '<script src="leaflet/leaflet.js"></script>')
sub("<title>Seafloor GeoTIFF picker</title>", "<title>BathyPicker</title>")

# Wording: an app on the phone, not a script on a computer.
sub("The script pulls every depth layer for it from IMAS and merges them into one GeoTIFF.",
    "The app pulls every depth layer for it from IMAS and merges them into one GeoTIFF.")
sub("<h1>Seafloor GeoTIFF picker</h1>", "<h1>BathyPicker</h1>")

# No GDAL to set up.
cut('  <section id="setup" hidden', '  <section aria-labelledby="h-area">')
cut("  // ---------- one-time GDAL setup", "  place(); syncInputs(true); refreshButtons();")
sub('  $("setup-home").textContent = CFG.setup.home;\n', "")
sub("  let toolsReady = !CFG.setup.needed;", "  const toolsReady = true;")

# Files always go to Downloads/BathyPicker, so no folder picker.
cut('    <div class="f" style="margin-top:10px">\n      <label for="outdir">Save to</label>', '    <details style="margin-top:10px">')
sub('    <details style="margin-top:10px">\n      <summary>More options</summary>',
    '    <p class="hint" style="margin-top:10px">Saves to <strong>Download/BathyPicker</strong> on this phone.</p>\n'
    '    <input id="outdir" type="hidden" value="">\n'
    '    <details style="margin-top:10px">\n      <summary>More options</summary>')
sub(" WGS84 lat/long is what SeaBathy reads. The folder is on the computer running this script.</p>",
    " WGS84 lat/long is what SeaBathy reads.</p>")
cut('<dialog id="picker"', '<script src="leaflet/leaflet.js">')
cut("  // ---------- save-to folder chooser", "  // ---------- assess")
sub('  $("outdir").value = CFG.outDir;\n', "")
sub('  ["res", "crs", "resamp", "outdir"].forEach', '  ["res", "crs", "resamp"].forEach')

# Footer: the app version in place of GDAL's.
sub('<p style="margin-top:6px">Not for navigation. GDAL: <span id="gdalver"></span></p>',
    '<p style="margin-top:6px">Not for navigation. BathyPicker for Android <span id="gdalver"></span>. '
    '<a href="https://github.com/SeaBathy/BathyPicker">Source and licences</a></p>')
sub('  $("gdalver").textContent = CFG.gdalVersion || "not installed yet";', '  $("gdalver").textContent = CFG.appVersion;')

# Talk to the app through its bridge instead of HTTP.
sub("  const CFG = __CONFIG__;\n  const TOKEN = \"__TOKEN__\";", "  const CFG = JSON.parse(BP.config());")
a = html.index("  async function api(path, body) {")
b = html.index("  function refreshButtons() {")
html = html[:a] + """  const pending = {};
  let seq = 0;
  window.__bpReply = function (id, json) {
    const done = pending[id];
    delete pending[id];
    if (done) { let data = {}; try { data = JSON.parse(json); } catch (e) { data = { error: "The app sent a bad answer." }; } done(data); }
  };
  function api(path, body) {
    return new Promise((resolve) => {
      const id = ++seq;
      pending[id] = resolve;
      try { BP.request(id, path, body === undefined ? "" : JSON.stringify(body)); }
      catch (e) { delete pending[id]; resolve({ error: "The app couldn't take that request." }); }
    });
  }
""" + html[b:]

# The finished file: open it or share it on the phone.
a = html.index('      const dl = el("a", "", "Save a copy to this device");')
b = html.index('      b.appendChild(el("p", "", "In SeaBathy, import it by browsing to the folder under This device, not Recent."));')
html = html[:a] + """      if (r.uri) {
        const row = el("div", "row"); row.style.marginTop = "8px";
        const open = el("button", "primary", "Open"); open.type = "button";
        open.addEventListener("click", () => BP.open(r.uri));
        const share = el("button", "", "Share"); share.type = "button";
        share.addEventListener("click", () => BP.share(r.uri));
        row.appendChild(open); row.appendChild(share); b.appendChild(row);
      }
""" + html[b:]
sub('"In SeaBathy, import it by browsing to the folder under This device, not Recent."',
    '"In SeaBathy, import it by browsing to Download/BathyPicker under This device, not Recent."')

# Leftover references to the removed bits.
for gone in ("CFG.setup", "TOKEN", "pk-", "setup-", "/files/"):
    if gone in html:
        sys.exit(f"make_page: {gone!r} is still in the page")

out = here / "assets" / "index.html"
out.write_text(html, encoding="utf-8", newline="\n")
print(f"wrote {out} ({len(html)} characters)")
