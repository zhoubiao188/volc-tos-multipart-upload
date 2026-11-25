package com.example.volctos.tos;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一个只实现 TOS Multipart Upload 关键接口的本地 mock 服务，用来把「分片直传」跑通并可复现。
 *
 * 真实环境把 endpoint 换成 <bucket>.tos-cn-beijing.volces.com，把签名换成 ve-tos-java-sdk 即可，
 * 请求路径 / 参数 / ETag 语义完全一致：
 *
 *   POST /{bucket}/{key}?uploads                      CreateMultipartUpload
 *   PUT  /{bucket}/{key}?partNumber=1&uploadId=xxx    UploadPart
 *   GET  /{bucket}/{key}?uploadId=xxx                 ListParts（断点续传要靠它）
 *   POST /{bucket}/{key}?uploadId=xxx                 CompleteMultipartUpload
 *   DELETE /{bucket}/{key}?uploadId=xxx               AbortMultipartUpload
 *
 * 另外两个"线上才有的"东西也模拟了：
 *   1. 单连接带宽上限（throttleBytesPerSec）：公网单 TCP 流的吞吐天花板；
 *   2. 严格验签：AK/SK 不对直接 403，避免"本地随便通、上线 403"。
 */
public class TosMockServer {

    public static final String AK = "AKLTdemo0000000000000000000000";
    public static final String SK = "ZGVtby1zZWNyZXQta2V5LWZvci10b3MtbW9jaw==";

    private final HttpServer http;
    private final Path dataDir;
    private final long throttleBytesPerSec;
    private final Map<String, Upload> uploads = new ConcurrentHashMap<>();

    public final AtomicLong putParts = new AtomicLong();
    public final AtomicLong putBytes = new AtomicLong();
    public final AtomicLong forbidden = new AtomicLong();

    static final class PartMeta {
        long size;
        String etag;
    }

    static final class Upload {
        final String bucket, key, id;
        final Path dir;
        final Map<Integer, PartMeta> parts = new ConcurrentHashMap<>();

        Upload(String bucket, String key, String id, Path dir) {
            this.bucket = bucket;
            this.key = key;
            this.id = id;
            this.dir = dir;
        }
    }

    public TosMockServer(int port, Path dataDir, long throttleBytesPerSec) throws IOException {
        this.dataDir = dataDir;
        this.throttleBytesPerSec = throttleBytesPerSec;
        Files.createDirectories(dataDir);
        this.http = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 200);
        this.http.setExecutor(Executors.newFixedThreadPool(32));
        this.http.createContext("/", this::handle);
    }

    public void start() {
        http.start();
    }

    public void stop() {
        http.stop(0);
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/xml");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private void handle(HttpExchange ex) {
        String method = ex.getRequestMethod();
        String rawPath = ex.getRequestURI().getRawPath();
        String rawQuery = ex.getRequestURI().getRawQuery();
        Map<String, String> q = TosSigner.parseQuery(rawQuery);
        String host = ex.getRequestHeaders().getFirst("Host");
        String date = ex.getRequestHeaders().getFirst("x-tos-date");
        String auth = ex.getRequestHeaders().getFirst("Authorization");

        try {
            // 鉴权：预签名 URL 走 query 验签，其余走 header 验签
            boolean ok;
            if (q.containsKey("X-Tos-Signature")) {
                ok = TosSigner.verifyPresigned(SK, method, rawPath, q, host);
            } else {
                ok = TosSigner.verifyHeader(AK, SK, method, rawPath, q, host, date, auth);
            }
            if (!ok) {
                forbidden.incrementAndGet();
                send(ex, 403, err("SignatureDoesNotMatch", "签名不匹配"));
                return;
            }

            String[] parts = rawPath.substring(1).split("/", 2);
            if (parts.length < 2) {
                send(ex, 400, err("InvalidURI", "缺少 object key"));
                return;
            }
            String bucket = parts[0];
            String key = parts[1];

            if ("POST".equals(method) && q.containsKey("uploads")) {
                initiate(ex, bucket, key);
            } else if ("PUT".equals(method) && q.containsKey("partNumber")) {
                uploadPart(ex, q);
            } else if ("PUT".equals(method)) {
                putObject(ex, bucket, key);
            } else if ("GET".equals(method) && q.containsKey("uploadId")) {
                listParts(ex, q);
            } else if ("POST".equals(method) && q.containsKey("uploadId")) {
                complete(ex, bucket, key, q);
            } else if ("DELETE".equals(method) && q.containsKey("uploadId")) {
                abort(ex, q);
            } else {
                send(ex, 400, err("NotImplemented", "mock 未实现该接口"));
            }
        } catch (IOException e) {
            // 客户端中断（断点续传测试）会走到这里，属预期
            try { ex.close(); } catch (Exception ignored) {}
        } catch (Exception e) {
            try { send(ex, 500, err("InternalError", e.toString())); } catch (Exception ignored) {}
        }
    }

    private void initiate(HttpExchange ex, String bucket, String key) throws IOException {
        String id = UUID.randomUUID().toString().replace("-", "");
        Path dir = dataDir.resolve("uploads").resolve(id);
        Files.createDirectories(dir);
        uploads.put(id, new Upload(bucket, key, id, dir));
        send(ex, 200, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<InitiateMultipartUploadResult><Bucket>" + bucket + "</Bucket><Key>" + key + "</Key>"
                + "<UploadId>" + id + "</UploadId></InitiateMultipartUploadResult>");
    }

    private void uploadPart(HttpExchange ex, Map<String, String> q) throws IOException {
        Upload up = uploads.get(q.get("uploadId"));
        int n = Integer.parseInt(q.get("partNumber"));
        if (up == null) {
            send(ex, 404, err("NoSuchUpload", "uploadId 不存在"));
            return;
        }
        long len = Long.parseLong(ex.getRequestHeaders().getFirst("Content-Length"));
        Path target = up.dir.resolve("part-" + n);
        MessageDigest md5 = md5();
        long written = 0;
        try (InputStream in = new Throttled(ex.getRequestBody(), throttleBytesPerSec);
             OutputStream out = Files.newOutputStream(target)) {
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
                md5.update(buf, 0, r);
                written += r;
            }
        } catch (IOException e) {
            Files.deleteIfExists(target);
            throw e;
        }
        if (written != len) {
            Files.deleteIfExists(target);
            send(ex, 400, err("IncompleteBody", "期望 " + len + " 实际 " + written));
            return;
        }
        String etag = "\"" + hex(md5.digest()) + "\"";
        PartMeta pm = new PartMeta();
        pm.size = written;
        pm.etag = etag;
        up.parts.put(n, pm);
        putParts.incrementAndGet();
        putBytes.addAndGet(written);
        ex.getResponseHeaders().set("ETag", etag);
        ex.sendResponseHeaders(200, -1);
        ex.close();
    }

    /** 老接口用的整对象 PUT（业务服务器中转上传就是打这个）。 */
    private void putObject(HttpExchange ex, String bucket, String key) throws IOException {
        long len = Long.parseLong(ex.getRequestHeaders().getFirst("Content-Length"));
        Path target = dataDir.resolve("objects").resolve(bucket).resolve(key);
        Files.createDirectories(target.getParent());
        long written = 0;
        try (InputStream in = new Throttled(ex.getRequestBody(), throttleBytesPerSec);
             OutputStream out = Files.newOutputStream(target)) {
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
                written += r;
            }
        }
        putBytes.addAndGet(written);
        ex.getResponseHeaders().set("ETag", "\"" + written + "\"");
        ex.sendResponseHeaders(written == len ? 200 : 400, -1);
        ex.close();
    }

    private void listParts(HttpExchange ex, Map<String, String> q) throws IOException {
        Upload up = uploads.get(q.get("uploadId"));
        if (up == null) {
            send(ex, 404, err("NoSuchUpload", "uploadId 不存在"));
            return;
        }
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><ListPartsResult>");
        sb.append("<Bucket>").append(up.bucket).append("</Bucket><Key>").append(up.key).append("</Key>");
        sb.append("<UploadId>").append(up.id).append("</UploadId>");
        new ArrayList<>(up.parts.keySet()).stream().sorted().forEach(n -> {
            PartMeta p = up.parts.get(n);
            sb.append("<Part><PartNumber>").append(n).append("</PartNumber><ETag>")
              .append(p.etag).append("</ETag><Size>").append(p.size).append("</Size></Part>");
        });
        sb.append("</ListPartsResult>");
        send(ex, 200, sb.toString());
    }

    private void complete(HttpExchange ex, String bucket, String key, Map<String, String> q) throws IOException {
        Upload up = uploads.get(q.get("uploadId"));
        if (up == null) {
            send(ex, 404, err("NoSuchUpload", "uploadId 不存在"));
            return;
        }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        List<Integer> order = new ArrayList<>();
        List<String> etags = new ArrayList<>();
        Matcher m = Pattern.compile("<Part>\\s*<PartNumber>(\\d+)</PartNumber>\\s*<ETag>\"?([0-9a-f]+)\"?</ETag>")
                .matcher(body);
        while (m.find()) {
            order.add(Integer.parseInt(m.group(1)));
            etags.add(m.group(2));
        }
        // 1) 先全量校验，缺片/错片直接 400 —— 这就是"分片没传完不能 complete"
        for (int i = 0; i < order.size(); i++) {
            PartMeta p = up.parts.get(order.get(i));
            if (p == null || !p.etag.replace("\"", "").equals(etags.get(i))) {
                send(ex, 400, err("InvalidPart", "第 " + order.get(i) + " 片缺失或 ETag 不一致"));
                return;
            }
        }
        // 2) 再顺序拼接落盘
        Path target = dataDir.resolve("objects").resolve(bucket).resolve(key);
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        long total = 0;
        try (OutputStream out = Files.newOutputStream(tmp)) {
            for (int n : order) {
                total += Files.copy(up.dir.resolve("part-" + n), out);
            }
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        uploads.remove(up.id);
        send(ex, 200, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<CompleteMultipartUploadResult><Bucket>" + bucket + "</Bucket><Key>" + key + "</Key>"
                + "<ETag>\"" + total + "-" + order.size() + "\"</ETag>"
                + "<Size>" + total + "</Size></CompleteMultipartUploadResult>");
    }

    private void abort(HttpExchange ex, Map<String, String> q) throws IOException {
        Upload up = uploads.remove(q.get("uploadId"));
        if (up != null) {
            try (var s = Files.list(up.dir)) {
                s.forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
            }
            Files.deleteIfExists(up.dir);
        }
        ex.sendResponseHeaders(204, -1);
        ex.close();
    }

    private static String err(String code, String msg) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>" + code
                + "</Code><Message>" + msg + "</Message></Error>";
    }

    private static MessageDigest md5() {
        try { return MessageDigest.getInstance("MD5"); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** 用令牌桶的简化版模拟单连接带宽上限：读满 rate 字节就 park 到对应时间点。 */
    static final class Throttled extends FilterInputStream {
        private final long rate;
        private final long start = System.nanoTime();
        private long read;

        Throttled(InputStream in, long rate) {
            super(in);
            this.rate = rate;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                read += n;
                long due = start + (long) (read * 1_000_000_000.0 / rate);
                long sleep = due - System.nanoTime();
                if (sleep > 0) LockSupport.parkNanos(sleep);
            }
            return n;
        }
    }
}
