package com.seabathy.bathypicker.core;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/** Computes each key once, even when several threads ask at the same time. Failures are not kept. */
final class OnceMap<K, V> {
    private final ConcurrentHashMap<K, FutureTask<V>> map = new ConcurrentHashMap<>();

    V get(K key, Callable<V> make) throws Exception {
        FutureTask<V> task = map.get(key);
        if (task == null) {
            FutureTask<V> mine = new FutureTask<>(make);
            task = map.putIfAbsent(key, mine);
            if (task == null) {
                task = mine;
                mine.run();
            }
        }
        try {
            return task.get();
        } catch (ExecutionException e) {
            map.remove(key, task);
            Throwable c = e.getCause();
            if (c instanceof Exception) throw (Exception) c;
            throw new RuntimeException(c);
        }
    }

    void clear() {
        map.clear();
    }
}
