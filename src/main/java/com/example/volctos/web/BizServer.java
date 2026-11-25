package com.example.volctos.web;

import com.example.volctos.tos.TosMockServer;
import com.example.volctos.tos.TosSigner;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 业务侧的三个接口：
 *
 *  POST /api/upload/relay?mode=buffer|stream   老接口：文件先进业务服务器，再由服务器单流转发到 TOS
 *  POST /api/upload/ticket?size=&parts=        新接口：服务端只签发 uploadId + 分片预签名 URL（微秒级）
 *  POST /api/upload/complete                   收片完成后调 CompleteMultipartUpload
 *  GET  /api/upload/parts?uploadId=            断点续传：先问 TOS 哪些片已经传完
 *
 * 注意 relay 两个 mode 的差别只有一行：整个文件进不进堆。
 */
public class BizServer {

    private final HttpServer http;
    private final String tosEndpoint;      // 127.0.0.1:9090
    private final String bucket;
    private final TosMockServer tos;

    public final AtomicLong relayCount = new AtomicLong();
    public final AtomicLong ticketCount = new AtomicLong();
    public final AtomicLong completeCount = new AtomicLong();
    public final AtomicLong oomCount = new AtomicLong();

    public BizServer(int port, String tosEndpoint, String bucket, TosMockServer tos) throws IOException {
        this.tosEndpoint = tosEndpoint;
        this.bucket = bucket;
        this.tos = tos;
        this.http = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 200);
        this.http.setExecutor(Executors.newFixedThreadPool(16));
        this.http.createContext("/api/upload/relay", this::relay);
        this.http.createContext("/api/upload/ticket", this::ticket);
        this.http.createContext("/api/upload/complete", this::complete);
        this.http.createContext("/api/upload/parts", this::parts);
        this.http.createContext("/metrics", this::metrics);
    }

    public void start() { http.start(); }
    public void stop() { http.stop(0); }

    // ---------------------------------------------------------------- relay

    private void relay(HttpExchange ex) throws IOException {
        long t0 = System.nanoTime();
        relayCount.incrementAndGet();
        String mode = TosSigner.parseQuery(ex.getRequestURI().getRawQuery()).getOrDefault("mode", "buffer");
        long len = Long.parseLong(ex.getRequestHeaders().getFirst("Content-Length"));
        String key = "relay/" + System.currentTimeMillis() + ".bin";
        HeapSampler sampler = HeapSampler.start();

        HttpURLConnection conn = null;
        long sent = 0;
        int code;
        String err = null;
        try {
            URL url = new URL("http://" + tosEndpoint + "/" + bucket + "/" + key);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("PUT");
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(len);
            Map<String, String> q = new LinkedHashMap<>();
            String date = TosSigner.nowDate();
            conn.setRequestProperty("Host", tosEndpoint);
            conn.setRequestProperty("x-tos-date", date);
            conn.setRequestProperty("Authorization", TosSigner.authorization(
                    TosMockServer.AK, TosMockServer.SK, "PUT", "/" + bucket + "/" + key, q, tosEndpoint, date));

            if ("buffer".equals(mode)) {
                // 老代码就是这样写的：先把整个文件读进内存，再一把推给对象存储
                byte[] all = ex.getRequestBody().readAllBytes();
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(all);
                }
                sent = all.length;
            } else {
                try (InputStream in = ex.getRequestBody(); OutputStream os = conn.getOutputStream()) {
                    byte[] buf = new byte[64 * 1024];
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        os.write(buf, 0, r);
                        sent += r;
                    }
                }
            }
            code = conn.getResponseCode();
        } catch (OutOfMemoryError oom) {
            oomCount.incrementAndGet();
            code = 500;
            err = "java.lang.OutOfMemoryError: Java heap space（整个文件进了堆）";
            System.err.printf("[relay] OutOfMemoryError: Java heap space —— Content-Length=%,d 字节，"
                    + "堆上限 %d MB%n", len, Runtime.getRuntime().maxMemory() / 1048576);
            if (conn != null) conn.disconnect();
            // 堆已经炸了，把剩余请求体丢掉再回 500，否则客户端只会看到连接被重置
            try {
                ex.getRequestBody().transferTo(OutputStream.nullOutputStream());
            } catch (Exception ignored) {}
        } catch (IOException ioe) {
            code = 502;
            err = ioe.toString();
        } finally {
            sampler.stop();
        }

        long ms = (System.nanoTime() - t0) / 1_000_000;
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("api", "/api/upload/relay");
        json.put("mode", mode);
        json.put("httpStatus", code);
        json.put("bytes", sent);
        json.put("ms", ms);
        json.put("mbPerSec", sent > 0 ? Math.round(sent / 1048576.0 / (ms / 1000.0) * 10) / 10.0 : 0);
        json.put("peakHeapMB", sampler.peakMB());
        json.put("heapMaxMB", Runtime.getRuntime().maxMemory() / 1048576);
        if (err != null) json.put("error", err);
        sendJson(ex, code == 200 ? 200 : 500, Json.write(json));
    }

    // --------------------------------------------------------------- ticket

    private void ticket(HttpExchange ex) throws IOException {
        long t0 = System.nanoTime();
        ticketCount.incrementAndGet();
        Map<String, String> q = TosSigner.parseQuery(ex.getRequestURI().getRawQuery());
        long size = Long.parseLong(q.getOrDefault("size", "0"));
        int parts = Integer.parseInt(q.getOrDefault("parts", "8"));
        String key = q.getOrDefault("key", "direct/" + System.currentTimeMillis() + ".bin");

        // 1) CreateMultipartUpload
        Map<String, String> iq = new LinkedHashMap<>();
        iq.put("uploads", "");
        String date = TosSigner.nowDate();
        Resp r = call("POST", "/" + bucket + "/" + key, iq, date, new byte[0]);
        String uploadId = between(r.body, "<UploadId>", "</UploadId>");
        if (uploadId == null || uploadId.isEmpty()) {
            sendJson(ex, 500, "{\"error\":\"CreateMultipartUpload failed: " + r.code + "\"}");
            return;
        }

        // 2) 按 1MB 对齐切分，再为每一片签一个预签名 URL（服务端不碰文件内容）
        long partSize = Math.max(1024 * 1024, ((size + parts - 1) / parts + 1048575) / 1048576 * 1048576);
        int partCount = (int) ((size + partSize - 1) / partSize);
        List<String> urls = new ArrayList<>();
        for (int n = 1; n <= partCount; n++) {
            Map<String, String> pq = new LinkedHashMap<>();
            pq.put("partNumber", String.valueOf(n));
            pq.put("uploadId", uploadId);
            pq.put("X-Tos-Date", date);
            String sig = TosSigner.signature("PUT", "/" + bucket + "/" + key, pq, tosEndpoint, date,
                    TosMockServer.SK, true);
            pq.put("X-Tos-Signature", sig);
            urls.add("http://" + tosEndpoint + "/" + bucket + "/" + key + "?" + TosSigner.canonicalQuery(pq));
        }

        long ms = (System.nanoTime() - t0) / 1_000_000;
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("uploadId", uploadId);
        json.put("key", key);
        json.put("bucket", bucket);
        json.put("size", size);
        json.put("partSize", partSize);
        json.put("partCount", partCount);
        json.put("signMs", ms);
        json.put("urls", urls);
        sendJson(ex, 200, Json.write(json));
    }

    private void complete(HttpExchange ex) throws IOException {
        Map<String, String> q = TosSigner.parseQuery(ex.getRequestURI().getRawQuery());
        String key = q.get("key");
        String uploadId = q.get("uploadId");
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> cq = new LinkedHashMap<>();
        cq.put("uploadId", uploadId);
        Resp r = call("POST", "/" + bucket + "/" + key, cq, TosSigner.nowDate(),
                body.getBytes(StandardCharsets.UTF_8));
        completeCount.incrementAndGet();
        // 原样透传 TOS 的状态码：缺片/ETag 不一致是 400 InvalidPart，不要一律糊成 500
        sendJson(ex, r.code, r.body);
    }

    private void parts(HttpExchange ex) throws IOException {
        Map<String, String> q = TosSigner.parseQuery(ex.getRequestURI().getRawQuery());
        Map<String, String> lq = new LinkedHashMap<>();
        lq.put("uploadId", q.get("uploadId"));
        Resp r = call("GET", "/" + bucket + "/" + q.get("key"), lq, TosSigner.nowDate(), new byte[0]);
        sendJson(ex, r.code == 200 ? 200 : 500, r.body);
    }

    private void metrics(HttpExchange ex) throws IOException {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("relayCount", relayCount.get());
        json.put("ticketCount", ticketCount.get());
        json.put("completeCount", completeCount.get());
        json.put("relayOom", oomCount.get());
        json.put("tosPutParts", tos.putParts.get());
        json.put("tosPutBytes", tos.putBytes.get());
        json.put("tosForbidden", tos.forbidden.get());
        json.put("heapMaxMB", Runtime.getRuntime().maxMemory() / 1048576);
        json.put("heapUsedMB",
                (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576);
        sendJson(ex, 200, Json.write(json));
    }

    // --------------------------------------------------------------- helper

    record Resp(int code, String body) {}

    private Resp call(String method, String path, Map<String, String> q, String date, byte[] body)
            throws IOException {
        String qs = TosSigner.canonicalQuery(q);
        URL url = new URL("http://" + tosEndpoint + path + (qs.isEmpty() ? "" : "?" + qs));
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod(method);
        c.setRequestProperty("Host", tosEndpoint);
        c.setRequestProperty("x-tos-date", date);
        c.setRequestProperty("Authorization", TosSigner.authorization(
                TosMockServer.AK, TosMockServer.SK, method, path, q, tosEndpoint, date));
        if (body.length > 0 || "POST".equals(method)) {
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body);
            }
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        return new Resp(code, text);
    }

    private static String between(String s, String a, String b) {
        if (s == null) return null;
        int i = s.indexOf(a);
        if (i < 0) return null;
        int j = s.indexOf(b, i + a.length());
        if (j < 0) return null;
        return s.substring(i + a.length(), j);
    }

    private static void sendJson(HttpExchange ex, int code, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    /** 每 10ms 采一次堆使用量，只为了给出 peakHeapMB 这一列。 */
    static final class HeapSampler {
        private final Thread t;
        private final AtomicLong peak = new AtomicLong();
        private final AtomicBoolean running = new AtomicBoolean(true);

        private HeapSampler() {
            t = new Thread(() -> {
                var mx = ManagementFactory.getMemoryMXBean();
                while (running.get()) {
                    long used = mx.getHeapMemoryUsage().getUsed();
                    peak.accumulateAndGet(used, Math::max);
                    try { Thread.sleep(10); } catch (InterruptedException e) { return; }
                }
            }, "heap-sampler");
            t.setDaemon(true);
        }

        static HeapSampler start() {
            HeapSampler s = new HeapSampler();
            s.t.start();
            return s;
        }

        void stop() {
            running.set(false);
            t.interrupt();
        }

        long peakMB() { return peak.get() / 1048576; }
    }
}
