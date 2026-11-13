# mac-transcribe

맥미니(Apple Silicon)에서 **녹음 → 고품질 한국어 전사 + 화자 구분**을 하는
오픈소스 도구입니다. 기존 파이프라인의 전사 품질을 끌어올리는 용도예요.

```
오디오 → ffmpeg 전처리(16kHz 모노 + 음량정규화)
       → faster-whisper (large-v3, 한국어, VAD, 용어 힌트)
       → pyannote 화자 구분(선택)
       → 이름.txt / 이름.srt / 이름.json
```

## 왜 품질이 올라가나
- **large-v3** 모델 — 한국어 받아쓰기 정확도 최상급
- **음량 정규화 + 16kHz 모노** — 폰 녹음의 들쭉날쭉함을 Whisper가 좋아하는 형태로
- **VAD** — 무음 구간을 걸러 환청(hallucination) 감소
- **화자 구분** — "화자1/화자2…" 로 나눠 미팅 내용이 읽기 쉬워짐
- **용어 힌트(vocab.txt)** — 회사명·사람이름 같은 고유명사 인식률 ↑

## 설치 (맥 터미널)

```bash
# 1) ffmpeg
brew install ffmpeg

# 2) 파이썬 패키지 (가상환경 권장)
cd ~/javascript/mac-transcribe
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
```

### 화자 구분을 쓰려면 (선택, 무료)
pyannote 모델은 한 번 동의 + 토큰이 필요합니다:
1. https://huggingface.co 가입 → **Settings → Access Tokens** 에서 토큰 발급
2. 아래 두 모델 페이지에서 **"Agree/Accept"** 클릭(약관 동의):
   - `pyannote/speaker-diarization-3.1`
   - `pyannote/segmentation-3.0`
3. 실행할 때 토큰 전달: `export HF_TOKEN=hf_xxx`

> 화자 구분이 필요 없으면 `HF_TOKEN` 없이 실행하면 됩니다(전사만 수행).

## 사용법

**파일 하나 전사:**
```bash
python transcribe.py "/Users/donoh/.openclaw/media/inbound/미팅.m4a"
```
→ 같은 폴더에 `미팅.txt`(화자 구분본) / `미팅.srt`(자막) / `미팅.json` 생성

**폴더 감시(새 녹음 자동 전사):**
```bash
export HF_TOKEN=hf_xxx   # 화자구분 원하면
python transcribe.py --watch /Users/donoh/.openclaw/media/inbound
```
→ `.part...` 조각은 건너뛰고, 합쳐진 원본만 전사합니다. 이미 `.txt` 있으면 skip.

**용어 힌트:**
```bash
cp vocab.example.txt vocab.txt
# vocab.txt 에 참석자/회사명 등을 한 줄씩 추가
```

## 옵션
| 옵션 | 기본값 | 설명 |
| --- | --- | --- |
| `--model` | `large-v3` | 더 빠르게: `large-v3-turbo`, `medium` |
| `--hf-token` / `HF_TOKEN` | (없음) | 있으면 화자 구분 ON |
| `--vocab` | `vocab.txt` | 용어 힌트 파일 |
| `--interval` | `10` | 감시 주기(초) |
| `--force` | | 이미 전사된 것도 다시 |

## M4 성능 메모
- faster-whisper 는 Apple Silicon에서 **CPU(int8)** 로 돕니다(M4면 충분히 빠름,
  1시간 회의 ≈ 수 분~십수 분, 백그라운드 처리엔 넉넉).
- 더 빠른 GPU 전사를 원하면 **`mlx-whisper`**(Apple MLX, Metal 가속) 로 전사만
  교체할 수 있습니다. 화자 구분(pyannote)은 그대로 MPS로 돌립니다.

## 자동 요약 (로컬 Ollama, 오픈소스)

전사본 → **회의 요약 / 결정사항 / 할 일 / 쟁점** 을 로컬 LLM으로 뽑습니다.
온디바이스라 외부로 안 나가고 무료입니다.

```bash
# 1) Ollama 설치 + 모델 받기 (한국어 강한 모델 권장)
brew install ollama
ollama serve &              # 백그라운드 데몬
ollama pull qwen2.5:7b      # 또는 exaone3.5:7.8b (LG, 한국어 특화), gemma2:9b

# 2) 전사 + 요약 한 번에
python transcribe.py --summarize "/path/미팅.m4a"
#   또는 감시 모드에서
SUMMARIZE=1 python transcribe.py --watch /Users/donoh/.openclaw/media/inbound
```
→ `미팅.summary.md` (요약) 가 추가로 생성됩니다.

| 옵션 | 기본값 | 설명 |
| --- | --- | --- |
| `--summarize` / `SUMMARIZE=1` | off | 요약 생성 |
| `--ollama-model` / `OLLAMA_MODEL` | `qwen2.5:7b` | 요약 모델 (`exaone3.5:7.8b` 등) |
| `--ollama-host` / `OLLAMA_HOST` | `http://localhost:11434` | Ollama 주소 |

> Ollama가 안 떠 있으면 요약만 자동 생략되고 전사는 정상 완료됩니다.

## 다음 단계 (원하면 추가)
- 기존 openclaw 파이프라인에 이 전사·요약 단계를 끼워넣기
- 카카오톡 대화(.txt) 요약도 같은 요약기로 처리
