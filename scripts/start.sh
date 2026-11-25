#!/usr/bin/env bash
# 启动两个服务（同一个 JVM 内）：
#   9090  TOS mock（含验签 + 单连接限速）
#   8080  业务应用（relay / ticket / complete / parts / metrics）
#
# 故意把业务侧堆压到 160MB —— 这样才能复现"老接口把整个文件读进堆 → OOM"。
# 需要放开：HEAP=1g ./scripts/start.sh

set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
JAR="$ROOT/target/volc-tos-multipart-upload.jar"
LOG="$ROOT/logs/app.log"

HEAP=${HEAP:-160m}
TOS_RATE=${TOS_RATE:-20971520}     # 单连接 20MB/s，模拟公网带宽天花板
JAVA=${JAVA:-java}

mkdir -p "$ROOT/logs" "$ROOT/data"

if [ ! -f "$JAR" ]; then
  echo "未找到 $JAR，先 mvn -q package"
  (cd "$ROOT" && mvn -q package -DskipTests)
fi

if [ -f "$ROOT/logs/app.pid" ] && kill -0 "$(cat "$ROOT/logs/app.pid")" 2>/dev/null; then
  echo "已在运行 pid=$(cat "$ROOT/logs/app.pid")，先 ./scripts/stop.sh"
  exit 1
fi

nohup "$JAVA" -Xmx"$HEAP" -DtosRate="$TOS_RATE" -jar "$JAR" \
  >> "$LOG" 2>&1 &
echo $! > "$ROOT/logs/app.pid"

for _ in $(seq 1 50); do
  if curl -s -o /dev/null "http://127.0.0.1:8080/metrics"; then
    echo "已启动 pid=$(cat "$ROOT/logs/app.pid") heap=$HEAP 单连接限速=$((TOS_RATE/1048576))MB/s"
    tail -2 "$LOG"
    exit 0
  fi
  sleep 0.2
done

echo "启动失败，看 $LOG"
tail -20 "$LOG"
exit 1
