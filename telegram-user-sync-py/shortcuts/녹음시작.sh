#!/data/data/com.termux/files/usr/bin/sh
# Termux:Widget shortcut — start the sync (idempotent: won't start a duplicate).
# Install: copy to ~/.shortcuts/ and `chmod +x`, then add a Termux:Widget to the
# home screen and pick this script.
termux-wake-lock
cd ~/javascript/telegram-user-sync-py || { echo "폴더 없음 ❌"; sleep 4; exit 1; }
if pgrep -f "sync.py" >/dev/null; then
  echo "이미 실행 중 ✅"
else
  nohup python -u sync.py > ~/user-sync.log 2>&1 &
  sleep 3
  pgrep -f "sync.py" >/dev/null && echo "시작됨 ✅" || { echo "시작 실패 ❌"; tail -n 8 ~/user-sync.log; }
fi
sleep 4
