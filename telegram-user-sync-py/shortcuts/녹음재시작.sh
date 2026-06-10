#!/data/data/com.termux/files/usr/bin/sh
# Termux:Widget shortcut — force restart (kill all instances, then start one).
# Use when the sync is stuck or shows "database is locked".
termux-wake-lock
pkill -9 -f "sync.py"; sleep 2
cd ~/javascript/telegram-user-sync-py || { echo "폴더 없음 ❌"; sleep 4; exit 1; }
nohup python -u sync.py > ~/user-sync.log 2>&1 &
sleep 3
pgrep -f "sync.py" >/dev/null && echo "재시작됨 ✅" || { echo "실패 ❌"; tail -n 8 ~/user-sync.log; }
sleep 4
