package com.natestechstuff.jarvis;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A tiny real HTTP/1.1 server on a random localhost port for client tests (the JDK's HttpServer isn't on
 * Android's unit-test classpath). Responses are written in pieces with flushes, Connection: close and no
 * Content-Length, so streaming clients really see a stream.
 */
public class MockHttp implements AutoCloseable {
    public static final class Req {
        public String method, path, body;
        public Map<String, String> headers = new HashMap<>();
    }

    public static final class Resp {
        int code = 200;
        String type = "application/json";
        String[] parts = {""};
        long gapMs = 0;
        public static Resp json(int code, String body) { Resp r = new Resp(); r.code = code; r.parts = new String[]{body}; return r; }
        public static Resp stream(String type, long gapMs, String... parts) { Resp r = new Resp(); r.type = type; r.parts = parts; r.gapMs = gapMs; return r; }
    }

    public interface Handler { Resp handle(Req r) throws Exception; }

    private final ServerSocket server;
    public final String base;
    public final CopyOnWriteArrayList<Req> requests = new CopyOnWriteArrayList<>();
    private volatile Handler handler;

    public MockHttp(Handler h) throws IOException {
        handler = h;
        server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        base = "http://127.0.0.1:" + server.getLocalPort();
        Thread loop = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    Socket s = server.accept();
                    Thread t = new Thread(() -> { try (Socket ss = s) { serve(ss); } catch (Exception ignored) {} });
                    t.setDaemon(true);
                    t.start();
                } catch (IOException ignored) {
                }
            }
        });
        loop.setDaemon(true);
        loop.start();
    }

    public int port() { return server.getLocalPort(); }

    private void serve(Socket s) throws Exception {
        InputStream in = s.getInputStream();
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int c, state = 0;
        while (state < 4 && (c = in.read()) >= 0) {
            head.write(c);
            state = (c == '\r' && (state == 0 || state == 2)) || (c == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
        }
        String[] lines = head.toString("US-ASCII").split("\r\n");
        if (lines.length == 0 || lines[0].isEmpty()) return;
        Req r = new Req();
        r.method = lines[0].split(" ")[0];
        r.path = lines[0].split(" ")[1];
        for (int i = 1; i < lines.length; i++) {
            int k = lines[i].indexOf(':');
            if (k > 0) r.headers.put(lines[i].substring(0, k).trim().toLowerCase(), lines[i].substring(k + 1).trim());
        }
        int len = Integer.parseInt(r.headers.getOrDefault("content-length", "0"));
        byte[] body = new byte[len];
        int got = 0;
        while (got < len) {
            int n = in.read(body, got, len - got);
            if (n < 0) break;
            got += n;
        }
        r.body = new String(body, 0, got, StandardCharsets.UTF_8);
        requests.add(r);
        Resp resp = handler.handle(r);
        OutputStream out = s.getOutputStream();
        out.write(("HTTP/1.1 " + resp.code + " X\r\nContent-Type: " + resp.type + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        for (String p : resp.parts) {
            out.write(p.getBytes(StandardCharsets.UTF_8));
            out.flush();
            if (resp.gapMs > 0) Thread.sleep(resp.gapMs);
        }
    }

    @Override public void close() throws IOException { server.close(); }
}
