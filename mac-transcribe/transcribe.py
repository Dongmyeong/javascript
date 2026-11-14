#!/usr/bin/env python3
"""High-quality Korean meeting transcription for Apple Silicon (M-series).

Pipeline per file:
  1. ffmpeg preprocess  -> 16 kHz mono WAV + loudness normalize (loudnorm)
  2. faster-whisper     -> large-v3, language=ko, VAD, optional vocab prompt
  3. pyannote (optional)-> speaker diarization, merged onto the transcript
  4. write <name>.txt (speaker-labeled), <name>.srt, <name>.json

Usage:
  python transcribe.py path/to/meeting.m4a
  python transcribe.py --watch /Users/you/.openclaw/media/inbound
  HF_TOKEN=hf_xxx python transcribe.py meeting.m4a      # enables diarization

Only depends on ffmpeg (brew) + faster-whisper (+ pyannote.audio for speakers).
"""

import argparse
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.request

AUDIO_EXTS = {".m4a", ".mp3", ".wav", ".aac", ".amr", ".ogg", ".opus", ".3gp", ".flac"}


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def preprocess(src, dst):
    """Convert to 16 kHz mono WAV and normalize loudness — the input Whisper
    likes best, which noticeably improves accuracy on phone recordings."""
    subprocess.run(
        ["ffmpeg", "-y", "-i", src, "-ac", "1", "-ar", "16000",
         "-af", "loudnorm", "-f", "wav", dst],
        check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )


def load_vocab(path):
    """Optional comma-joined domain terms (names, companies) -> initial_prompt,
    which helps Whisper spell proper nouns correctly."""
    if not path or not os.path.exists(path):
        return None
    try:
        with open(path, encoding="utf-8") as fh:
            terms = [ln.strip() for ln in fh if ln.strip() and not ln.startswith("#")]
        return ", ".join(terms) if terms else None
    except OSError:
        return None


def transcribe(wav, model_name, prompt):
    from faster_whisper import WhisperModel
    # CTranslate2 runs on CPU on Apple Silicon; int8 keeps it fast on M-series.
    model = WhisperModel(model_name, device="cpu", compute_type="int8")
    segments, _info = model.transcribe(
        wav, language="ko", vad_filter=True, beam_size=5, initial_prompt=prompt,
    )
    return [{"start": s.start, "end": s.end, "text": s.text.strip()} for s in segments]


def diarize(wav, hf_token):
    from pyannote.audio import Pipeline
    import torch
    pipe = Pipeline.from_pretrained("pyannote/speaker-diarization-3.1", use_auth_token=hf_token)
    try:
        if torch.backends.mps.is_available():
            pipe.to(torch.device("mps"))  # Apple GPU for pyannote
    except Exception:
        pass
    diar = pipe(wav)
    return [(turn.start, turn.end, spk) for turn, _, spk in diar.itertracks(yield_label=True)]


def assign_speakers(segments, turns):
    def overlap(a0, a1, b0, b1):
        return max(0.0, min(a1, b1) - max(a0, b0))

    # Stable speaker labels: 화자1, 화자2, ... in first-appearance order.
    label_map, order = {}, []
    for seg in segments:
        best, best_ov = None, 0.0
        for (t0, t1, spk) in turns:
            ov = overlap(seg["start"], seg["end"], t0, t1)
            if ov > best_ov:
                best_ov, best = ov, spk
        if best is not None and best not in label_map:
            order.append(best)
            label_map[best] = f"화자{len(order)}"
        seg["speaker"] = label_map.get(best, "화자?")
    return segments


def fmt_srt(t):
    h = int(t // 3600)
    m = int((t % 3600) // 60)
    s = t % 60
    return f"{h:02d}:{m:02d}:{s:06.3f}".replace(".", ",")


def write_outputs(base, segments, diarized):
    # .txt — merge consecutive segments from the same speaker into paragraphs
    lines, cur, buf = [], None, []
    for seg in segments:
        spk = seg.get("speaker") if diarized else None
        tag = f"[{spk}] " if spk else ""
        if spk != cur:
            if buf:
                lines.append((f"[{cur}] " if (diarized and cur) else "") + " ".join(buf))
            cur, buf = spk, [seg["text"]]
        else:
            buf.append(seg["text"])
    if buf:
        lines.append((f"[{cur}] " if (diarized and cur) else "") + " ".join(buf))
    with open(base + ".txt", "w", encoding="utf-8") as fh:
        fh.write("\n\n".join(lines) + "\n")

    with open(base + ".srt", "w", encoding="utf-8") as fh:
        for i, seg in enumerate(segments, 1):
            spk = seg.get("speaker") if diarized else None
            tag = f"[{spk}] " if spk else ""
            fh.write(f"{i}\n{fmt_srt(seg['start'])} --> {fmt_srt(seg['end'])}\n{tag}{seg['text']}\n\n")

    with open(base + ".json", "w", encoding="utf-8") as fh:
        json.dump(segments, fh, ensure_ascii=False, indent=2)


SUMMARY_PROMPT = (
    "다음은 회의/통화 녹음 전사본입니다. 나중에 '이 사람과의 관계와 이력'을 찾아볼 때\n"
    "쓸 수 있도록, 정보 가치를 보존하는 기록으로 한국어로 정리하세요.\n"
    "육하원칙(누가·언제·어디서·무엇을·어떻게·왜)을 챙기고, 아래 형식(마크다운)을 지키세요:\n\n"
    "## 한 줄 요약\n\n"
    "## 인물 (누가)\n- 이름 / 역할·소속 (등장인물, 아는 범위에서)\n\n"
    "## 일시·장소 (언제·어디서)\n- \n\n"
    "## 핵심 내용 (무엇을·어떻게)\n- 주요 논의·결정·수치·고유명사\n\n"
    "## 배경·목적 (왜)\n- \n\n"
    "## 약속·할 일\n- 담당 / 기한\n\n"
    "## 기억할 사실 (기록 가치)\n- 나중에 찾아볼 구체 정보(금액·일정·연락처·관계 등)\n\n"
    "전사본에 없는 내용은 지어내지 말고, 날짜·이름·숫자는 정확히 쓰세요.\n\n---\n전사본:\n"
)


def summarize(text, model, host):
    """Summarize a transcript with a local Ollama model (open source, on-device).
    No extra Python dependency — plain HTTP."""
    body = json.dumps({"model": model, "prompt": SUMMARY_PROMPT + text, "stream": False})
    req = urllib.request.Request(
        host.rstrip("/") + "/api/generate",
        data=body.encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=1200) as resp:
        return json.loads(resp.read().decode("utf-8")).get("response", "").strip()


def process(path, args):
    base = os.path.splitext(path)[0]
    if os.path.exists(base + ".txt") and not args.force:
        return  # already transcribed
    fd, wav = tempfile.mkstemp(suffix=".wav")
    os.close(fd)
    try:
        log(f"[1/3] 전처리: {os.path.basename(path)}")
        preprocess(path, wav)
        prompt = load_vocab(args.vocab)
        log(f"[2/3] 전사 ({args.model})…")
        segments = transcribe(wav, args.model, prompt)
        diarized = False
        if args.hf_token:
            log("[3/3] 화자 구분…")
            try:
                turns = diarize(wav, args.hf_token)
                segments = assign_speakers(segments, turns)
                diarized = True
            except Exception as exc:  # noqa: BLE001
                log(f"  (화자구분 건너뜀: {exc})")
        write_outputs(base, segments, diarized)
        log(f"완료 → {base}.txt / .srt / .json" + (" (화자구분 O)" if diarized else ""))
        if args.summarize:
            try:
                log(f"[요약] {args.ollama_model} …")
                with open(base + ".txt", encoding="utf-8") as fh:
                    transcript = fh.read()
                summary = summarize(transcript, args.ollama_model, args.ollama_host)
                with open(base + ".summary.md", "w", encoding="utf-8") as fh:
                    fh.write(summary + "\n")
                log(f"요약 → {base}.summary.md")
            except Exception as exc:  # noqa: BLE001
                log(f"  (요약 건너뜀: {exc})")
    finally:
        try:
            os.remove(wav)
        except OSError:
            pass


def watch(directory, args):
    log(f"감시 시작: {directory} (새 녹음 자동 전사)")
    while True:
        try:
            names = sorted(os.listdir(directory))
        except OSError:
            names = []
        for name in names:
            path = os.path.join(directory, name)
            if (os.path.isfile(path) and os.path.splitext(name)[1].lower() in AUDIO_EXTS
                    and ".part" not in name):
                try:
                    process(path, args)
                except Exception as exc:  # noqa: BLE001
                    log(f"ERROR {name}: {exc}")
        time.sleep(args.interval)


def main():
    ap = argparse.ArgumentParser(description="Korean meeting transcription (M-series friendly)")
    ap.add_argument("input", nargs="?", help="audio file to transcribe")
    ap.add_argument("--watch", metavar="DIR", help="watch a folder and transcribe new files")
    ap.add_argument("--model", default=os.environ.get("WHISPER_MODEL", "large-v3"))
    ap.add_argument("--vocab", default=os.environ.get("VOCAB_FILE", os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "vocab.txt")))
    ap.add_argument("--hf-token", default=os.environ.get("HF_TOKEN", ""),
                    help="HuggingFace token to enable speaker diarization")
    ap.add_argument("--interval", type=float, default=10.0)
    ap.add_argument("--force", action="store_true", help="re-transcribe even if .txt exists")
    ap.add_argument("--summarize", action="store_true",
                    default=os.environ.get("SUMMARIZE", "").lower() in ("1", "true", "yes", "on"),
                    help="also write <name>.summary.md via a local Ollama model")
    ap.add_argument("--ollama-model", default=os.environ.get("OLLAMA_MODEL", "qwen2.5:7b"))
    ap.add_argument("--ollama-host", default=os.environ.get("OLLAMA_HOST", "http://localhost:11434"))
    args = ap.parse_args()

    if args.watch:
        watch(args.watch, args)
    elif args.input:
        process(args.input, args)
    else:
        ap.print_help()
        sys.exit(1)


if __name__ == "__main__":
    main()
