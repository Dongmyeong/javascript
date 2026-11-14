#!/data/data/com.termux/files/usr/bin/sh
# Termux:Widget shortcut — start with auto-recovery (idempotent).
# Launches run.sh, which keeps sync.py alive and restarts it if it ever exits.
# The single-instance lock means tapping again never creates a duplicate.
termux-wake-lock
cd ~/javascript/telegram-user-sync-py || { echo "폴더 없음 ❌"; sleep 4; exit 1; }
if pgrep -f "telegram-user-sync-py/run.sh" >/dev/null || pgrep -f "sync.py" >/dev/null; then
  echo "이미 실행 중 ✅"
else
  nohup sh run.sh > ~/user-sync.log 2>&1 &
  sleep 4
  pgrep -f "sync.py" >/dev/null && echo "시작됨 ✅ (자동복구 ON)" || { echo "시작 실패 ❌"; tail -n 8 ~/user-sync.log; }
fi
sleep 4
