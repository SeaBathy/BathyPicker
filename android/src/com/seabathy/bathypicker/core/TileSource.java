package com.seabathy.bathypicker.core;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fetches source tiles, shared by all build workers: an LRU cache of the compressed tiles sized in bytes
 * (about 4 times more tiles fit than if they were kept decoded),
 * one download per tile even when two workers want it, and neighbouring tiles fetched in one request.
 */
public final class TileSource {
    /** Marks a tile that is all NoData (stored with zero bytes in the file). */
    static final float[] EMPTY = new float[0];
    private static final long MAX_RUN_BYTES = 4L * 1024 * 1024;

    private final ExecutorService net;
    private final long budget;
    private long used;
    private static final byte[] NONE = new byte[0];
    private final LinkedHashMap<String, byte[]> lru = new LinkedHashMap<>(256, 0.75f, true);
    private final ConcurrentHashMap<String, CompletableFuture<byte[]>> inflight = new ConcurrentHashMap<>();
    public final AtomicLong bytesDownloaded = new AtomicLong();

    public TileSource(ExecutorService net, long budgetBytes) {
        this.net = net;
        this.budget = budgetBytes;
    }

    private static String key(RemoteTiff t, RemoteTiff.Level L, int tx, int ty) {
        return t.url + '|' + L.index + '|' + tx + '|' + ty;
    }

    private synchronized byte[] cached(String k) {
        return lru.get(k);
    }

    private synchronized void put(String k, byte[] v) {
        byte[] old = lru.put(k, v);
        if (old != null) used -= old.length + 64L;
        used += v.length + 64L;
        Iterator<Map.Entry<String, byte[]>> it = lru.entrySet().iterator();
        while (used > budget && it.hasNext()) {
            Map.Entry<String, byte[]> e = it.next();
            if (e.getKey().equals(k)) continue;
            used -= e.getValue().length + 64L;
            it.remove();
        }
    }

    /**
     * Compressed tiles tx0..tx1, ty0..ty1 (inclusive) of one level, as [ty - ty0][tx - tx0].
     * An entry has length 0 when the tile holds no data. Decode each with RemoteTiff.decode as it's used,
     * so only one decoded tile is in memory at a time.
     */
    public byte[][][] tiles(RemoteTiff t, RemoteTiff.Level L, int tx0, int ty0, int tx1, int ty1, Http.Cancel cancel)
            throws IOException {
        int nx = tx1 - tx0 + 1, ny = ty1 - ty0 + 1;
        byte[][][] raw = new byte[ny][nx][];
        Map<String, CompletableFuture<byte[]>> waits = new HashMap<>();
        Map<Integer, List<int[]>> mine = new LinkedHashMap<>();  // ty -> owned (tx)
        Map<String, CompletableFuture<byte[]>> owned = new HashMap<>();
        for (int ty = ty0; ty <= ty1; ty++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                String k = key(t, L, tx, ty);
                byte[] hit = cached(k);
                if (hit != null) {
                    raw[ty - ty0][tx - tx0] = hit;
                    continue;
                }
                CompletableFuture<byte[]> f = new CompletableFuture<>();
                CompletableFuture<byte[]> prev = inflight.putIfAbsent(k, f);
                if (prev != null) {
                    waits.put(k, prev);
                } else {
                    hit = cached(k);  // finished between the two checks
                    if (hit != null) {
                        inflight.remove(k, f);
                        f.complete(hit);
                        raw[ty - ty0][tx - tx0] = hit;
                        continue;
                    }
                    owned.put(k, f);
                    waits.put(k, f);
                    List<int[]> row = mine.get(ty);
                    if (row == null) mine.put(ty, row = new ArrayList<>());
                    row.add(new int[]{tx});
                }
            }
        }

        List<CompletableFuture<Void>> jobs = new ArrayList<>();
        try {
            for (Map.Entry<Integer, List<int[]>> e : mine.entrySet()) {
                int ty = e.getKey();
                long[][] idx = t.tileRow(L, ty, cancel);
                long[] off = idx[0], cnt = idx[1];
                List<int[]> txs = e.getValue();
                int i = 0;
                while (i < txs.size()) {
                    int a = txs.get(i)[0];
                    if (cnt[a] == 0) {
                        finish(owned, key(t, L, a, ty), NONE);
                        i++;
                        continue;
                    }
                    int j = i;
                    long end = off[a] + cnt[a];
                    while (j + 1 < txs.size()) {
                        int b = txs.get(j + 1)[0];
                        if (b != txs.get(j)[0] + 1 || cnt[b] == 0 || off[b] != end || end + cnt[b] - off[a] > MAX_RUN_BYTES) break;
                        end += cnt[b];
                        j++;
                    }
                    final int first = a, last = txs.get(j)[0];
                    final long start = off[a];
                    final int len = (int) (end - start);
                    final int fty = ty;
                    jobs.add(CompletableFuture.runAsync(() -> {
                        try {
                            byte[] data = Http.range(t.url, start, len, cancel);
                            bytesDownloaded.addAndGet(len);
                            for (int tx = first; tx <= last; tx++) {
                                int o = (int) (off[tx] - start);
                                finish(owned, key(t, L, tx, fty), Arrays.copyOfRange(data, o, o + (int) cnt[tx]));
                            }
                        } catch (Exception ex) {
                            for (int tx = first; tx <= last; tx++) fail(owned, key(t, L, tx, fty), ex);
                        }
                    }, net));
                    i = j + 1;
                }
            }
        } catch (IOException ex) {
            for (String k : owned.keySet()) fail(owned, k, ex);
            throw ex;
        }

        for (Map.Entry<String, CompletableFuture<byte[]>> w : waits.entrySet()) {
            byte[] v;
            try {
                v = w.getValue().get();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Cancelled");
            } catch (ExecutionException ex) {
                Throwable c = ex.getCause();
                if (c instanceof IOException) throw (IOException) c;
                throw new IOException(String.valueOf(c), c);
            }
            String[] parts = w.getKey().split("\\|");
            int tx = Integer.parseInt(parts[parts.length - 2]), ty = Integer.parseInt(parts[parts.length - 1]);
            raw[ty - ty0][tx - tx0] = v;
        }
        return raw;
    }

    private void finish(Map<String, CompletableFuture<byte[]>> owned, String k, byte[] v) {
        put(k, v);
        CompletableFuture<byte[]> f = owned.get(k);
        inflight.remove(k, f);
        f.complete(v);
    }

    private void fail(Map<String, CompletableFuture<byte[]>> owned, String k, Exception ex) {
        CompletableFuture<byte[]> f = owned.get(k);
        if (f == null || f.isDone()) return;
        inflight.remove(k, f);
        f.completeExceptionally(ex);
    }
}
