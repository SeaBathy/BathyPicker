package com.seabathy.bathypicker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import com.seabathy.bathypicker.core.Builder;
import com.seabathy.bathypicker.core.Job;
import com.seabathy.bathypicker.core.Planner;
import com.seabathy.bathypicker.core.TileSource;

import java.io.InterruptedIOException;
import java.util.Map;

/**
 * Runs a build as a foreground service with a progress notification, so it keeps going with the
 * screen off or the app in the background. Holds a wake lock and a Wi-Fi lock while it works.
 */
public final class BuildService extends Service {
    static final String ACTION_CANCEL = "com.seabathy.bathypicker.CANCEL";
    private static final String CHANNEL_PROGRESS = "build";
    private static final String CHANNEL_DONE = "done";
    private static final int ID_PROGRESS = 1, ID_DONE = 2;

    private Thread worker;

    static void start(Context c) {
        Intent i = new Intent(c, BuildService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Engine e = Engine.get(this);
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            e.job.requestCancel();
            return START_NOT_STICKY;
        }
        channels();
        Notification n = progress(e.job, 0, "Starting");
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(ID_PROGRESS, n);
        if (worker == null || !worker.isAlive()) {
            worker = new Thread(this::run, "bp-build");
            worker.start();
        }
        return START_NOT_STICKY;
    }

    private void run() {
        Engine e = Engine.get(this);
        Job job = e.job;
        Planner.Plan plan = e.pending;
        e.pending = null;
        if (plan == null || !job.running()) {
            if (job.running()) job.fail("The build lost its settings. Press Build GeoTIFF again.");
            stopSelf();
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        PowerManager.WakeLock wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BathyPicker:build");
        wake.acquire(6 * 60 * 60 * 1000L);
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        @SuppressWarnings("deprecation")
        WifiManager.WifiLock wifi = wm != null ? wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "BathyPicker:build") : null;
        if (wifi != null) wifi.acquire();

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Thread ticker = new Thread(() -> {
            while (job.running()) {
                nm.notify(ID_PROGRESS, progress(job, job.percent(), job.stage()));
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ex) {
                    return;
                }
            }
        }, "bp-notify");
        ticker.setDaemon(true);
        ticker.start();

        Output out = null;
        try {
            out = Output.create(this, plan.filename);
            long budget = Math.max(48L << 20, Runtime.getRuntime().maxMemory() / 3);
            TileSource tiles = new TileSource(e.net, budget);
            Builder b = new Builder(plan, job, tiles, e.workers);
            Map<String, Object> r = b.run(out.write, out.read);
            if (job.cancelled()) throw new InterruptedIOException("Cancelled");
            job.progress(99.5, "Saving to Downloads");
            Uri uri = out.publish();
            out = null;
            r.put("path", Engine.OUT_LABEL + "/" + plan.filename);
            r.put("uri", uri != null ? uri.toString() : null);
            job.done(r);
            finished(nm, "Saved " + plan.filename, uri);
        } catch (InterruptedIOException ex) {
            if (out != null) out.discard();
            job.markCancelled();
        } catch (Throwable ex) {
            if (out != null) out.discard();
            if (job.cancelled()) {
                job.markCancelled();
            } else {
                String msg = ex instanceof OutOfMemoryError
                        ? "The phone ran out of memory. Try a smaller box or coarser detail."
                        : (ex.getMessage() != null ? ex.getMessage() : ex.toString());
                job.add(ex.toString());
                job.fail(msg);
                finished(nm, "The build failed: " + msg, null);
            }
        } finally {
            ticker.interrupt();
            if (wifi != null && wifi.isHeld()) wifi.release();
            if (wake.isHeld()) wake.release();
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void channels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel p = new NotificationChannel(CHANNEL_PROGRESS, "Build progress", NotificationManager.IMPORTANCE_LOW);
        p.setShowBadge(false);
        nm.createNotificationChannel(p);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_DONE, "Finished builds", NotificationManager.IMPORTANCE_DEFAULT));
    }

    @SuppressWarnings("deprecation")
    private Notification.Builder builder(String channel) {
        return Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, channel) : new Notification.Builder(this);
    }

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification progress(Job job, double pct, String stage) {
        PendingIntent cancel = PendingIntent.getService(this, 1, new Intent(this, BuildService.class).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = builder(CHANNEL_PROGRESS)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Building GeoTIFF, " + Math.round(pct) + "%")
                .setContentText(stage)
                .setStyle(new Notification.BigTextStyle().bigText(stage))
                .setProgress(100, (int) Math.round(pct), pct <= 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp());
        if (!job.cancelled()) b.addAction(new Notification.Action.Builder(null, "Cancel", cancel).build());
        return b.build();
    }

    private void finished(NotificationManager nm, String text, Uri uri) {
        PendingIntent tap = openApp();
        if (uri != null) {
            Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/tiff")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            Intent chooser = Intent.createChooser(view, "Open GeoTIFF with");
            tap = PendingIntent.getActivity(this, 2, chooser, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        }
        Notification n = builder(CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(uri != null ? "GeoTIFF ready" : "BathyPicker")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(tap)
                .build();
        nm.notify(ID_DONE, n);
    }
}
