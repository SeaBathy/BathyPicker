package com.seabathy.bathypicker;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.seabathy.bathypicker.core.Catalog;
import com.seabathy.bathypicker.core.Json;
import com.seabathy.bathypicker.core.Planner;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shows the same map page as the desktop BathyPicker in a WebView. The page's "/api/..." calls
 * come through the BP bridge instead of HTTP. The page itself is served from the app's assets
 * on https://appassets.androidplatform.net, the address Android reserves for this.
 */
public final class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private WebView web;
    private Engine engine;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        engine = Engine.get(this);
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(0x0b, 0x25, 0x30));
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(0x0b, 0x25, 0x30));
        root.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        // Keep the page clear of the status and navigation bars (Android 15 draws apps edge to edge).
        // A WebView ignores its own padding, so the frame around it is padded instead.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            @SuppressWarnings("deprecation") int l = insets.getSystemWindowInsetLeft(), t = insets.getSystemWindowInsetTop(),
                    r = insets.getSystemWindowInsetRight(), b = insets.getSystemWindowInsetBottom();
            v.setPadding(l, t, r, b);
            return insets;
        });

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setUserAgentString(s.getUserAgentString() + " BathyPicker-Android/" + BuildConfig.VERSION);
        web.addJavascriptInterface(new Bridge(), "BP");
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.i("BathyPicker", "page: " + m.message() + " (" + m.sourceId() + ":" + m.lineNumber() + ")");
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (!HOST.equals(u.getHost())) return null;
                String path = u.getPath() == null || u.getPath().equals("/") ? "index.html" : u.getPath().substring(1);
                if (path.contains("..")) return notFound();
                try {
                    InputStream in = getAssets().open(path);
                    WebResourceResponse r = new WebResourceResponse(mime(path), mime(path).startsWith("text") || path.endsWith(".js") ? "utf-8" : null, in);
                    Map<String, String> h = new LinkedHashMap<>();
                    h.put("Cache-Control", "no-store");
                    r.setResponseHeaders(h);
                    return r;
                } catch (IOException e) {
                    return notFound();
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest req, WebResourceResponse resp) {
                Log.w("BathyPicker", "HTTP " + resp.getStatusCode() + " for " + req.getUrl());
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, android.webkit.WebResourceError err) {
                Log.w("BathyPicker", "load error " + err.getErrorCode() + " " + err.getDescription() + " for " + req.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (ActivityNotFoundException ignored) {
                    // no browser
                }
                return true;
            }
        });
        if (state != null) web.restoreState(state);
        else web.loadUrl("https://" + HOST + "/index.html");

        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null, null);
    }

    private static String mime(String path) {
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".js")) return "application/javascript";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    private void askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
    }

    // ------------------------------------------------------------------ the page's API

    private String handle(String path, String body) {
        try {
            switch (path) {
                case "/api/status":
                    return Json.write(engine.job.snapshot());
                case "/api/cancel":
                    return Json.write(Collections.singletonMap("cancelled", engine.job.requestCancel()));
                case "/api/assess":
                case "/api/download":
                    break;
                default:
                    return error("Not found.");
            }
            if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1));
                return error("BathyPicker needs permission to save files in Downloads. Allow it, then try again.");
            }
            Planner.Params p = params(new JSONObject(body.isEmpty() ? "{}" : body));
            Planner.Plan plan = Planner.assess(p, engine.catalog, engine.surveys(), Engine.MAX_GB, Engine.freeBytes(), Engine.OUT_LABEL, null);
            if (path.equals("/api/assess")) return Json.write(plan.json);
            if (!Boolean.TRUE.equals(plan.json.get("ok"))) {
                StringBuilder sb = new StringBuilder();
                for (Object m : (List<?>) plan.json.get("errors")) sb.append(sb.length() > 0 ? " " : "").append(m);
                return error(sb.toString());
            }
            if (!engine.job.start()) return error("A build is already running. Wait for it or cancel it first.");
            engine.pending = plan;
            runOnUiThread(this::askForNotifications);
            try {
                BuildService.start(this);
            } catch (RuntimeException ex) {
                engine.pending = null;
                engine.job.fail("Android didn't let the build start (" + ex.getMessage() + ").");
                return error("Android didn't let the build start. Keep BathyPicker open and try again.");
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("started", true);
            m.put("filename", plan.filename);
            return Json.write(m);
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        } catch (IOException e) {
            return error(e.getMessage() != null ? e.getMessage() : "Couldn't reach the data servers. Check your internet connection.");
        } catch (org.json.JSONException e) {
            return error("Request wasn't valid JSON.");
        } catch (RuntimeException e) {
            return error("Unexpected error: " + e);
        }
    }

    private static Planner.Params params(JSONObject o) throws org.json.JSONException {
        double[] bbox = null;
        JSONArray b = o.optJSONArray("bbox");
        if (b != null && b.length() == 4) {
            bbox = new double[4];
            for (int i = 0; i < 4; i++) bbox[i] = b.optDouble(i, Double.NaN);
        }
        List<String> zones = null;
        Object z = o.opt("zones");
        if (z instanceof JSONArray) {
            zones = new ArrayList<>();
            for (int i = 0; i < ((JSONArray) z).length(); i++) zones.add(((JSONArray) z).getString(i));
        } else if (z != null && !"all".equals(z)) {
            throw new IllegalArgumentException("Pick at least one depth layer.");
        }
        // The original surveys: which the page ticked or unticked, their order (top wins) and edge blending.
        Map<String, Boolean> prefs = new LinkedHashMap<>();
        JSONObject pr = o.optJSONObject("survey_prefs");
        if (pr != null) {
            JSONArray names = pr.names();
            for (int i = 0; names != null && i < names.length(); i++) prefs.put(names.getString(i), pr.optBoolean(names.getString(i)));
        }
        List<String> order = new ArrayList<>();
        JSONArray or = o.optJSONArray("survey_order");
        for (int i = 0; or != null && i < or.length(); i++) order.add(String.valueOf(or.get(i)));
        return Planner.parse(bbox, zones, o.optBoolean("base", true), o.optString("crs", "wgs84"), o.optString("resolution", "auto"),
                o.optString("resampling", "auto"), o.optString("surveys", "default"), prefs, order, o.optBoolean("blend", true));
    }

    private static String error(String msg) {
        return Json.write(Collections.singletonMap("error", msg));
    }

    private void open(String uri) {
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), "image/tiff")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(view, "Open GeoTIFF with"));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No app here opens GeoTIFF files. Try SeaBathy.", Toast.LENGTH_LONG).show();
        }
    }

    private void share(String uri) {
        Intent send = new Intent(Intent.ACTION_SEND).setType("image/tiff")
                .putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(send, "Share GeoTIFF"));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Nothing on this phone can share the file.", Toast.LENGTH_LONG).show();
        }
    }

    /** Called by the page as window.BP. Methods run on a WebView binder thread. */
    private final class Bridge {
        @JavascriptInterface
        public String config() {
            Map<String, Object> cfg = new LinkedHashMap<>();
            List<Object> zones = new ArrayList<>();
            for (Catalog.Zone z : Catalog.ZONES) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key", z.key);
                m.put("label", z.label);
                zones.add(m);
            }
            cfg.put("zones", zones);
            cfg.put("maxGb", Engine.MAX_GB == Math.rint(Engine.MAX_GB) ? (Object) (long) Engine.MAX_GB : (Object) Engine.MAX_GB);
            cfg.put("citation", Catalog.CITATION);
            cfg.put("doi", Catalog.DOI_URL);
            cfg.put("appVersion", BuildConfig.VERSION);
            List<Object> crs = new ArrayList<>();
            for (String k : Arrays.asList("wgs84", "albers")) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key", k);
                m.put("label", Planner.crsLabel(k));
                crs.add(m);
            }
            cfg.put("crs", crs);
            return Json.write(cfg);
        }

        @JavascriptInterface
        public void request(final int id, final String path, final String body) {
            engine.requests.execute(() -> {
                String out = handle(path, body == null ? "" : body);
                runOnUiThread(() -> {
                    if (!isDestroyed()) web.evaluateJavascript("window.__bpReply(" + id + "," + Json.quote(out) + ")", null);
                });
            });
        }

        @JavascriptInterface
        public void open(String uri) {
            runOnUiThread(() -> MainActivity.this.open(uri));
        }

        @JavascriptInterface
        public void share(String uri) {
            runOnUiThread(() -> MainActivity.this.share(uri));
        }
    }
}
