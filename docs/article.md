# 200MB 文件一传就 500：改成火山引擎 TOS 分片直传后，10 秒变 1.5 秒

上周有用户反馈「传视频进度条走到 90% 突然失败」，我一看日志：

```
[relay] OutOfMemoryError: Java heap space —— Content-Length=209,715,200 字节，堆上限 160 MB
```

一个 200MB 的文件，业务服务器先 `readAllBytes()` 整个读进堆，再整体 PUT 给对象存储。
容器给了 160MB 堆，于是每次上传到一半，堆就炸了，连接被掐，前端看到的就是「90% 失败」。

这篇文章记录我怎么把上传改成**客户端直传火山引擎对象存储 TOS**：业务服务器只签一个 URL、不碰文件内容。
结果是 **10.05 秒 → 1.46 秒**，业务进程堆从 **158MB（OOM 边缘）降到 5MB 常驻**，还顺手拿到了断点续传。

工程和压测脚本都在文末仓库，`mvn package` 就能复现全部数字——压测里我写了个 TOS mock，
带 TOSV4 风格验签和**单连接 20MB/s 限速**（模拟公网单条 TCP 流的带宽天花板），不是同进程方法调用"白给"的 0ms。

## 一、先看清楚：老代码两条路都是死路

老接口长这样，两个模式只差一行——文件进不进堆：

```java
if ("buffer".equals(mode)) {
    byte[] all = ex.getRequestBody().readAllBytes();   // 200MB 全进堆
    try (OutputStream os = conn.getOutputStream()) { os.write(all); }
} else {
    try (InputStream in = ex.getRequestBody(); OutputStream os = conn.getOutputStream()) {
        in.transferTo(os);                              // 流式，不 OOM，但只有一条连接
    }
}
```

实测（`-Xmx160m`，200MB 文件）：

| 模式 | 结果 | 耗时 | 业务进程堆峰值 |
| --- | --- | --- | --- |
| buffer | **HTTP 500 OutOfMemoryError** | 277ms 就崩 | 158MB / 上限 160MB |
| stream | 200 OK | **10052ms** | 3MB |

改成流式确实不 OOM 了，但耗时 10 秒——因为业务服务器到 TOS 只有**一条 TCP 连接**，
公网上单流跑到 20MB/s 已经是好运气。更糟的是这条连接是全站共享的：一个用户传 200MB 占满 10 秒，
后面 20 个用户就得排队。加机器？加的是"搬字节的机器"，这活儿本来就不该它干。

所以问题的本质不是"内存不够"，是**数据流走错了路**：

```
改前：浏览器 ──200MB──▶ 业务服务器 ──200MB──▶ TOS     （业务服务器搬两遍）
改后：浏览器 ──① 要个签名 ──▶ 业务服务器              （只传几百字节 JSON）
      浏览器 ──② 200MB 分 8 片，8 条连接 ──▶ TOS       （业务服务器全程旁观）
```

## 二、分片直传：三个接口就够

```
客户端                          业务应用                       TOS
  │  ① POST /api/upload/ticket   │                            │
  │ ────────────────────────────▶│  ② CreateMultipartUpload   │
  │                              │ ──────────────────────────▶│
  │                              │ ◀────────── uploadId ──────│
  │                              │  ③ 为每片签预签名 URL       │
  │ ◀── uploadId + 8 个 URL ─────│                            │
  │                                                           │
  │  ④ 并发 PUT 第 1..8 片（带签名，直连 TOS）                  │
  │ ─────────────────────────────────────────────────────────▶│
  │                                                           │
  │  ⑤ POST /api/upload/complete │  ⑥ ListParts 校验 ETag      │
  │ ────────────────────────────▶│ ──────────────────────────▶│
  │                              │  ⑦ CompleteMultipartUpload │
  │ ◀────────── 200 OK ──────────│ ◀──────────────────────────│
```

业务服务器在 ④ 里一个字节都没碰。它只干两件事：签名、收尾。

## 三、核心代码

### 3.1 签名：预签名 URL 和 header 鉴权只差一个"date 放哪"

```java
public static String signature(String method, String path, Map<String, String> params,
                               String host, String date, String secretKey, boolean presigned) {
    String canonicalHeaders = presigned
            ? "host:" + host + "\n"                                  // 预签名：date 在 query 里
            : "host:" + host + "\n" + "x-tos-date:" + date + "\n";   // header 鉴权
    String signedHeaders = presigned ? "host" : "host;x-tos-date";
    String canonical = method + "\n" + path + "\n" + canonicalQuery(params) + "\n"
            + canonicalHeaders + "\n" + signedHeaders + "\n" + sha256Hex("");
    return hex(hmac(signingKey(secretKey, date), canonical));        // TOS4 + SK → 日 → region → service → request
}
```

`canonicalQuery()` 里有一行 `Collections.sort(keys)`——这不是洁癖，是**签名能不能过**的分水岭，第四节细说。

### 3.2 ticket：只签名，不落盘

```java
// 1) CreateMultipartUpload 拿 uploadId
Resp r = call("POST", "/" + bucket + "/" + key, Map.of("uploads", ""), date, new byte[0]);
String uploadId = between(r.body, "<UploadId>", "</UploadId>");

// 2) 按 1MB 对齐切分（TOS 要求除最后一片外每片 ≥1MB）
long partSize = Math.max(1024 * 1024,
        ((size + parts - 1) / parts + 1048575) / 1048576 * 1048576);

// 3) 每片签一个 URL，服务端不碰文件内容
for (int n = 1; n <= partCount; n++) {
    Map<String, String> pq = new LinkedHashMap<>();
    pq.put("partNumber", String.valueOf(n));
    pq.put("uploadId", uploadId);
    pq.put("X-Tos-Date", date);
    pq.put("X-Tos-Signature", TosSigner.signature("PUT", path, pq, host, date, SK, true));
    urls.add("http://" + host + path + "?" + TosSigner.canonicalQuery(pq));
}
```

签名本身耗时 **68ms**（8 片，含一次 CreateMultipartUpload 往返）。这就是业务服务器在这个链路里的全部开销。

### 3.3 并发上传：定位读，不要每个线程读整个文件

```java
for (int i = 0; i < t.partCount; i++) {
    long offset = (long) i * t.partSize;
    long len = Math.min(t.partSize, t.size - offset);
    pool.submit(() -> putPart(urls.get(i), file, offset, len));
}

// putPart 内部：FileChannel 按 offset 定位读，堆里只有一个 1MB 缓冲
try (FileChannel ch = FileChannel.open(file, READ); OutputStream os = conn.getOutputStream()) {
    ByteBuffer buf = ByteBuffer.allocate(1024 * 1024);
    long pos = offset, remain = len;
    while (remain > 0) {
        buf.clear(); buf.limit((int) Math.min(buf.capacity(), remain));
        int r = ch.read(buf, pos);
        os.write(buf.array(), 0, r);  pos += r;  remain -= r;
    }
}
```

失败重试 3 次（`setFixedLengthStreamingMode` 保证重试时 Content-Length 一致）。

### 3.4 complete 之前必须 ListParts

这是最容易写出「文件看起来传成功了，下载下来是坏的」的地方。分片是并发传的，
到达顺序不可控，合并必须按 `partNumber` 排序；而 ETag 是每片 MD5，必须逐片核对：

```java
public String complete(Ticket t) throws IOException {
    List<PartInfo> parts = listParts(t);                  // GET ?uploadId=xxx
    if (parts.size() != t.partCount) {
        throw new IOException("还差 " + (t.partCount - parts.size()) + " 片没传完，不能 complete");
    }
    // 按 number 排序后拼 XML，交给 CompleteMultipartUpload
}
```

mock 服务端也一样：**先全量校验 ETag，全部对上才落盘合并**，缺一片直接 `400 InvalidPart`。

## 四、实测：10 秒 → 1.5 秒

同一台机器（Apple M1 Pro / JDK 19.0.2），同一个 200MB 文件，全部走真实 HTTP：

| 场景 | 耗时 | 有效吞吐 | 业务进程堆 |
| --- | --- | --- | --- |
| 中转 buffer | **500，277ms 崩溃** | — | 158MB → OOM |
| 中转 stream | 10052ms | 20.0 MB/s | 3MB |
| **分片直传 8 片 / 并发 8** | **1456ms** | **156.9 MB/s** | **5MB** |

拆开看这 1456ms：签名 68ms + 分片上传 1275ms + complete（含 ListParts）2ms，剩下的是一次 ListParts 往返。

**吞吐为什么是 8 倍？** 因为限制不在 CPU、不在磁盘，在单条连接。压测里对每条连接限速 20MB/s，那总吞吐就约等于 `并发数 × 20MB/s`：

| 并发 | 1 | 2 | 4 | 8 |
| --- | --- | --- | --- | --- |
| 耗时 | 10432ms | 5391ms | 2788ms | **1472ms** |
| 吞吐 | 19.9 MB/s | 38.3 MB/s | 76.4 MB/s | **151.7 MB/s** |

线性到 8 并发一点没衰减。这也解释了为什么"多线程下载"永远比"单线程"快——
公网单流的带宽上限，不是靠优化代码能突破的。

512MB 的大文件更说明问题：老接口照样 OOM（157MB 峰值），分片直传 **3516ms / 159.1 MB/s**，
传完之后业务进程堆**还是 4MB**——文件根本不经过它。

**完整性**：合并后的对象和源文件 `sha256` 逐位一致（`4e0cab58d8474392…`）。并发上传最容易出的问题就是片序错乱，
所以这一步我每次都校验。

## 五、断点续传：只补缺口，浪费 0 字节

200MB / 8 片，传完前 3 片时进程被杀：

```
[resume] 第一次只传前 3/8 片：78,643,200 字节 / 1260 ms —— 进程被 kill
[resume] ListParts 查到已传 3 片：[1, 2, 3]        （7ms）
[resume] 续传 5 片 / 131,072,000 字节 / 2503 ms
[resume] 重传浪费 0 字节（续传只补缺口 125 MB，不从头再来）
```

关键就一句：**别猜，问 TOS**。客户端持久化 `uploadId` + `key` + `partSize`，重启后 `ListParts` 拿权威结果，
把已有片号塞进 skip 集合，剩下的才传：

```java
Set<Integer> done = TosClient.uploadedNumbers(client.listParts(t));  // [1,2,3]
client.uploadParts(t, file, concurrency, done);                       // 只传 4..8
```

注意 `partSize` 必须和第一次一致，否则偏移全错——所以 partSize 要跟 uploadId 一起存，不能每次重算。

## 六、六个坑，全是真踩的

**坑 1：非 200 响应先取 `getInputStream()` 会直接抛异常。**
写 `complete()` 时我先读流再判码，结果 400 的时候抛的是 `IOException`，服务端返回的 `InvalidPart` 一个字都看不到，
排查了两小时。正确顺序是先 `getResponseCode()`，再按码选 `getInputStream()` / `getErrorStream()`。

**坑 2：把 TOS 的 400 糊成了 500。**
业务层一开始写 `sendJson(ex, r.code == 200 ? 200 : 500, r.body)`，客户端只看到"服务器错误"，
不会去补片，只会整包重传。透传状态码之后，客户端才能区分"缺片 → 补传"和"服务端炸了 → 退避重试"。

**坑 3：OOM 之后不回响应，用户看到的是"网络错误"。**
buffer 模式炸了堆之后直接 `sendResponseHeaders`，请求体还没读完，连接被重置，客户端报
`Error writing request body to server`。线上表现就是"传一半失败"，没有任何有用信息。
修法是先把剩余请求体 drain 到 `OutputStream.nullOutputStream()`，再回 500，让客户端拿到明确的错误：

```java
} catch (OutOfMemoryError oom) {
    System.err.printf("[relay] OutOfMemoryError —— Content-Length=%,d，堆上限 %d MB%n", len, maxMB);
    try { ex.getRequestBody().transferTo(OutputStream.nullOutputStream()); } catch (Exception ignored) {}
    // 再回 500 + JSON
}
```

**坑 4：预签名 URL 的 query 顺序。**
签名时是按排序后的 canonical query 算的，请求时必须用**同一个顺序**重放。少了 `Collections.sort`，
签名和验签各算各的，全站 403。另外 `X-Tos-Date` 在预签名模式下必须进 query 和规范串，不能只放 header。
（本工程只保留了 TOSV4 的最小集合 `X-Tos-Date` + `X-Tos-Signature`；真实 SDK 还会带 `X-Tos-Algorithm`、
`X-Tos-Credential`、`X-Tos-Expires`、`X-Tos-SignedHeaders`。）

**坑 5：分片大小没对齐 1MB。**
TOS 要求除最后一片外每片不小于 1MB。我第一版直接 `size / parts`，200MB / 8 = 25MB 刚好没事，
换成 200MB / 16 = 13.1MB 就踩了。现在是向上取整到 MB。

**坑 6：断点续传的 skip 集合写反了。**
第一版把"要传的片"当成 skip 传进去，结果先传了尾巴再补头部——文件本身没错（合并按 partNumber），
但日志全对不上，测试同学差点以为续传失效。这类"结果对、语义错"的 bug 最难查，命名和注释比代码重要。

## 七、什么时候该分片，上生产清单

**小文件别分片。** 单次 PUT 在 5MB 以内时，多一次 CreateMultipartUpload + Complete 往返反而更慢，
而且碎片更难管。经验阈值：**> 20MB 或需要断点续传/进度条时**才上分片。

**上生产的 6 件事：**

1. **AK/SK 换成 STS 临时凭证**。前端只拿到 15 分钟有效的预签名 URL，泄露了也不至于被人刷爆桶。
2. **CORS 要在桶上配好**：`AllowedMethod` 含 `PUT`，`AllowedHeader` 放行 `Content-Type`、`x-tos-*`，`ExposeHeader` 带上 `ETag`。
3. **清理碎片**。没 complete 的 upload 会一直占存储，配生命周期规则自动 Abort 掉 N 天前的未完成分片，这笔钱很容易被忽略。
4. **`uploadId` 落库**。放 Redis/MySQL 里，key 用 `业务id + 文件hash`，用户刷新页面或换设备才能续传。
5. **秒传**。文件 sha256 做对象 key 的一部分，上传前先 `HEAD`，命中就直接复用已有对象，连传都不用传。
6. **限流按"文件数"而不是"字节数"**。分片直传后业务服务器的压力从带宽变成了 QPS，签名接口几毫秒一个，别再用带宽做限流维度。

另外别忘了**失败补偿**：`AbortMultipartUpload` 要真的调，不然用户取消上传后碎片会一直堆着。

## 八、最后

回头看，这次改造没有一行"优化"代码——只是把数据流从业务服务器上搬走了。
之前那 10 秒里，业务服务器在干一件它最不该干的事：**当文件的中转站**。
对象存储天生就是干这个的，分片 + 预签名 + 并发，把 20MB/s 的单流变成 157MB/s 的聚合带宽，
顺便把内存从"文件多大就吃多大"变成常数。

工程地址（含 TOS mock、压测脚本、全部实测日志）：

**https://github.com/zhoubiao188/volc-tos-multipart-upload**

```
mvn -q package && ./scripts/start.sh && ./bench/bench.sh
```

同系列还有：[CDN 加速接口](https://github.com/zhoubiao188/volc-cdn-api-accel)、
[ECS + CLB 同城双活](https://github.com/zhoubiao188/volc-ecs-clb-ha)、
[Redis 缓存五坑](https://github.com/zhoubiao188/volc-redis-item-cache)。
