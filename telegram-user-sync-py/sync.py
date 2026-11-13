#!/usr/bin/env python3
"""Watch a folder and send new recordings to a Telegram bot AS YOUR USER ACCOUNT
(via Telethon / MTProto), so they look like manual uploads and the receiver's
getUpdates poller picks them up. Files larger than MAX_PART_BYTES are split into
name.partNofM pieces so the receiving bot can download each (<=20MB) part.

First run is interactive (phone number + code) and saves a session file; after
that it runs unattended.

Enhancements:
  * single-instance lock  -> never two copies fighting over the session (no
    "database is locked")
  * per-send retry + FloodWait handling -> survives transient Telegram errors
  * alerts/summary to your own Saved Messages -> you learn about problems and
    see a daily total without checking the phone
"""

import asyncio
import fcntl
import json
import math
import os
import sys
import time
from io import BytesIO

from telethon import TelegramClient
from telethon.errors import FloodWaitError

BASE = os.path.dirname(os.path.abspath(__file__))


def load_env(path):
    if not os.path.exists(path):
        return
    with open(path, encoding="utf-8") as fh:
        for raw in fh:
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, val = line.split("=", 1)
            key = key.strip()
            val = val.strip().strip('"').strip("'")
            os.environ.setdefault(key, val)


load_env(os.path.join(BASE, ".env"))


def env_bool(name, default):
    val = os.environ.get(name)
    if val is None:
        return default
    return val.strip().lower() in ("1", "true", "yes", "on")


API_ID = int(os.environ.get("API_ID", "0") or "0")
API_HASH = os.environ.get("API_HASH", "")
TARGET = os.environ.get("TARGET", "")
# WATCH_DIR may be a single folder or a comma-separated list, e.g.
#   /storage/emulated/0/Recordings,/storage/emulated/0/Download/KakaoTalk
WATCH_DIRS = [d.strip() for d in os.environ.get("WATCH_DIR", "").split(",") if d.strip()]
RECURSIVE = env_bool("WATCH_RECURSIVE", False)
EXTS = [e.strip().lower() for e in os.environ.get(
    "WATCH_EXTENSIONS", ".m4a,.mp3,.amr,.aac,.wav,.ogg,.opus,.3gp").split(",") if e.strip()]
POLL = int(os.environ.get("POLL_INTERVAL_MS", "5000")) / 1000.0
STABLE = int(os.environ.get("STABLE_FOR_MS", "10000")) / 1000.0
MAX_PART = int(os.environ.get("MAX_PART_BYTES", "19000000"))
MAX_RETRIES = int(os.environ.get("MAX_RETRIES", "4"))
NOTIFY = env_bool("NOTIFY", True)            # alerts + daily summary to Saved Messages
NOTIFY_START = env_bool("NOTIFY_START", False)  # also ping on startup
STATE_FILE = os.environ.get("STATE_FILE", os.path.join(BASE, ".sent-state.json"))
SESSION = os.path.join(BASE, "user")  # -> creates user.session
LOCK_FILE = os.path.join(BASE, ".lock")

for name, value in (("API_ID", API_ID), ("API_HASH", API_HASH), ("TARGET", TARGET)):
    if not value:
        print(f"설정 누락: {name} — .env 를 확인하세요.")
        sys.exit(1)
if not WATCH_DIRS:
    print("설정 누락: WATCH_DIR — .env 를 확인하세요.")
    sys.exit(1)


# --- single-instance lock: refuse to start if another copy already holds it ---
_LOCK_FH = open(LOCK_FILE, "w")
try:
    fcntl.flock(_LOCK_FH, fcntl.LOCK_EX | fcntl.LOCK_NB)
except OSError:
    print("이미 실행 중입니다 (중복 방지). 이 인스턴스는 종료합니다.")
    sys.exit(0)


def now():
    return time.strftime("%H:%M:%S")


def load_state():
    try:
        with open(STATE_FILE, encoding="utf-8") as fh:
            return json.load(fh)
    except Exception:
        return {}


STATE = load_state()


def save_state():
    tmp = STATE_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(STATE, fh)
    os.replace(tmp, STATE_FILE)


def list_files(directory):
    out = []
    try:
        entries = os.listdir(directory)
    except Exception:
        return out
    for name in entries:
        full = os.path.join(directory, name)
        if os.path.isdir(full):
            if RECURSIVE:
                out += list_files(full)
        elif os.path.isfile(full):
            out.append(full)
    return out


def is_sent(path, st):
    entry = STATE.get(path)
    return bool(entry) and entry.get("size") == st.st_size and entry.get("mtime") == st.st_mtime


def mark_sent(path, st):
    STATE[path] = {"size": st.st_size, "mtime": st.st_mtime, "sentAt": time.time()}
    save_state()


async def notify(client, text):
    """Best-effort message to your own Saved Messages. Never breaks syncing."""
    if not NOTIFY:
        return
    try:
        await client.send_message("me", text)
    except Exception as exc:  # noqa: BLE001
        print(now(), f"(알림 실패: {exc})")


async def send_unit(client, source, caption):
    """Send one unit (a whole-file path, or a (bytes, filename) part) with
    retries and FloodWait handling. Raises if it still fails after MAX_RETRIES."""
    attempt = 0
    while True:
        attempt += 1
        try:
            if isinstance(source, str):
                file_arg = source
            else:
                data, fname = source
                bio = BytesIO(data)
                bio.name = fname
                file_arg = bio
            await client.send_file(TARGET, file_arg, force_document=True, caption=caption)
            return
        except FloodWaitError as exc:
            wait = exc.seconds + 2
            print(now(), f"FloodWait {wait}s — 대기 후 재시도: {caption}")
            await asyncio.sleep(wait)
            attempt -= 1  # flood waits are expected; don't count toward giving up
        except Exception as exc:  # noqa: BLE001
            if attempt >= MAX_RETRIES:
                raise
            backoff = min(60, 2 ** attempt)
            print(now(), f"재시도 {attempt}/{MAX_RETRIES} ({caption}): {exc} — {backoff}s 후")
            await asyncio.sleep(backoff)


async def send_recording(client, path, st):
    name = os.path.basename(path)
    if st.st_size <= MAX_PART:
        print(now(), f"Sending: {name} ({st.st_size / 1048576:.1f}MB)")
        await send_unit(client, path, name)
        return 1
    total = max(1, math.ceil(st.st_size / MAX_PART))
    width = len(str(total))
    print(now(), f"Sending (split): {name} ({st.st_size / 1048576:.1f}MB) -> {total} parts")
    with open(path, "rb") as fh:
        for i in range(total):
            chunk = fh.read(MAX_PART)
            part_name = f"{name}.part{str(i + 1).zfill(width)}of{str(total).zfill(width)}"
            await send_unit(client, (chunk, part_name), part_name)
            print(f"  part {i + 1}/{total}: {part_name}")
    return total


# running counters for the daily summary
STATS = {"files": 0, "bytes": 0, "day": time.strftime("%Y-%m-%d")}


async def tick(client):
    files = []
    for directory in WATCH_DIRS:
        files += list_files(directory)
    for path in files:
        try:
            st = os.stat(path)
        except Exception:
            continue
        if os.path.splitext(path)[1].lower() not in EXTS or st.st_size == 0:
            continue
        if is_sent(path, st):
            continue
        if time.time() - st.st_mtime < STABLE:
            continue  # still being written
        name = os.path.basename(path)
        try:
            await send_recording(client, path, st)
            mark_sent(path, st)
            STATS["files"] += 1
            STATS["bytes"] += st.st_size
            print(now(), f"Sent: {name}")
        except Exception as exc:  # noqa: BLE001
            print(now(), f"ERROR {name}: {exc}")
            await notify(client, f"⚠️ 전송 실패(재시도 소진): {name}\n{exc}\n다음 주기에 다시 시도합니다.")


async def maybe_daily_summary(client):
    today = time.strftime("%Y-%m-%d")
    if today != STATS["day"]:
        if STATS["files"]:
            await notify(
                client,
                f"📊 {STATS['day']} 요약 — {STATS['files']}개 / "
                f"{STATS['bytes'] / 1048576:.0f}MB 전송 완료.",
            )
        STATS.update(files=0, bytes=0, day=today)


async def main():
    client = TelegramClient(SESSION, API_ID, API_HASH)
    await client.start()  # interactive on first run (phone + code), then cached
    me = await client.get_me()
    who = ("@" + me.username) if me.username else me.first_name
    print(f"Logged in as {who}. Sending to {TARGET}.")
    print(f"Watching {', '.join(WATCH_DIRS)} (recursive={RECURSIVE}) for {','.join(EXTS)}")
    if NOTIFY_START:
        await notify(client, f"▶️ 동기화 시작 — {', '.join(WATCH_DIRS)} 감시 중")
    while True:
        await tick(client)
        await maybe_daily_summary(client)
        await asyncio.sleep(POLL)


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\n중지됨.")
