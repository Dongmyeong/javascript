#!/usr/bin/env python3
"""Print a quick status: running?, last sent file/time, total tracked, today's
count. Safe to run while sync.py is running (read-only)."""

import json
import os
import time

BASE = os.path.dirname(os.path.abspath(__file__))
STATE_FILE = os.path.join(BASE, ".sent-state.json")

try:
    with open(STATE_FILE, encoding="utf-8") as fh:
        state = json.load(fh)
except Exception:
    state = {}

# running? — look for a python process whose state file lock is held is complex;
# instead rely on the caller (pgrep). Here we just report the state file.
total = len(state)
if state:
    latest_path, latest = max(state.items(), key=lambda kv: kv[1].get("sentAt", 0))
    when = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(latest.get("sentAt", 0)))
    today = time.strftime("%Y-%m-%d")
    today_n = sum(1 for v in state.values()
                  if time.strftime("%Y-%m-%d", time.localtime(v.get("sentAt", 0))) == today)
    print(f"전송 기록 총 {total}개")
    print(f"오늘 전송: {today_n}개")
    print(f"마지막 전송: {os.path.basename(latest_path)}  ({when})")
else:
    print("아직 전송 기록이 없습니다.")
