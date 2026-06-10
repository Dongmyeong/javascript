#!/usr/bin/env python3
"""Mark all recordings currently in WATCH_DIR as already-sent, WITHOUT sending
them. Run this once if you only want NEW recordings (from now on) to be sent —
it stops the whole existing backlog from re-uploading.

    python seed.py
"""

import os

import sync  # reuses the same .env config and helpers (does not start syncing)

count = 0
for path in sync.list_files(sync.WATCH_DIR):
    try:
        st = os.stat(path)
    except OSError:
        continue
    if os.path.splitext(path)[1].lower() in sync.EXTS and st.st_size > 0:
        sync.mark_sent(path, st)
        count += 1

print(f"기존 {count}개를 '이미 보냄'으로 표시했습니다. 이제 새 녹음만 전송됩니다.")
