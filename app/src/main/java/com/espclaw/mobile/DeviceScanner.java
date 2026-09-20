package com.espclaw.mobile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Finds ESP-Claw devices on a /24 by probing {@code GET /api/webim/status} on every host and
 * looking for the {@code "bound"} field. Pure Java so it can be tested off-device.
 */
final class DeviceScanner {
    private static final int FIRST_HOST = 1;
    private static final int LAST_HOST = 254;
    private static final int PROBE_TIMEOUT_MS = 800;
    private static final int MAX_BODY_BYTES = 512;
    private static final int THREADS = 48;
    private static final long OVERALL_TIMEOUT_S = 20;
    private static final String PROBE_PATH = "/api/webim/status";
    private static final String MARKER = "\"bound\"";

    private DeviceScanner() {
    }

    /** @param prefix the first three octets with a trailing dot, e.g. {@code "192.168.1."}. */
    static List<String> scan(String prefix) {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<String>> jobs = new ArrayList<>();
        for (int i = FIRST_HOST; i <= LAST_HOST; i++) {
            final String ip = prefix + i;
            jobs.add(pool.submit(() -> probe(ip) ? ip : null));
        }
        pool.shutdown();
        try {
            pool.awaitTermination(OVERALL_TIMEOUT_S, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        pool.shutdownNow();

        List<String> found = new ArrayList<>();
        for (Future<String> job : jobs) {
            if (job.isDone() && !job.isCancelled()) {
                try {
                    String ip = job.get();
                    if (ip != null) {
                        found.add(ip);
                    }
                } catch (Exception ignored) {
                    // probe failed: not a device
                }
            }
        }
        Collections.sort(found);
        return found;
    }

    private static boolean probe(String ip) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL("http://" + ip + PROBE_PATH).openConnection();
            conn.setConnectTimeout(PROBE_TIMEOUT_MS);
            conn.setReadTimeout(PROBE_TIMEOUT_MS);
            if (conn.getResponseCode() != 200) {
                return false;
            }
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                byte[] buf = new byte[256];
                int n;
                while ((n = in.read(buf)) > 0 && body.size() < MAX_BODY_BYTES) {
                    body.write(buf, 0, n);
                }
                return body.toString(StandardCharsets.UTF_8.name()).contains(MARKER);
            }
        } catch (IOException | RuntimeException e) {
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
