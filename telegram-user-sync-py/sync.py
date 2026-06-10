#!/usr/bin/env python3
"""Watch a folder and send new recordings to a Telegram bot AS YOUR USER ACCOUNT
(via Telethon / MTProto), so they look like manual uploads and the receiver's
getUpdates poller picks them up. Files larger than MAX_PART_BYTES are split into
name.partNofM pieces so the receiving bot can download each (<=20MB) part.

First run is interactive (phone number + code) and saves a session file; after
that it runs unattended.
"""

import asyncio
import json
import math
import os
import sys
import time
from io import BytesIO

from telethon import TelegramClient
from telethon.tl.types import DocumentAttributeFilename  # noqa: F401 (kept for ref)

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

API_ID = int(os.environ.get("API_ID", "0") or "0")
API_HASH = os.environ.get("API_HASH", "")
TARGET = os.environ.get("TARGET", "")
WATCH_DIR = os.environ.get("WATCH_DIR", "")
RECURSIVE = os.environ.get("WATCH_RECURSIVE", "").lower() in ("1", "true", "yes", "on")
EXTS = [e.strip().lower() for e in os.environ.get(
    "WATCH_EXTENSIONS", ".m4a,.mp3,.amr,.aac,.wav,.ogg,.opus,.3gp").split(",") if e.strip()]
POLL = int(os.environ.get("POLL_INTERVAL_MS", "5000")) / 1000.0
STABLE = int(os.environ.get("STABLE_FOR_MS", "10000")) / 1000.0
MAX_PART = int(os.environ.get("MAX_PART_BYTES", "19000000"))
STATE_FILE = os.environ.get("STATE_FILE", os.path.join(BASE, ".sent-state.json"))
SESSION = os.path.join(BASE, "user")  # -> creates user.session

for name, value in (("API_ID", API_ID), ("API_HASH", API_HASH),
                    ("TARGET", TARGET), ("WATCH_DIR", WATCH_DIR)):
    if not value:
        print(f"설정 누락: {name} — .env 를 확인하세요.")
        sys.exit(1)


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


async def send_recording(client, path, st):
    name = os.path.basename(path)
    if st.st_size <= MAX_PART:
        print(now(), f"Sending: {name} ({st.st_size / 1048576:.1f}MB)")
        await client.send_file(TARGET, path, force_document=True, caption=name)
        return
    total = max(1, math.ceil(st.st_size / MAX_PART))
    width = len(str(total))
    print(now(), f"Sending (split): {name} ({st.st_size / 1048576:.1f}MB) -> {total} parts")
    with open(path, "rb") as fh:
        for i in range(total):
            chunk = fh.read(MAX_PART)
            part_name = f"{name}.part{str(i + 1).zfill(width)}of{str(total).zfill(width)}"
            bio = BytesIO(chunk)
            bio.name = part_name  # Telethon uses this as the filename
            await client.send_file(TARGET, bio, force_document=True, caption=part_name)
            print(f"  part {i + 1}/{total}: {part_name}")


async def tick(client):
    for path in list_files(WATCH_DIR):
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
        try:
            await send_recording(client, path, st)
            mark_sent(path, st)
            print(now(), f"Sent: {os.path.basename(path)}")
        except Exception as exc:  # noqa: BLE001
            print(now(), f"ERROR {os.path.basename(path)}: {exc}")


async def main():
    client = TelegramClient(SESSION, API_ID, API_HASH)
    await client.start()  # interactive on first run (phone + code), then cached
    me = await client.get_me()
    who = ("@" + me.username) if me.username else me.first_name
    print(f"Logged in as {who}. Sending to {TARGET}.")
    print(f"Watching {WATCH_DIR} (recursive={RECURSIVE}) for {','.join(EXTS)}")
    while True:
        await tick(client)
        await asyncio.sleep(POLL)


if __name__ == "__main__":
    asyncio.run(main())
