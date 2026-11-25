package com.example.volctos.bench;

import com.example.volctos.tos.TosClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Set;
import java.util.TreeSet;

/**
 * 压测入口（客户端视角，跟真实浏览器/APP 一样只走 HTTP）：
 *
 *   gen    <file> <sizeMB>                       生成测试文件
 *   relay  <file> buffer|stream                  走老接口：业务服务器中转
 *   direct <file> <parts> <concurrency>          走新接口：分片直传
 *   resume <file> <parts> <concurrency> <stopAt> 传到第 stopAt 片后"断网"，再续传
 *   sha    <file>                                打印 sha256，用于校验对象完整性
 */
public class Bench {

    private static final String APP = System.getProperty("app", "127.0.0.1:8080");

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        switch (mode) {
            case "gen" -> gen(Path.of(args[1]), Long.parseLong(args[2]));
            case "relay" -> relay(Path.of(args[1]), args[2]);
            case "direct" -> direct(Path.of(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]));
            case "resume" -> resume(Path.of(args[1]), Integer.parseInt(args[2]),
                    Integer.parseInt(args[3]), Integer.parseInt(args[4]));
            case "sha" -> System.out.println(sha256(Path.of(args[1])));
            default -> throw new IllegalArgumentException("未知模式: " + mode);
        }
    }

    // ------------------------------------------------------------------ gen

    private static void gen(Path file, long mb) throws IOException {
        long total = mb * 1024 * 1024;
        byte[] block = new byte[1024 * 1024];
        new java.util.Random(42).nextBytes(block);
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            long written = 0;
            while (written < total) {
                int len = (int) Math.min(block.length, total - written);
                ByteBuffer buf = ByteBuffer.wrap(block, 0, len);
                while (buf.hasRemaining()) ch.write(buf);
                written += len;
            }
        }
        System.out.printf("生成 %s  %,d 字节 (%d MB)%n", file, total, mb);
    }

    // ---------------------------------------------------------------- relay

    private static void relay(Path file, String mode) throws IOException {
        long size = Files.size(file);
        long t0 = System.nanoTime();
        HttpURLConnection c = (HttpURLConnection) new URL(
                "http://" + APP + "/api/upload/relay?mode=" + mode).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(size);
        c.setConnectTimeout(5000);
        c.setReadTimeout(300_000);
        String writeErr = null;
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ);
             OutputStream os = c.getOutputStream()) {
            ByteBuffer buf = ByteBuffer.allocate(1024 * 1024);
            long pos = 0;
            while (pos < size) {
                buf.clear();
                int r = ch.read(buf, pos);
                if (r <= 0) break;
                os.write(buf.array(), 0, r);
                pos += r;
            }
        } catch (IOException e) {
            // 服务端 OOM 后直接把连接掐掉，客户端写到一半就断了 —— 线上用户看到的就是这个
            writeErr = e.getMessage();
        }

        int code;
        String resp;
        try {
            code = c.getResponseCode();
            resp = new String((code >= 400 ? c.getErrorStream() : c.getInputStream()).readAllBytes(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            code = -1;
            resp = "连接已被服务端关闭（" + e.getMessage() + "）";
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        if (writeErr != null) System.out.printf("[relay/%s] 客户端写到一半失败：%s%n", mode, writeErr);
        System.out.printf("[relay/%s] 客户端耗时 %d ms, HTTP %d%n  服务端回报: %s%n", mode, ms, code, resp);
    }

    // --------------------------------------------------------------- direct

    private static void direct(Path file, int parts, int concurrency) throws Exception {
        long size = Files.size(file);
        TosClient client = new TosClient(APP);
        long t0 = System.nanoTime();
        TosClient.Ticket t = client.init(size, parts);
        long tTicket = (System.nanoTime() - t0) / 1_000_000;
        TosClient.Result r = client.uploadParts(t, file, concurrency, null);
        long tAfterParts = (System.nanoTime() - t0) / 1_000_000;
        client.complete(t);
        long total = (System.nanoTime() - t0) / 1_000_000;

        System.out.printf("[direct] 文件 %,d 字节 / %d 片 × %,d 字节 / 并发 %d%n",
                size, t.partCount, t.partSize, concurrency);
        System.out.printf("[direct] ticket(签名) %d ms | 分片上传 %d ms | complete(含 ListParts) %d ms | 总计 %d ms%n",
                tTicket, r.ms(), (tAfterParts - r.ms() - tTicket), total);
        System.out.printf("[direct] 有效吞吐 %.1f MB/s（%,d 字节 / %.2f s）%n",
                size / 1048576.0 / (r.ms() / 1000.0), r.bytes(), r.ms() / 1000.0);
        System.out.printf("[direct] key=%s uploadId=%s%n", t.key, t.uploadId);
    }

    // --------------------------------------------------------------- resume

    private static void resume(Path file, int parts, int concurrency, int stopAt) throws Exception {
        long size = Files.size(file);
        TosClient client = new TosClient(APP);

        // 第一段：只传前 stopAt 片（后面的片号进 skip），然后"拔网线"
        TosClient.Ticket t = client.init(size, parts);
        Set<Integer> later = new TreeSet<>();
        for (int n = stopAt + 1; n <= t.partCount; n++) later.add(n);
        TosClient.Result r1 = client.uploadParts(t, file, concurrency, later);
        System.out.printf("[resume] 第一次只传前 %d/%d 片：%,d 字节 / %d ms —— 进程被 kill%n",
                stopAt, t.partCount, r1.bytes(), r1.ms());

        // 第二段：新进程重新 `resume`，先问 TOS 已经到哪了
        long t0 = System.nanoTime();
        Set<Integer> done = TosClient.uploadedNumbers(client.listParts(t));
        System.out.printf("[resume] ListParts 查到已传 %d 片：%s%n", done.size(), done);
        Set<Integer> remain = new TreeSet<>();
        long expectBytes = 0;
        for (int n = 1; n <= t.partCount; n++) {
            if (!done.contains(n)) {
                remain.add(n);
                long offset = (long) (n - 1) * t.partSize;
                expectBytes += Math.min(t.partSize, size - offset);
            }
        }
        TosClient.Result r2 = client.uploadParts(t, file, concurrency, done);
        long listMs = (System.nanoTime() - t0) / 1_000_000 - r2.ms();
        client.complete(t);
        System.out.printf("[resume] 续传 %d 片 / %,d 字节 / %d ms（ListParts %d ms）%n",
                remain.size(), r2.bytes(), r2.ms(), listMs);
        System.out.printf("[resume] 重传浪费 %,d 字节（续传只补缺口 %.0f MB，不从头再来）%n",
                Math.max(0, r2.bytes() - expectBytes), expectBytes / 1048576.0);
    }

    // ------------------------------------------------------------------ sha

    private static String sha256(Path file) throws IOException {
        try (var in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1024 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IOException(e);
        }
    }
}
