package com.espclaw.mobile;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Minimal RFC 6455 text WebSocket client (plain ws://, no extensions) so the background
 * service needs no third-party library. One instance = one connection attempt; reconnect
 * logic belongs to the caller. Pure Java on purpose, so it can be tested off-device.
 */
final class MiniWebSocket {
    interface Listener {
        void onOpen();

        void onText(String text);

        void onClosed(String reason);
    }

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int OP_CONTINUATION = 0x0;
    private static final int OP_TEXT = 0x1;
    private static final int OP_CLOSE = 0x8;
    private static final int OP_PING = 0x9;
    private static final int OP_PONG = 0xA;
    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static final int READ_TIMEOUT_MS = 60000;
    private static final int PING_INTERVAL_MS = 20000;
    private static final int MAX_PAYLOAD = 1 << 20;
    private static final int MAX_HEADER_BYTES = 8192;

    private final String host;
    private final int port;
    private final String path;
    private final Listener listener;
    private final SecureRandom random = new SecureRandom();
    private final Object writeLock = new Object();
    private volatile Socket socket;
    private volatile OutputStream out;
    private volatile boolean closed;

    /** @param hostPort "host" or "host:port" (port defaults to 80). */
    MiniWebSocket(String hostPort, String path, Listener listener) {
        int colon = hostPort.lastIndexOf(':');
        if (colon > 0) {
            this.host = hostPort.substring(0, colon);
            this.port = Integer.parseInt(hostPort.substring(colon + 1));
        } else {
            this.host = hostPort;
            this.port = 80;
        }
        this.path = path;
        this.listener = listener;
    }

    void start() {
        Thread t = new Thread(this::run, "mini-ws");
        t.setDaemon(true);
        t.start();
    }

    void sendText(String text) throws IOException {
        sendFrame(OP_TEXT, text.getBytes(StandardCharsets.UTF_8));
    }

    void close() {
        closed = true;
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
    }

    private void run() {
        String reason = "closed";
        try {
            Socket s = new Socket();
            socket = s;
            s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            s.setTcpNoDelay(true);
            s.setSoTimeout(READ_TIMEOUT_MS);
            out = s.getOutputStream();
            InputStream in = new BufferedInputStream(s.getInputStream());

            handshake(in);
            if (closed) {
                return;
            }
            listener.onOpen();
            startPinger(s);
            readLoop(in);
        } catch (IOException | RuntimeException e) {
            reason = String.valueOf(e.getMessage());
        } finally {
            closed = true;
            try {
                if (socket != null) {
                    socket.close();
                }
            } catch (IOException ignored) {
                // nothing to do
            }
            listener.onClosed(reason);
        }
    }

    private void handshake(InputStream in) throws IOException {
        byte[] keyBytes = new byte[16];
        random.nextBytes(keyBytes);
        String key = Base64.getEncoder().encodeToString(keyBytes);
        String req = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + ":" + port + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        synchronized (writeLock) {
            out.write(req.getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        StringBuilder head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("handshake: connection closed");
            }
            head.append((char) b);
            if (head.length() > MAX_HEADER_BYTES) {
                throw new IOException("handshake: header too large");
            }
        }
        String response = head.toString();
        if (!response.startsWith("HTTP/1.1 101")) {
            throw new IOException("handshake failed: " + response.split("\r\n", 2)[0]);
        }
        String expected = accept(key);
        for (String line : response.split("\r\n")) {
            if (line.toLowerCase().startsWith("sec-websocket-accept:")
                    && !line.substring(line.indexOf(':') + 1).trim().equals(expected)) {
                throw new IOException("handshake: bad Sec-WebSocket-Accept");
            }
        }
    }

    private static String accept(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((key + GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void startPinger(Socket s) {
        Thread t = new Thread(() -> {
            while (!closed && socket == s) {
                try {
                    Thread.sleep(PING_INTERVAL_MS);
                    sendFrame(OP_PING, new byte[0]);
                } catch (InterruptedException | IOException e) {
                    return;
                }
            }
        }, "mini-ws-ping");
        t.setDaemon(true);
        t.start();
    }

    private void readLoop(InputStream in) throws IOException {
        ByteArrayOutputStream fragments = new ByteArrayOutputStream();
        boolean inText = false;
        while (!closed) {
            int b0 = readByte(in);
            int b1 = readByte(in);
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7F;
            if (len == 126) {
                len = (readByte(in) << 8) | readByte(in);
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) {
                    len = (len << 8) | readByte(in);
                }
            }
            if (len < 0 || len > MAX_PAYLOAD) {
                throw new IOException("frame too large: " + len);
            }
            byte[] mask = null;
            if (masked) {
                mask = new byte[4];
                readFully(in, mask);
            }
            byte[] payload = new byte[(int) len];
            readFully(in, payload);
            if (mask != null) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= mask[i & 3];
                }
            }

            switch (opcode) {
                case OP_TEXT:
                case OP_CONTINUATION:
                    if (opcode == OP_TEXT) {
                        fragments.reset();
                        inText = true;
                    }
                    if (inText) {
                        fragments.write(payload);
                        if (fin) {
                            listener.onText(new String(fragments.toByteArray(), StandardCharsets.UTF_8));
                            fragments.reset();
                            inText = false;
                        }
                    }
                    break;
                case OP_PING:
                    sendFrame(OP_PONG, payload);
                    break;
                case OP_CLOSE:
                    try {
                        sendFrame(OP_CLOSE, new byte[0]);
                    } catch (IOException ignored) {
                        // peer already gone
                    }
                    return;
                default:
                    // pong / binary / unknown: ignored
                    break;
            }
        }
    }

    private void sendFrame(int opcode, byte[] payload) throws IOException {
        OutputStream o = out;
        if (o == null) {
            throw new IOException("not connected");
        }
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        ByteArrayOutputStream frame = new ByteArrayOutputStream(payload.length + 14);
        frame.write(0x80 | opcode);
        if (payload.length < 126) {
            frame.write(0x80 | payload.length);
        } else if (payload.length <= 0xFFFF) {
            frame.write(0x80 | 126);
            frame.write(payload.length >> 8);
            frame.write(payload.length & 0xFF);
        } else {
            frame.write(0x80 | 127);
            for (int shift = 56; shift >= 0; shift -= 8) {
                frame.write((int) (((long) payload.length >> shift) & 0xFF));
            }
        }
        frame.write(mask);
        for (int i = 0; i < payload.length; i++) {
            frame.write(payload[i] ^ mask[i & 3]);
        }
        synchronized (writeLock) {
            o.write(frame.toByteArray());
            o.flush();
        }
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new EOFException("connection closed");
        }
        return b;
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                throw new EOFException("connection closed");
            }
            off += n;
        }
    }
}
