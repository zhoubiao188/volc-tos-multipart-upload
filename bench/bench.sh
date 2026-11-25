#!/usr/bin/env bash
# 大文件上传改造 · 全场景压测（客户端视角，全部走真实 HTTP，不调内部方法）
#
# 前置：./scripts/start.sh   （默认 -Xmx160m，TOS mock 单连接限速 20MB/s）
# 用法：./bench/bench.sh
#
# 场景：
#   S0 改造前：业务服务器中转 —— buffer（整个文件进堆）/ stream（单流转发）
#   S1 改造后：分片直传 200MB / 8 片 / 并发 8，并校验合并后的 sha256
#   S2 并发扫描：同样 200MB，并发 1 → 2 → 4 → 8，看吞吐怎么线性涨
#   S3 断点续传：只传前 3 片就"断电"，重启后 ListParts 只补缺口
#   S4 大文件 512MB：老接口必 OOM，分片直传照跑，业务侧堆纹丝不动
#   S5 错误与鉴权：签名被篡改 403、缺片 complete 400
#   S6 指标对账：TOS 侧到底收到了多少片、多少字节

set -uo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
JAVA=${JAVA:-java}
APP=${APP:-127.0.0.1:8080}
TOS=${TOS:-127.0.0.1:9090}
BUCKET=${BUCKET:-volc-demo}
DATA="$ROOT/bench/testdata"
OUT="$ROOT/logs/bench-result.txt"
APP_LOG="$ROOT/logs/app.log"

mkdir -p "$DATA" "$ROOT/logs"
BENCH="$JAVA -cp $ROOT/target/classes com.example.volctos.bench.Bench"
BIG="$DATA/big-200mb.bin"
HUGE="$DATA/huge-512mb.bin"

hr() { printf '=%.0s' {1..78}; echo; }
m() { curl -s "http://$APP/metrics"; }
heap() { m | python3 -c 'import sys,json;d=json.load(sys.stdin);print("%d MB / 上限 %d MB"%(d["heapUsedMB"],d["heapMaxMB"]))'; }
sha() { shasum -a 256 "$1" | cut -d' ' -f1; }

[ -f "$BIG" ]  || $BENCH gen "$BIG"  200 >/dev/null
[ -f "$HUGE" ] || $BENCH gen "$HUGE" 512 >/dev/null

{
hr
echo "大文件上传 · 火山引擎对象存储 TOS 分片直传改造压测"
echo "机器：$(sysctl -n machdep.cpu.brand_string 2>/dev/null || uname -m)  JDK：$($JAVA -version 2>&1 | head -1 | sed 's/.*"\(.*\)".*/\1/')"
echo "业务进程堆上限：$(heap | sed 's#.*上限 ##')   TOS mock 单连接限速：20 MB/s"
echo "时间：$(date '+%Y-%m-%d %H:%M:%S')"
hr

echo
echo "【S0-1】改造前 · buffer：先把 200MB 整个读进堆，再一把推给对象存储"
$BENCH relay "$BIG" buffer
echo "  服务端日志：$(grep -a 'OutOfMemoryError' "$APP_LOG" | tail -1 | sed 's/^\[relay\] //')"

echo
echo "【S0-2】改造前 · stream：改成流式转发，不 OOM 了，但全站只有一条连接在搬数据"
$BENCH relay "$BIG" stream

echo
echo "【S1】改造后 · 分片直传：200MB / 8 片 / 并发 8，业务服务器只签名，不碰文件内容"
S1=$($BENCH direct "$BIG" 8 8)
echo "$S1"
KEY=$(echo "$S1" | sed -n 's/.*key=\(direct[^ ]*\) uploadId=.*/\1/p')
if [ -n "$KEY" ] && [ -f "$ROOT/data/objects/$BUCKET/$KEY" ]; then
  A=$(sha "$BIG"); B=$(sha "$ROOT/data/objects/$BUCKET/$KEY")
  echo "  完整性校验：源文件 ${A:0:16}…  合并后对象 ${B:0:16}…  → $([ "$A" = "$B" ] && echo 一致 || echo 不一致)"
fi
echo "  当前业务进程堆：$(heap)"

echo
echo "【S2】并发扫描：同样 200MB（16 片），只改并发数"
for c in 1 2 4 8; do
  echo "  -- 并发 $c"
  $BENCH direct "$BIG" 16 "$c" | grep -E '分片上传|有效吞吐'
done

echo
echo "【S3】断点续传：传到第 3/8 片时进程被杀，重启后先 ListParts 再补缺口"
$BENCH resume "$BIG" 8 4 3

echo
echo "【S4】大文件 512MB"
echo "  -- 老接口 buffer："
$BENCH relay "$HUGE" buffer | head -3
echo "  -- 分片直传 16 片 / 并发 8："
$BENCH direct "$HUGE" 16 8
echo "  传完 512MB 后业务进程堆：$(heap)（文件根本不经过业务进程）"

echo
echo "【S5】错误与鉴权"
TICKET=$(curl -s "http://$APP/api/upload/ticket?size=20971520&parts=4&key=neg/check.bin")
U1=$(echo "$TICKET" | python3 -c 'import sys,json;print(json.load(sys.stdin)["urls"][0])')
UID_=$(echo "$TICKET" | python3 -c 'import sys,json;print(json.load(sys.stdin)["uploadId"])')
echo "  -- 预签名 URL 的签名被改掉再 PUT（SK 不对就是这个下场）"
curl -s -w "     HTTP %{http_code}\n" -X PUT --data-binary @<(head -c 1048576 "$BIG") \
  "${U1%X-Tos-Signature=*}X-Tos-Signature=deadbeef" | sed 's#<?xml[^>]*>##'
echo "  -- 只传第 1 片就想 complete"
ETAG=$(curl -s -D - -o /dev/null -X PUT --data-binary @<(head -c 5242880 "$BIG") "$U1" \
  | grep -i '^etag:' | tr -d '\r' | cut -d' ' -f2)
curl -s -w "     HTTP %{http_code}\n" -X POST \
  "http://$APP/api/upload/complete?key=neg/check.bin&uploadId=$UID_" \
  -H 'Content-Type: application/xml' \
  -d "<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>$ETAG</ETag></Part><Part><PartNumber>2</PartNumber><ETag>\"0000\"</ETag></Part></CompleteMultipartUpload>" \
  | sed 's#<?xml[^>]*>##'

echo
echo "【S6】指标对账（TOS mock 侧真实计数）"
m | python3 -m json.tool
echo "  对象数据盘占用：$(du -sh "$ROOT/data" 2>/dev/null | cut -f1)"

echo
hr
echo "备注：TOS mock 对每条 TCP 连接限速 20MB/s，模拟公网单流的带宽天花板；"
echo "      分片直传的总吞吐 ≈ 并发数 × 单连接带宽，并发 8 就能吃满 160MB/s。"
echo "      全部为本机实跑数据，未做任何修饰。"
hr
} 2>&1 | tee "$OUT"

echo
echo "结果已写入 $OUT"
