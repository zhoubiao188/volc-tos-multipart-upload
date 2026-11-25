# volc-tos-multipart-upload

**200MB 文件经业务服务器中转上传 → OOM 500；改成火山引擎对象存储 TOS 分片直传后，10 秒 → 1.5 秒，业务进程堆 158MB → 5MB。**

一个零第三方依赖的 Java 工程，把「大文件上传」这件事从"业务服务器搬字节"改成"客户端直传对象存储"，
并带一个本地 TOS mock（含 TOSV4 风格验签、单连接限速、分片合并校验），`mvn package` 就能完整复现下面所有数字。

## 一条命令看结论

```bash
mvn -q package
./scripts/start.sh        # -Xmx160m，TOS mock 单连接限速 20MB/s
./bench/bench.sh          # 约 50 秒，结果写入 logs/bench-result.txt
./scripts/stop.sh
```

实测（Apple M1 Pro / JDK 19.0.2 / 本地全走 HTTP）：

| 场景 | 耗时 | 有效吞吐 | 业务进程堆 |
| --- | --- | --- | --- |
| 中转 · buffer（整个文件进堆）200MB | **HTTP 500**，277ms 就崩 | — | 峰值 158MB / 上限 160MB → **OOM** |
| 中转 · stream（单流转发）200MB | 10052ms | 20.0 MB/s | 峰值 3MB |
| **分片直传** 200MB / 8 片 / 并发 8 | **1456ms**（签名 68ms + 上传 1275ms） | **156.9 MB/s** | **5MB** |
| 分片直传 512MB / 16 片 / 并发 8 | 3516ms | 159.1 MB/s | 4MB |

并发数与吞吐（同样 200MB、16 片，只改并发）：

| 并发 | 1 | 2 | 4 | 8 |
| --- | --- | --- | --- | --- |
| 耗时 | 10432ms | 5391ms | 2788ms | **1472ms** |
| 吞吐 | 19.9 MB/s | 38.3 MB/s | 76.4 MB/s | **151.7 MB/s** |

断点续传：传完前 3/8 片后中断，`ListParts` 7ms 查到 `[1,2,3]`，只补 5 片 125MB / 2503ms，**重传浪费 0 字节**。
合并后的对象与源文件 **sha256 完全一致**。

## 两个服务，故意分开端口

```
                      ┌──────────────────────────────┐
  客户端 ──① ticket──▶ │  业务应用 :8080               │
         ◀─ uploadId ─│  /api/upload/ticket          │
           + 预签名URL │  /api/upload/complete        │
                      │  /api/upload/parts (ListParts)│
                      └───────────┬──────────────────┘
                                  │ ② CreateMultipartUpload / Complete / ListParts
                                  ▼
   客户端 ──③ 并发 PUT 各分片（带预签名，业务服务器完全不参与）──▶ TOS mock :9090
                                  │
                                  ▼ ④ 按 partNumber 顺序拼接 + 校验 ETag
                            objects/volc-demo/<key>
```

- `:9090` —— 只实现 Multipart Upload 五个接口的 TOS mock：`CreateMultipartUpload` / `UploadPart` / `ListParts` /
  `CompleteMultipartUpload` / `AbortMultipartUpload`，外加 TOSV4 风格验签（AK/SK 错直接 403）和**单连接限速**（默认 20MB/s，模拟公网单条 TCP 流的带宽天花板）。
- `:8080` —— 业务侧四个接口。老接口 `relay` 把文件搬进业务进程；新接口 `ticket` 只签名，一个字节内容都不碰。

## 目录

```
src/main/java/com/example/volctos/
├── App.java                 一个进程起两个服务（9090 + 8080）
├── tos/
│   ├── TosSigner.java       TOSV4-HMAC-SHA256 签名（含预签名 URL 分支）
│   ├── TosMockServer.java   TOS mock：五个接口 + 验签 + 限速 + 合并校验
│   └── TosClient.java       端上分片直传客户端（并发、重试、ListParts 续传）
├── web/
│   ├── BizServer.java       relay / ticket / complete / parts / metrics
│   └── Json.java            够用的 JSON 序列化，不引第三方库
└── bench/Bench.java         gen / relay / direct / resume / sha
bench/bench.sh               全场景压测（S0 ~ S6）
scripts/start.sh stop.sh     启停（HEAP / TOS_RATE 可覆盖）
docs/article.md              社区文章正文（就是本工程改造过程的完整记录）
```

## 五个接口怎么对应真 TOS

| mock 路径 | 真实 TOS |
| --- | --- |
| `POST /{bucket}/{key}?uploads` | `CreateMultipartUpload` |
| `PUT /{bucket}/{key}?partNumber=n&uploadId=x` | `UploadPart`（预签名 URL 直传） |
| `GET /{bucket}/{key}?uploadId=x` | `ListParts`（断点续传靠它） |
| `POST /{bucket}/{key}?uploadId=x` | `CompleteMultipartUpload` |
| `DELETE /{bucket}/{key}?uploadId=x` | `AbortMultipartUpload` |

生产上把 `tosEndpoint` 换成 `<bucket>.tos-cn-beijing.volces.com`、签名交给 `ve-tos-java-sdk`，
其余请求路径、参数、ETag 语义一致。AK/SK 换成 STS 临时凭证，预签名 URL 有效期压到 15 分钟。

## 六个真踩的坑（文章里有详细复盘）

1. 非 200 响应先调 `getInputStream()` 会直接抛 `IOException`，错误体永远读不到 —— 必须先判码。
2. `CompleteMultipartUpload` 的 `400 InvalidPart` 被业务层糊成 500，客户端没法区分"缺片"和"服务端炸了"。
3. buffer 模式 OOM 后不回响应，客户端只看到 `Error writing request body to server`，必须把剩余请求体 drain 掉再回 500。
4. 预签名 URL 的 canonical query 必须与签名时**完全同序**，`X-Tos-Date` 也得进规范串，否则 403。
5. 分片大小必须 1MB 对齐（除最后一片），否则真 TOS 直接拒；本工程向上取整到 MB。
6. 断点续传的 skip 集合写过一次反的，结果对、日志全错 —— 先写清楚"已传/待传"再写代码。

## 相关

- 同系列：[CDN 加速](https://github.com/zhoubiao188/volc-cdn-api-accel) ·
  [ECS + CLB 双活](https://github.com/zhoubiao188/volc-ecs-clb-ha) ·
  [Redis 缓存](https://github.com/zhoubiao188/volc-redis-item-cache)
