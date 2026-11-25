#!/usr/bin/env bash
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
PID_FILE="$ROOT/logs/app.pid"
if [ -f "$PID_FILE" ]; then
  PID=$(cat "$PID_FILE")
  if kill -0 "$PID" 2>/dev/null; then
    kill "$PID"
    for _ in $(seq 1 20); do kill -0 "$PID" 2>/dev/null || break; sleep 0.2; done
    kill -9 "$PID" 2>/dev/null || true
    echo "已停止 pid=$PID"
  else
    echo "进程 $PID 不在了"
  fi
  rm -f "$PID_FILE"
else
  echo "没有 pid 文件，按端口清理"
  lsof -ti :8080 -ti :9090 2>/dev/null | xargs -r kill 2>/dev/null || true
fi
