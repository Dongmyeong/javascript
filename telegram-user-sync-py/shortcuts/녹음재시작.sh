#!/data/data/com.termux/files/usr/bin/sh
# Termux:Widget shortcut — force restart (kill runner + sync, start fresh).
# Use when stuck or showing "database is locked".
termux-wake-lock
pkill -9 -f "telegram-user-sync-py/run.sh"
pkill -9 -f "sync.py"
sleep 2
cd ~/javascript/telegram-user-sync-py || { echo "폴더 없음 ❌"; sleep 4; exit 1; }
nohup sh run.sh > ~/user-sync.log 2>&1 &
sleep 4
pgrep -f "sync.py" >/dev/null && echo "재시작됨 ✅ (자동복구 ON)" || { echo "실패 ❌"; tail -n 8 ~/user-sync.log; }
sleep 4
