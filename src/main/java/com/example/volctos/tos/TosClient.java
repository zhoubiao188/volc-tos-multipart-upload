package com.example.volctos.tos;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 端上的分片直传客户端（浏览器/APP/CLI 都长这样）：
 *   1. 向业务服务要 ticket（uploadId + 每片的预签名 URL）；
 *   2. 自己开 N 个连接把分片直接推到 TOS，业务服务器全程不碰文件；
 *   3. 全部成功后调 complete。
 *
 * 断点续传：进程重启后先 ListParts，只补没传的片。
 */
public class TosClient {

    private final String appEndpoint;

    public TosClient(String appEndpoint) {
        this.appEndpoint = appEndpoint;
    }

    public static final class Ticket {
        public String uploadId, key;
        public long size, partSize;
        public int partCount;
        public long signMs;
        public List<String> urls = new ArrayList<>();
    }

    public Ticket init(long size, int parts) throws IOException {
        String json = get("http://" + appEndpoint + "/api/upload/ticket?size=" + size + "&parts=" + parts);
        Ticket t = new Ticket();
        t.uploadId = field(json, "uploadId");
        t.key = field(json, "key");
        t.size = Long.parseLong(field(json, "size"));
        t.partSize = Long.parseLong(field(json, "partSize"));
        t.partCount = Integer.parseInt(field(json, "partCount"));
        t.signMs = Long.parseLong(field(json, "signMs"));
        Matcher m = Pattern.compile("\"urls\":\\[(.*?)]").matcher(json);
        if (m.find()) {
            for (String u : m.group(1).split("\",\"")) {
                t.urls.add(u.replace("\"", ""));
            }
        }
        return t;
    }

    /** 并发上传分片；skip 里的分片号跳过（断点续传用）。返回实际新传字节数。 */
    public Result uploadParts(Ticket t, Path file, int concurrency, Set<Integer> skip) throws Exception {
        AtomicLong bytes = new AtomicLong();
        AtomicLong partsDone = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        List<Future<?>> futures = new ArrayList<>();
        long t0 = System.nanoTime();
        try {
            for (int i = 0; i < t.partCount; i++) {
                int n = i + 1;
                if (skip != null && skip.contains(n)) continue;
                long offset = (long) i * t.partSize;
                long len = Math.min(t.partSize, t.size - offset);
                String url = t.urls.get(i);
                futures.add(pool.submit((Callable<Void>) () -> {
                    putPart(url, file, offset, len);
                    bytes.addAndGet(len);
                    partsDone.incrementAndGet();
                    return null;
                }));
            }
            for (Future<?> f : futures) f.get();
        } finally {
            pool.shutdown();
        }
        return new Result((System.nanoTime() - t0) / 1_000_000, bytes.get(), partsDone.get());
    }

    private void putPart(String url, Path file, long offset, long len) throws IOException {
        int attempts = 0;
        while (true) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestMethod("PUT");
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(len);
                c.setConnectTimeout(5000);
                c.setReadTimeout(120_000);
                try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ);
                     OutputStream os = c.getOutputStream()) {
                    ByteBuffer buf = ByteBuffer.allocate(1024 * 1024);
                    long pos = offset, remain = len;
                    while (remain > 0) {
                        buf.clear();
                        buf.limit((int) Math.min(buf.capacity(), remain));
                        int r = ch.read(buf, pos);
                        if (r <= 0) throw new IOException("文件被截断");
                        os.write(buf.array(), 0, r);
                        pos += r;
                        remain -= r;
                    }
                }
                int code = c.getResponseCode();
                if (code != 200) {
                    throw new IOException("UploadPart HTTP " + code + " " + new String(
                            c.getErrorStream() == null ? new byte[0] : c.getErrorStream().readAllBytes(),
                            StandardCharsets.UTF_8));
                }
                return;
            } catch (IOException e) {
                if (++attempts >= 3) throw e;
            }
        }
    }

    /** ETag 正常由 UploadPart 响应头带回；真实 SDK 会自己收集，这里统一从 ListParts 取权威值。 */
    public String complete(Ticket t) throws IOException {
        List<PartInfo> parts = listParts(t);
        if (parts.size() != t.partCount) {
            throw new IOException("还差 " + (t.partCount - parts.size()) + " 片没传完，不能 complete");
        }
        StringBuilder xml = new StringBuilder("<CompleteMultipartUpload>");
        for (PartInfo p : parts) {
            xml.append("<Part><PartNumber>").append(p.number).append("</PartNumber><ETag>\"")
               .append(p.etag).append("\"</ETag></Part>");
        }
        xml.append("</CompleteMultipartUpload>");

        HttpURLConnection c = (HttpURLConnection) new URL("http://" + appEndpoint
                + "/api/upload/complete?key=" + t.key + "&uploadId=" + t.uploadId).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        byte[] body = xml.toString().getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(body.length);
        try (OutputStream os = c.getOutputStream()) {
            os.write(body);
        }
        int code = c.getResponseCode();
        // 非 200 时 getInputStream 会直接抛，必须先判码再取流
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String resp = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        if (code != 200) throw new IOException("Complete failed: HTTP " + code + " " + resp);
        return resp;
    }

    public record PartInfo(int number, long size, String etag) {}

    public List<PartInfo> listParts(Ticket t) throws IOException {
        String xml = get("http://" + appEndpoint + "/api/upload/parts?uploadId=" + t.uploadId + "&key=" + t.key);
        List<PartInfo> out = new ArrayList<>();
        Matcher m = Pattern.compile(
                "<Part><PartNumber>(\\d+)</PartNumber><ETag>\"?([0-9a-f]+)\"?</ETag><Size>(\\d+)</Size></Part>")
                .matcher(xml);
        while (m.find()) {
            out.add(new PartInfo(Integer.parseInt(m.group(1)), Long.parseLong(m.group(3)), m.group(2)));
        }
        out.sort((a, b) -> Integer.compare(a.number, b.number));
        return out;
    }

    public record Result(long ms, long bytes, long parts) {}

    private String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(30_000);
        return new String(c.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":\"?([^\",}]+)\"?").matcher(json);
        if (!m.find()) throw new IllegalStateException("字段缺失: " + name + " in " + json);
        return m.group(1);
    }

    /** 小工具：把 ListParts 结果里的分片号抽出来给 skip 用。 */
    public static Set<Integer> uploadedNumbers(List<PartInfo> parts) {
        Set<Integer> s = new TreeSet<>();
        for (PartInfo p : parts) s.add(p.number());
        return s;
    }

    @SuppressWarnings("unused")
    private static Map<String, String> q(String k, String v) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }
}
