package com.seabathy.bathypicker;

import android.content.Context;
import android.os.Environment;
import android.os.StatFs;

import com.seabathy.bathypicker.core.Catalog;
import com.seabathy.bathypicker.core.Http;
import com.seabathy.bathypicker.core.Job;
import com.seabathy.bathypicker.core.Planner;

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

    private Engine(Context c) {
        app = c.getApplicationContext();
        Http.userAgent = "BathyPicker-Android/" + BuildConfig.VERSION + " (+https://github.com/SeaBathy/BathyPicker)";
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
