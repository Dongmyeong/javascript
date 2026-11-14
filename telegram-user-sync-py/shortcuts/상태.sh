#!/data/data/com.termux/files/usr/bin/sh
# Termux:Widget shortcut — show status (running?, last sent, today's count).
cd ~/javascript/telegram-user-sync-py || { echo "폴더 없음 ❌"; sleep 4; exit 1; }
if pgrep -f "sync.py" >/dev/null; then
  echo "상태: 실행 중 ✅"
else
  echo "상태: 멈춤 ❌ (녹음시작 탭)"
fi
python status.py 2>/dev/null
echo "---- 최근 로그 ----"
tail -n 6 ~/user-sync.log 2>/dev/null
sleep 8
