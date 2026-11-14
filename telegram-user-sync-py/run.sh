#!/data/data/com.termux/files/usr/bin/sh
# Self-healing runner: keeps sync.py alive (restarts it if it ever exits), and
# holds a wakelock. The single-instance lock inside sync.py means a second copy
# simply exits, so running this twice is harmless.
#
#   nohup sh run.sh > ~/user-sync.log 2>&1 &
termux-wake-lock 2>/dev/null
cd "$(dirname "$0")" || exit 1
while true; do
  python -u sync.py
  echo "$(date '+%H:%M:%S') sync.py 종료됨 — 10초 후 재시작"
  sleep 10
done
