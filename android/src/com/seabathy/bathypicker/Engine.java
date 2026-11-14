package com.seabathy.bathypicker;

import android.content.Context;
import android.os.Environment;
import android.os.StatFs;

import com.seabathy.bathypicker.core.Catalog;
import com.seabathy.bathypicker.core.Http;
import com.seabathy.bathypicker.core.Job;
import com.seabathy.bathypicker.core.Planner;
import com.seabathy.bathypicker.core.Surveys;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** Lives as long as the app process: the zone headers, the thread pools and the one build job. */
final class Engine {
    static final double MAX_GB = Planner.DEFAULT_MAX_GB;
    static final String FOLDER = "BathyPicker";
    static final String OUT_LABEL = "Download/" + FOLDER;

    private static Engine instance;

    final Context app;
    final ExecutorService net = Executors.newFixedThreadPool(8, named("bp-net"));
    final ExecutorService requests = Executors.newCachedThreadPool(named("bp-request"));
    final ExecutorService workers = Executors.newFixedThreadPool(
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())), named("bp-work"));
    final Catalog catalog = new Catalog(net);
    final Job job = new Job();
    /** The plan the build service picks up. */
    volatile Planner.Plan pending;
    private volatile Surveys surveys;

    private Engine(Context c) {
        app = c.getApplicationContext();
        Http.userAgent = "BathyPicker-Android/" + BuildConfig.VERSION + " (+https://github.com/SeaBathy/BathyPicker)";
        // The survey list: the copy saved last time, else the one inside the app; then a fresh one online.
        surveys = load(new File(app.getFilesDir(), "surveys.json"), "cached");
        Surveys bundled = null;
        try (InputStream in = app.getAssets().open("surveys.json")) {
            bundled = Surveys.parse(readAll(in), "bundled");
        } catch (IOException ignored) {
            // no list inside the app
        }
        if (surveys == null || (bundled != null && bundled.built.compareTo(surveys.built) > 0)) surveys = bundled;
        requests.execute(this::refreshSurveys);
    }

    Surveys surveys() {
        return surveys;
    }

    private static Surveys load(File f, String origin) {
        try (InputStream in = new FileInputStream(f)) {
            return Surveys.parse(readAll(in), origin);
        } catch (IOException e) {
            return null;
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > 8_000_000) throw new IOException("Survey list is too big");
        }
        return out.toString("UTF-8");
    }

    /** Fetches the latest survey list; keeps it only if it's newer than the one in use. */
    private void refreshSurveys() {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(Surveys.URL).openConnection();
            con.setConnectTimeout(20_000);
            con.setReadTimeout(30_000);
            con.setRequestProperty("User-Agent", Http.userAgent);
            if (con.getResponseCode() != 200) return;
            String text;
            try (InputStream in = con.getInputStream()) {
                text = readAll(in);
            }
            Surveys fresh = Surveys.parse(text, "online");
            Surveys now = surveys;
            if (fresh == null || (now != null && !now.built.isEmpty() && fresh.built.compareTo(now.built) < 0)) return;
            surveys = fresh;
            try (FileOutputStream out = new FileOutputStream(new File(app.getFilesDir(), "surveys.json"))) {
                out.write(text.getBytes("UTF-8"));
            }
        } catch (IOException | RuntimeException ignored) {
            // offline: keep the list we have
        } finally {
            if (con != null) con.disconnect();
        }
    }

    static synchronized Engine get(Context c) {
        if (instance == null) instance = new Engine(c);
        return instance;
    }

    /** Free space where files are saved, or -1 if unknown. */
    static long freeBytes() {
        try {
            return new StatFs(Environment.getExternalStorageDirectory().getPath()).getAvailableBytes();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static ThreadFactory named(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
