package com.espclaw.mobile;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.WindowInsetsController;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hosts the local web UI (assets/app/index.html) and exposes a small native bridge
 * so the page can call the ESP-Claw device's HTTP API without CORS restrictions.
 * The chat WebSocket is opened directly by the page.
 */
public class MainActivity extends Activity {
    private static final String TAG = "EspClaw";
    private static final String APP_URL = "file:///android_asset/app/index.html";
    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_NOTIFICATIONS = 1002;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int MAX_RESPONSE_BYTES = 1 << 20;
    private static final int IO_THREADS = 4;

    /** True while the UI is on screen; ChatService stays quiet then because the page owns the socket. */
    static volatile boolean visible;

    private final ExecutorService io = Executors.newFixedThreadPool(IO_THREADS);
    private WebView web;
    private ValueCallback<Uri[]> fileCallback;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setTextZoom(100);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Sub-frame navigation (the embedded device panel) stays inside the page.
                if (!request.isForMainFrame()) {
                    return false;
                }
                String url = request.getUrl().toString();
                if (url.startsWith("file:///android_asset/")) {
                    return false;
                }
                openExternal(url);
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE_CHOOSER);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.d(TAG, m.message() + " (" + m.sourceId() + ":" + m.lineNumber() + ")");
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl(APP_URL);
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        // Re-arm the background service if the user enabled it (e.g. after the process was killed).
        if (getSharedPreferences(ChatService.PREFS, MODE_PRIVATE).getBoolean(ChatService.KEY_ENABLED, false)) {
            startChatService();
        }
    }

    @Override
    protected void onStop() {
        visible = false;
        super.onStop();
    }

    private void startChatService() {
        startForegroundService(new Intent(this, ChatService.class));
    }

    private boolean notificationsAllowed() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATIONS) {
            callJs("window.__perm && window.__perm(" + notificationsAllowed() + ")");
        }
    }

    /** Local IPv4 prefix ("192.168.1.") of the active network, or null when offline. */
    private String localPrefix() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        Network net = cm.getActiveNetwork();
        LinkProperties lp = net == null ? null : cm.getLinkProperties(net);
        if (lp == null) {
            return null;
        }
        for (LinkAddress la : lp.getLinkAddresses()) {
            if (la.getAddress() instanceof Inet4Address && !la.getAddress().isLoopbackAddress()) {
                String ip = la.getAddress().getHostAddress();
                return ip.substring(0, ip.lastIndexOf('.') + 1);
            }
        }
        return null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(
                        WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                fileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.__onBack ? window.__onBack() : false", value -> {
            if (!"true".equals(value)) {
                finish();
            }
        });
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        if (web != null) {
            web.destroy();
        }
        super.onDestroy();
    }

    private void openExternal(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "No app to open " + url);
        }
    }

    private void callJs(String script) {
        runOnUiThread(() -> {
            if (web != null) {
                web.evaluateJavascript(script, null);
            }
        });
    }

    /** Performs one HTTP request; returns {status, body}. status 0 means a transport failure. */
    private static Object[] request(String base, String method, String path,
                                    byte[] body, String contentType) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(base + path).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod(method);
            if (body != null && body.length > 0) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", contentType);
                conn.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(body);
                }
            }
            int status = conn.getResponseCode();
            InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            return new Object[]{status, readLimited(in)};
        } catch (IOException | RuntimeException e) {
            return new Object[]{0, String.valueOf(e.getMessage())};
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readLimited(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0 && out.size() < MAX_RESPONSE_BYTES) {
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private void deliver(String id, Object[] result) {
        callJs("window.__http(" + JSONObject.quote(id) + "," + result[0] + ","
                + JSONObject.quote(String.valueOf(result[1])) + ")");
    }

    private final class Bridge {
        @JavascriptInterface
        public void http(String id, String base, String method, String path, String body) {
            io.execute(() -> deliver(id, request(base, method, path,
                    body == null ? null : body.getBytes(StandardCharsets.UTF_8),
                    "application/json")));
        }

        @JavascriptInterface
        public void upload(String id, String base, String path, String base64) {
            io.execute(() -> {
                byte[] bytes;
                try {
                    bytes = Base64.decode(base64, Base64.DEFAULT);
                } catch (IllegalArgumentException e) {
                    deliver(id, new Object[]{0, "bad base64"});
                    return;
                }
                String query;
                try {
                    query = "/api/files/upload?path=" + URLEncoder.encode(path, "UTF-8");
                } catch (java.io.UnsupportedEncodingException e) {
                    deliver(id, new Object[]{0, "encoding"});
                    return;
                }
                deliver(id, request(base, "POST", query, bytes, "application/octet-stream"));
            });
        }

        /** Scans the local /24 for ESP-Claw devices; result arrives via window.__scan(id, json). */
        @JavascriptInterface
        public void scan(String id) {
            io.execute(() -> {
                String prefix = localPrefix();
                JSONObject result = new JSONObject();
                try {
                    if (prefix == null) {
                        result.put("error", "offline");
                    } else {
                        result.put("prefix", prefix);
                        result.put("devices", new JSONArray(DeviceScanner.scan(prefix)));
                    }
                } catch (JSONException e) {
                    Log.w(TAG, "scan result", e);
                }
                callJs("window.__scan(" + JSONObject.quote(id) + "," + result + ")");
            });
        }

        /**
         * Enables/disables the background notification service. Returns whether notifications
         * are currently allowed (the permission prompt is shown when they are not).
         */
        @JavascriptInterface
        public boolean setBackground(boolean enabled, String host, String chatId) {
            getSharedPreferences(ChatService.PREFS, MODE_PRIVATE).edit()
                    .putBoolean(ChatService.KEY_ENABLED, enabled)
                    .putString(ChatService.KEY_HOST, host)
                    .putString(ChatService.KEY_CHAT_ID, chatId)
                    .apply();
            boolean allowed = notificationsAllowed();
            runOnUiThread(() -> {
                if (enabled) {
                    if (!allowed) {
                        requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                                REQ_NOTIFICATIONS);
                    }
                    startChatService();
                } else {
                    stopService(new Intent(MainActivity.this, ChatService.class));
                }
            });
            return allowed;
        }

        /** Replies received by the service while the UI was hidden, as a JSON array. */
        @JavascriptInterface
        public String drainMessages() {
            return ChatService.drainQueue(MainActivity.this);
        }

        @JavascriptInterface
        public void openUrl(String url) {
            runOnUiThread(() -> openExternal(url));
        }

        @JavascriptInterface
        public String version() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "";
            }
        }

        /** Tints the status/navigation bars to match the page theme. */
        @JavascriptInterface
        public void setBars(String color, boolean lightBars) {
            runOnUiThread(() -> {
                int c;
                try {
                    c = Color.parseColor(color);
                } catch (IllegalArgumentException e) {
                    return;
                }
                getWindow().setStatusBarColor(c);
                getWindow().setNavigationBarColor(c);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowInsetsController ic = getWindow().getInsetsController();
                    if (ic != null) {
                        int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                        ic.setSystemBarsAppearance(lightBars ? mask : 0, mask);
                    }
                } else {
                    View decor = getWindow().getDecorView();
                    int flags = decor.getSystemUiVisibility();
                    int mask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                            | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                    decor.setSystemUiVisibility(lightBars ? (flags | mask) : (flags & ~mask));
                }
            });
        }
    }
}
