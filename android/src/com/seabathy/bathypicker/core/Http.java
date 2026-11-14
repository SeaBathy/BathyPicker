package com.seabathy.bathypicker.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;

/** Byte-range downloads from the IMAS and AusSeabed servers, with retries like GDAL's GDAL_HTTP_MAX_RETRY. */
public final class Http {
    public static String userAgent = "BathyPicker/1.0";
    private static final int MAX_RETRY = 6;

    private Http() {}

    /** Bytes [offset, offset + length) of url. */
    public static byte[] range(String url, long offset, int length, Cancel cancel) throws IOException {
        return range(url, offset, length, cancel, false);
    }

    /** As range, but past the end of the file it returns what there is (possibly nothing). */
    public static byte[] rangeUpTo(String url, long offset, int length, Cancel cancel) throws IOException {
        return range(url, offset, length, cancel, true);
    }

    private static byte[] range(String url, long offset, int length, Cancel cancel, boolean allowShort) throws IOException {
        if (length <= 0) return new byte[0];
        String host = host(url);
        IOException last = null;
        for (int attempt = 0; attempt <= MAX_RETRY; attempt++) {
            if (cancel != null) cancel.check();
            if (attempt > 0) {
                try {
                    Thread.sleep(Math.min(30_000L, 1500L << Math.min(attempt - 1, 4)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Cancelled");
                }
            }
            HttpURLConnection con = null;
            boolean ok = false;
            try {
                con = (HttpURLConnection) new URL(url).openConnection();
                con.setConnectTimeout(30_000);
                con.setReadTimeout(120_000);
                con.setRequestProperty("User-Agent", userAgent);
                con.setRequestProperty("Accept-Encoding", "identity");
                con.setRequestProperty("Range", "bytes=" + offset + "-" + (offset + length - 1));
                int code = con.getResponseCode();
                if (code == 206) {
                    byte[] out = new byte[length];
                    try (InputStream in = con.getInputStream()) {
                        int pos = 0;
                        while (pos < length) {
                            int n = in.read(out, pos, length - pos);
                            if (n < 0) break;
                            pos += n;
                            if (cancel != null && cancel.cancelled()) throw new InterruptedIOException("Cancelled");
                        }
                        if (pos != length) {
                            if (!allowShort) throw new IOException("Short read from " + url + " (" + pos + " of " + length + " bytes)");
                            out = java.util.Arrays.copyOf(out, pos);
                        }
                    }
                    ok = true;  // stream fully read and closed: the connection can be reused
                    return out;
                }
                if (code == 416 && allowShort) {
                    ok = true;
                    return new byte[0];
                }
                if (code == 200) {
                    throw new IOException("The " + host + " server ignored the byte range request.");
                }
                last = new IOException("HTTP " + code + " from " + host);
                if (code >= 400 && code < 500 && code != 408 && code != 429) throw last;
            } catch (java.net.SocketTimeoutException e) {
                last = e;  // a slow network: retry (it's an InterruptedIOException, but not a cancel)
            } catch (InterruptedIOException e) {
                throw e;
            } catch (java.net.UnknownHostException e) {
                // No point waiting a minute for retries: the phone has no working internet.
                throw new IOException("Can't reach " + host + ". Check the phone's internet connection.", e);
            } catch (IOException e) {
                last = e;
                if (e.getMessage() != null && e.getMessage().contains("server ignored the byte range")) throw e;
                if (e.getMessage() != null && e.getMessage().startsWith("HTTP 4")) throw e;
            } finally {
                if (con != null && !ok) con.disconnect();
            }
        }
        throw last != null ? last : new IOException("Download failed");
    }

    private static String host(String url) {
        try {
            return new URL(url).getHost();
        } catch (IOException e) {
            return "the data server";
        }
    }

    /** Set by the job; long downloads check it between blocks. */
    public interface Cancel {
        boolean cancelled();

        default void check() throws InterruptedIOException {
            if (cancelled()) throw new InterruptedIOException("Cancelled");
        }
    }
}
