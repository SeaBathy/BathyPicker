package com.seabathy.bathypicker.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** One build that the page polls. Same fields as the desktop script's Job. */
public final class Job implements Http.Cancel {
    private String state = "idle";  // idle | running | done | error | cancelled
    private double percent;
    private String stage = "";
    private final ArrayDeque<String> log = new ArrayDeque<>();
    private Map<String, Object> result;
    private String error;
    private long started, ended;
    private volatile boolean cancel;

    public synchronized boolean start() {
        if ("running".equals(state)) return false;
        state = "running";
        percent = 0;
        stage = "Starting";
        log.clear();
        result = null;
        error = null;
        cancel = false;
        started = System.currentTimeMillis();
        ended = 0;
        return true;
    }

    public synchronized boolean running() {
        return "running".equals(state);
    }

    public synchronized void progress(double pct, String stageText) {
        if (!"running".equals(state)) return;
        percent = Math.max(0, Math.min(100, pct));
        if (stageText != null) stage = stageText;
    }

    public synchronized double percent() {
        return percent;
    }

    public synchronized String stage() {
        return stage;
    }

    public synchronized void add(String line) {
        log.addLast(line);
        while (log.size() > 120) log.removeFirst();
    }

    public synchronized void done(Map<String, Object> r) {
        state = "done";
        stage = "Done";
        percent = 100;
        result = r;
        ended = System.currentTimeMillis();
    }

    public synchronized void fail(String message) {
        state = "error";
        error = message;
        ended = System.currentTimeMillis();
    }

    public synchronized void markCancelled() {
        state = "cancelled";
        stage = "Cancelled";
        ended = System.currentTimeMillis();
    }

    public synchronized String state() {
        return state;
    }

    public synchronized Map<String, Object> result() {
        return result;
    }

    /** Ask a running build to stop. */
    public boolean requestCancel() {
        synchronized (this) {
            if (!"running".equals(state)) return false;
        }
        cancel = true;
        return true;
    }

    @Override
    public boolean cancelled() {
        return cancel;
    }

    public synchronized Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        long end = ended != 0 ? ended : System.currentTimeMillis();
        m.put("state", state);
        m.put("percent", percent);
        m.put("passes", 1);
        m.put("stage", stage);
        ArrayList<String> tail = new ArrayList<>(log);
        m.put("log", tail.size() > 40 ? new ArrayList<>(tail.subList(tail.size() - 40, tail.size())) : tail);
        m.put("result", result);
        m.put("error", error);
        m.put("elapsed", started != 0 ? Math.round((end - started) / 100.0) / 10.0 : 0);
        return m;
    }
}
