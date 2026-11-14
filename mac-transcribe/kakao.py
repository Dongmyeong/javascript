#!/usr/bin/env python3
"""Process KakaoTalk exports on the Mac:
  * a KakaoTalk export .zip  -> unzip, pull the chat text out, (optional) summarize
  * a loose KakaoTalkChats.txt (shipped from the phone) -> (optional) summarize

Media inside a zip is ignored — only the conversation text matters.

  python kakao.py path/to/KakaoTalkChats.txt
  SUMMARIZE=1 python kakao.py --watch /Users/donoh/.openclaw/media/inbound
"""

import argparse
import os
import shutil
import time
import zipfile

from summarizer import summarize

CHAT_SUMMARY_PROMPT = (
    "다음은 카카오톡 대화 내보내기 내용입니다. 나중에 '이 사람과의 관계와 이력'을\n"
    "찾아볼 때 쓸 수 있도록, 정보 가치를 보존하는 기록으로 한국어로 정리하세요.\n"
    "육하원칙(누가·언제·어디서·무엇을·어떻게·왜)을 챙기고, 아래 형식을 지키세요:\n\n"
    "## 한 줄 요약\n\n"
    "## 인물 (누가)\n- 대화 상대 / 등장인물·역할 (아는 범위에서)\n\n"
    "## 기간·시점 (언제)\n- \n\n"
    "## 핵심 내용 (무엇을·어떻게)\n- 주요 논의·결정·수치·고유명사\n\n"
    "## 배경·목적 (왜)\n- \n\n"
    "## 약속·할 일\n- 담당 / 기한\n\n"
    "## 기억할 사실 (기록 가치)\n- 나중에 찾아볼 구체 정보(금액·일정·연락처·관계 등)\n\n"
    "대화에 없는 내용은 지어내지 말고, 날짜·이름·숫자는 정확히 쓰세요.\n\n---\n대화:\n"
)


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def chat_header(txt_path):
    """Pull the chat room / person name and saved-date from a KakaoTalk export's
    first lines, e.g. 'OOO 님과 카카오톡 대화' / '저장한 날짜 : ...'."""
    room, saved = "", ""
    try:
        with open(txt_path, encoding="utf-8", errors="replace") as fh:
            for _ in range(8):
                line = fh.readline()
                if not line:
                    break
                line = line.strip()
                if not line:
                    continue
                if ("카카오톡 대화" in line or "님과" in line) and not room:
                    room = line
                elif line.startswith("저장한 날짜") and not saved:
                    saved = line.split(":", 1)[-1].strip()
                elif not room:
                    room = line  # fallback: first non-empty line
    except OSError:
        pass
    return room, saved


def write_summary(txt_path, base, args):
    if not args.summarize:
        return
    if os.path.exists(base + ".summary.md") and not args.force:
        return
    try:
        with open(txt_path, encoding="utf-8", errors="replace") as fh:
            content = fh.read()
        room, saved = chat_header(txt_path)
        log(f"  대화방: {room or '(알 수 없음)'}")
        log("  요약…")
        summary = summarize(content, CHAT_SUMMARY_PROMPT, args.ollama_model, args.ollama_host, log)
        header = f"# 카톡 대화 요약 — {room or os.path.basename(base)}\n"
        if saved:
            header += f"> 저장 날짜: {saved}\n"
        with open(base + ".summary.md", "w", encoding="utf-8") as fh:
            fh.write(header + "\n" + summary + "\n")
        log(f"  요약 → {os.path.basename(base)}.summary.md")
    except Exception as exc:  # noqa: BLE001
        log(f"  (요약 건너뜀: {exc})")


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
    low = path.lower()
    if low.endswith(".zip"):
        base = os.path.splitext(path)[0]
        chat_txt = base + ".chat.txt"
        if os.path.exists(chat_txt) and not args.force:
            return
        log(f"카톡 zip 처리: {os.path.basename(path)}")
        try:
            ok = extract_chat_txt(path, chat_txt)
        except zipfile.BadZipFile:
            log("  (손상/미완성 zip — 다음에 다시)")
            return
        if not ok:
            log("  (.txt 없음 — 건너뜀)")
            return
        log(f"  대화 추출 → {os.path.basename(chat_txt)}")
        write_summary(chat_txt, base, args)
    elif low.endswith(".txt"):
        base = os.path.splitext(path)[0]
        if os.path.exists(base + ".summary.md") and not args.force:
            return
        log(f"카톡 대화 처리: {os.path.basename(path)}")
        write_summary(path, base, args)


def is_target(name):
    low = name.lower()
    if ".part" in low or low.endswith(".chat.txt"):
        return False  # skip split parts and our own extracted output
    if low.endswith(".zip"):
        return True
    # only summarize .txt that are clearly KakaoTalk exports (not transcripts)
    return low.endswith(".txt") and "kakao" in low


def watch(directory, args):
    log(f"감시 시작: {directory} (카톡 zip/.txt 자동 처리)")
    while True:
        try:
            names = sorted(os.listdir(directory))
        except OSError:
            names = []
        for name in names:
            path = os.path.join(directory, name)
            if os.path.isfile(path) and is_target(name):
                try:
                    process(path, args)
                except Exception as exc:  # noqa: BLE001
                    log(f"ERROR {name}: {exc}")
        time.sleep(args.interval)


def main():
    ap = argparse.ArgumentParser(description="KakaoTalk export (.zip/.txt) -> chat text + summary")
    ap.add_argument("input", nargs="?", help="a KakaoTalk .zip or .txt export")
    ap.add_argument("--watch", metavar="DIR", help="watch a folder for new exports")
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
