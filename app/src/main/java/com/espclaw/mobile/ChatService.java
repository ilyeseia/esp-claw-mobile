package com.espclaw.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Optional foreground service that keeps a chat WebSocket open while the app is in the
 * background. Assistant replies are queued in SharedPreferences (drained by the UI when it
 * returns to the foreground) and announced with a notification.
 */
public class ChatService extends Service {
    static final String PREFS = "espclaw";
    static final String KEY_ENABLED = "bg_enabled";
    static final String KEY_HOST = "host";
    static final String KEY_CHAT_ID = "chat_id";
    private static final String KEY_QUEUE = "queue";

    private static final String CHANNEL_SERVICE = "service";
    private static final String CHANNEL_MESSAGES = "messages";
    private static final int ONGOING_ID = 1;
    private static final int FIRST_MESSAGE_ID = 100;
    private static final int MAX_QUEUE = 50;
    private static final int MAX_PREVIEW_CHARS = 400;
    private static final int RECONNECT_MIN_MS = 2000;
    private static final int RECONNECT_MAX_MS = 15000;
    private static final String WS_PATH = "/ws/webim";

    private final AtomicInteger notificationId = new AtomicInteger(FIRST_MESSAGE_ID);
    private Worker worker;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String host = prefs.getString(KEY_HOST, "");
        String chatId = prefs.getString(KEY_CHAT_ID, "");

        // startForegroundService() requires startForeground() within seconds, even if we stop right after.
        createChannels();
        startForeground(ONGOING_ID, ongoingNotification(host),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        if (!prefs.getBoolean(KEY_ENABLED, false) || host.isEmpty() || chatId.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (worker == null || !worker.matches(host, chatId)) {
            if (worker != null) {
                worker.shutdown();
            }
            worker = new Worker(host, chatId);
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (worker != null) {
            worker.shutdown();
            worker = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** Returns and clears the replies received while the UI was not visible (JSON array). */
    static synchronized String drainQueue(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, MODE_PRIVATE);
        String queued = prefs.getString(KEY_QUEUE, "[]");
        prefs.edit().remove(KEY_QUEUE).apply();
        return queued;
    }

    private static synchronized void pushQueue(Context ctx, String text, JSONArray links) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, MODE_PRIVATE);
        try {
            JSONArray queue = new JSONArray(prefs.getString(KEY_QUEUE, "[]"));
            JSONObject item = new JSONObject();
            item.put("text", text);
            item.put("links", links == null ? new JSONArray() : links);
            item.put("ts", System.currentTimeMillis());
            queue.put(item);
            JSONArray trimmed = new JSONArray();
            for (int i = Math.max(0, queue.length() - MAX_QUEUE); i < queue.length(); i++) {
                trimmed.put(queue.get(i));
            }
            prefs.edit().putString(KEY_QUEUE, trimmed.toString()).apply();
        } catch (JSONException ignored) {
            // a corrupt queue is simply restarted
            prefs.edit().remove(KEY_QUEUE).apply();
        }
    }

    private void createChannels() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel service = new NotificationChannel(CHANNEL_SERVICE,
                getString(R.string.channel_service), NotificationManager.IMPORTANCE_MIN);
        NotificationChannel messages = new NotificationChannel(CHANNEL_MESSAGES,
                getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH);
        nm.createNotificationChannel(service);
        nm.createNotificationChannel(messages);
    }

    private PendingIntent openAppIntent() {
        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification ongoingNotification(String host) {
        return new Notification.Builder(this, CHANNEL_SERVICE)
                .setSmallIcon(R.drawable.ic_stat_claw)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.service_running, host))
                .setContentIntent(openAppIntent())
                .setOngoing(true)
                .build();
    }

    private void notifyReply(String text) {
        String preview = text.length() > MAX_PREVIEW_CHARS
                ? text.substring(0, MAX_PREVIEW_CHARS) + "…" : text;
        Notification n = new Notification.Builder(this, CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.ic_stat_claw)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(preview)
                .setStyle(new Notification.BigTextStyle().bigText(preview))
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(notificationId.incrementAndGet(), n);
    }

    /** Owns one reconnect loop for a (host, chatId) pair. */
    private final class Worker extends Thread {
        private final String host;
        private final String chatId;
        private volatile boolean stopped;
        private volatile MiniWebSocket socket;

        Worker(String host, String chatId) {
            super("chat-service-worker");
            setDaemon(true);
            this.host = host;
            this.chatId = chatId;
        }

        boolean matches(String otherHost, String otherChat) {
            return !stopped && host.equals(otherHost) && chatId.equals(otherChat);
        }

        void shutdown() {
            stopped = true;
            MiniWebSocket s = socket;
            if (s != null) {
                s.close();
            }
            interrupt();
        }

        @Override
        public void run() {
            int backoff = RECONNECT_MIN_MS;
            while (!stopped) {
                CountDownLatch done = new CountDownLatch(1);
                final int[] opened = {0};
                MiniWebSocket ws = new MiniWebSocket(host, WS_PATH, new MiniWebSocket.Listener() {
                    @Override
                    public void onOpen() {
                        opened[0] = 1;
                        MiniWebSocket s = socket;
                        if (s != null) {
                            try {
                                s.sendText("{\"type\":\"hello\",\"chat_id\":\"" + chatId + "\"}");
                            } catch (java.io.IOException ignored) {
                                // onClosed will follow
                            }
                        }
                    }

                    @Override
                    public void onText(String text) {
                        handle(text);
                    }

                    @Override
                    public void onClosed(String reason) {
                        done.countDown();
                    }
                });
                socket = ws;
                ws.start();
                try {
                    done.await();
                    if (opened[0] == 1) {
                        backoff = RECONNECT_MIN_MS;
                    }
                    ws.close();
                    if (!stopped) {
                        Thread.sleep(backoff);
                    }
                } catch (InterruptedException e) {
                    ws.close();
                    return;
                }
                backoff = Math.min(backoff * 3 / 2, RECONNECT_MAX_MS);
            }
        }

        private void handle(String raw) {
            // While the UI is visible it owns the chat socket and shows replies itself.
            if (MainActivity.visible) {
                return;
            }
            try {
                JSONObject o = new JSONObject(raw);
                if (!chatId.equals(o.optString("chat_id")) || !"assistant".equals(o.optString("role"))) {
                    return;
                }
                String text = o.optString("text");
                pushQueue(ChatService.this, text, o.optJSONArray("links"));
                notifyReply(text.isEmpty() ? getString(R.string.new_message) : text);
            } catch (JSONException ignored) {
                // not a chat message
            }
        }
    }
}
