#!/usr/bin/env python3
"""Process KakaoTalk "모든 메시지" exports (.zip): unzip, pull out the chat text
file, and optionally summarize it with a local Ollama model. Media inside the
zip is ignored — we only want the conversation text.

  python kakao.py path/to/KakaoTalkChats.zip
  python kakao.py --watch /Users/donoh/.openclaw/media/inbound
  SUMMARIZE=1 python kakao.py --watch /path   # also write <name>.summary.md
"""

import argparse
import json
import os
import shutil
import time
import urllib.request
import zipfile

CHAT_SUMMARY_PROMPT = (
    "다음은 카카오톡 대화 내보내기 내용입니다. 한국어로 핵심만 정리하세요.\n"
    "아래 형식(마크다운)을 지키세요:\n\n"
    "## 핵심 요약\n- (3~6줄)\n\n"
    "## 주요 논의·결정\n- \n\n"
    "## 할 일·약속 (날짜/대상 있으면 함께)\n- \n\n"
    "## 특이사항\n- \n\n"
    "대화에 없는 내용은 지어내지 마세요.\n\n---\n대화:\n"
)


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def summarize(text, model, host):
    body = json.dumps({"model": model, "prompt": CHAT_SUMMARY_PROMPT + text, "stream": False})
    req = urllib.request.Request(
        host.rstrip("/") + "/api/generate",
        data=body.encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=1200) as resp:
        return json.loads(resp.read().decode("utf-8")).get("response", "").strip()


def extract_chat_txt(zip_path, dest_txt):
    """Pull the largest .txt (the chat log) out of a KakaoTalk export zip."""
    with zipfile.ZipFile(zip_path) as zf:
        txts = [n for n in zf.namelist() if n.lower().endswith(".txt")]
        if not txts:
            return False
        txts.sort(key=lambda n: zf.getinfo(n).file_size, reverse=True)
        with zf.open(txts[0]) as src, open(dest_txt, "wb") as out:
            shutil.copyfileobj(src, out)
    return True


def process(path, args):
    base = os.path.splitext(path)[0]
    txt = base + ".chat.txt"
    if os.path.exists(txt) and not args.force:
        return
    log(f"카톡 zip 처리: {os.path.basename(path)}")
    try:
        ok = extract_chat_txt(path, txt)
    except zipfile.BadZipFile:
        log("  (손상/미완성 zip — 다음에 다시)")
        return
    if not ok:
        log("  (.txt 없음 — 건너뜀)")
        return
    log(f"  대화 추출 → {os.path.basename(txt)}")

    if args.summarize:
        try:
            with open(txt, encoding="utf-8", errors="replace") as fh:
                content = fh.read()
            log("  요약…")
            summary = summarize(content, args.ollama_model, args.ollama_host)
            with open(base + ".summary.md", "w", encoding="utf-8") as fh:
                fh.write(summary + "\n")
            log(f"  요약 → {os.path.basename(base)}.summary.md")
        except Exception as exc:  # noqa: BLE001
            log(f"  (요약 건너뜀: {exc})")


def watch(directory, args):
    log(f"감시 시작: {directory} (카톡 .zip 자동 처리)")
    while True:
        try:
            names = sorted(os.listdir(directory))
        except OSError:
            names = []
        for name in names:
            path = os.path.join(directory, name)
            if os.path.isfile(path) and name.lower().endswith(".zip") and ".part" not in name:
                try:
                    process(path, args)
                except Exception as exc:  # noqa: BLE001
                    log(f"ERROR {name}: {exc}")
        time.sleep(args.interval)


def main():
    ap = argparse.ArgumentParser(description="KakaoTalk export (.zip) -> chat text + summary")
    ap.add_argument("input", nargs="?", help="a KakaoTalk .zip export")
    ap.add_argument("--watch", metavar="DIR", help="watch a folder for new .zip exports")
    ap.add_argument("--summarize", action="store_true",
                    default=os.environ.get("SUMMARIZE", "").lower() in ("1", "true", "yes", "on"))
    ap.add_argument("--ollama-model", default=os.environ.get("OLLAMA_MODEL", "qwen2.5:7b"))
    ap.add_argument("--ollama-host", default=os.environ.get("OLLAMA_HOST", "http://localhost:11434"))
    ap.add_argument("--interval", type=float, default=10.0)
    ap.add_argument("--force", action="store_true")
    args = ap.parse_args()

    if args.watch:
        watch(args.watch, args)
    elif args.input:
        process(args.input, args)
    else:
        ap.print_help()


if __name__ == "__main__":
    main()
