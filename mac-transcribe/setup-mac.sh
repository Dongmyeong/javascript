#!/bin/bash
# One-shot setup + run for mac-transcribe on Apple Silicon (M-series).
#
#   bash setup-mac.sh                                  # 전사만
#   WITH_OLLAMA=1 SUMMARIZE=1 bash setup-mac.sh         # 전사 + 자동요약
#   HF_TOKEN=hf_xxx bash setup-mac.sh                   # 전사 + 화자구분
#   INBOUND=/경로 bash setup-mac.sh                      # 감시 폴더 변경
#
# Safe to re-run. Does NOT prompt (answers handled non-interactively).
set -e

cd "$(dirname "$0")"
INBOUND="${INBOUND:-/Users/donoh/.openclaw/media/inbound}"
OLLAMA_MODEL="${OLLAMA_MODEL:-qwen2.5:7b}"

echo "===== mac-transcribe 설치/실행 ====="
echo "감시 폴더: $INBOUND"

if ! command -v brew >/dev/null 2>&1; then
  echo "ERROR: Homebrew 가 필요합니다 -> https://brew.sh"
  exit 1
fi

echo "[1/4] ffmpeg"
command -v ffmpeg >/dev/null 2>&1 || brew install ffmpeg

echo "[2/4] python 가상환경 + 패키지 (torch 때문에 시간이 좀 걸립니다)"
python3 -m venv .venv
# shellcheck disable=SC1091
source .venv/bin/activate
python -m pip install --upgrade pip >/dev/null
pip install -r requirements.txt

if [ "${WITH_OLLAMA:-0}" = "1" ] || [ "${SUMMARIZE:-0}" = "1" ]; then
  echo "[3/4] Ollama (자동요약용) + 모델 $OLLAMA_MODEL"
  command -v ollama >/dev/null 2>&1 || brew install ollama
  pgrep -f "ollama serve" >/dev/null 2>&1 || (nohup ollama serve >/tmp/ollama.log 2>&1 &)
  sleep 3
  ollama pull "$OLLAMA_MODEL" || echo "  (모델 받기 실패 — 나중에 'ollama pull $OLLAMA_MODEL')"
else
  echo "[3/4] Ollama 건너뜀 (요약 미사용)"
fi

echo "[4/4] 전사 감시 시작"
mkdir -p "$INBOUND"
pkill -f "transcribe.py --watch" 2>/dev/null || true
sleep 1
# HF_TOKEN / SUMMARIZE / OLLAMA_MODEL 는 환경변수로 그대로 전달됨
OLLAMA_MODEL="$OLLAMA_MODEL" nohup python transcribe.py --watch "$INBOUND" > ~/transcribe.log 2>&1 &
sleep 2

echo ""
if pgrep -f "transcribe.py --watch" >/dev/null 2>&1; then
  echo "✅ 전사 감시가 백그라운드로 시작되었습니다."
else
  echo "⚠️ 시작 실패 — 아래 로그 확인:"
fi
echo "   로그 보기:  tail -f ~/transcribe.log"
echo "   중지:      pkill -f 'transcribe.py --watch'"
echo "   상태:      pgrep -f transcribe.py"
