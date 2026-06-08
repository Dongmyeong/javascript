# mac-receiver

맥미니에서 **텔레그램으로 들어온 녹음을 직접 받아 합쳐주는** 수신기입니다.
의존성 0개, Node.js 18+ 만 있으면 됩니다. 두 가지 도구가 있습니다:

1. **`receive-telegram.js` (권장, 메인)** — 텔레그램을 직접 폴링해 새 녹음을
   inbound로 내려받고, 분할 조각을 원본으로 합칩니다. **트런넬(cloudflare 등)
   불필요**, 봇 토큰만 있으면 됩니다.
2. **`reassemble-parts.js`** — 이미 inbound에 들어온 `이름.m4a.partNofM` 조각만
   합치는 보조 스크립트(다른 경로로 조각이 들어올 때 사용).

---

## receive-telegram.js — 텔레그램 직접 수신 (권장)

폰이 모든 녹음을 텔레그램 봇으로 보내고(>20MB는 19MB 이하로 분할), 맥은 이
스크립트로 봇에서 직접 가져옵니다. trycloudflare 주소가 바뀌어 깨지는 문제가
없습니다.

```bash
cp .env.example .env
nano .env            # TELEGRAM_BOT_TOKEN, OUTPUT_DIR 입력
node receive-telegram.js
```

`Connected to Telegram as @...` 가 뜨면 정상. 새 녹음이 봇에 오면 자동으로
`OUTPUT_DIR` 에 내려받고, 조각(`.partNofM`)이 다 모이면 원본으로 합칩니다.

백그라운드 상시 실행(임시):
```bash
nohup node receive-telegram.js > ~/telegram-receiver.log 2>&1 &
tail -f ~/telegram-receiver.log
```

### 재부팅에도 자동 실행 (권장) — launchd

`nohup` 은 맥을 재부팅하면 꺼집니다. **백그라운드 상시 + 죽으면 자동 재시작 +
부팅 시 자동 시작**을 원하면 launchd 에이전트로 등록하세요:

```bash
cd ~/javascript/mac-receiver
cp .env.example .env && nano .env     # 토큰 입력 (한 번만)
sh install-launchd.sh
```

설치 스크립트가 하는 일:
- `node` 절대경로를 자동으로 찾아 plist 생성
- 수동 실행 중이던 인스턴스를 정리(중복 폴링 방지)
- `~/Library/LaunchAgents/com.dongmyeong.telegram-receiver.plist` 등록 후 즉시 시작
- `KeepAlive`(죽으면 재시작) + `RunAtLoad`(로그인/부팅 시 시작)

확인 / 로그 / 제거:
```bash
launchctl list | grep telegram-receiver     # 등록 확인
tail -f ~/telegram-receiver.log             # 로그
sh uninstall-launchd.sh                      # 제거
```

> ⚠️ 헤드리스(모니터 없는) 맥미니는 **자동 로그인**을 켜두어야 재부팅 후 사용자
> 세션이 떠서 에이전트가 시작됩니다: 시스템 설정 → 사용자 및 그룹 → 자동 로그인.

### 설정 (.env)
| 변수 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- |
| `TELEGRAM_BOT_TOKEN` | ✅ | — | 폰이 보내는 그 봇의 토큰 |
| `OUTPUT_DIR` |  | `/Users/donoh/.openclaw/media/inbound` | 저장 폴더 |
| `OFFSET_FILE` |  | `<OUTPUT_DIR>/.telegram-offset` | 마지막 처리 위치 저장 |
| `POLL_TIMEOUT_SEC` |  | `30` | 롱폴 대기(초) |
| `STABLE_FOR_MS` |  | `5000` | 조각 안정 대기(ms) |

### ⚠️ 주의
- 봇 하나당 **getUpdates 소비자는 동시에 하나만** 가능합니다. 기존에 텔레그램을
  폴링하던 다른 수신기가 있으면 **끄세요**(안 그러면 서로 업데이트를 뺏어갑니다).
  파일 id를 직접 받던(POST 방식) 기존 수신기는 폴링을 안 하므로 충돌하지 않습니다.
- 봇 API **다운로드 한도는 파일당 20MB** 입니다. 그래서 폰에서 `MAX_PART_BYTES`
  를 `19000000` 으로 두어 모든 조각이 20MB 이하가 되게 해야 합니다.

---

## reassemble-parts.js — 조각 합치기만 (보조)

이미 inbound에 들어와 있는 조각 파일(`이름.m4a.part<번호>of<총개수>`)이
**다 모이면 자동으로 원본으로 합쳐주는** 스크립트입니다.

## 왜 필요한가

텔레그램 봇은 **다운로드가 파일당 20MB로 제한**됩니다. 그래서 폰 쪽
`telegram-recorder-sync` 는 20MB를 넘는 녹음(미팅 음성 등)을 19MB 이하 조각으로
쪼개 텔레그램으로 보냅니다. 맥미니 수신기는 그 조각들을 inbound에 내려받는데,
이 스크립트가 **조각을 다시 원본 하나로 합쳐** 전사 파이프라인이 온전한 녹음을
처리할 수 있게 합니다.

```
이름.m4a.part1of3  ┐
이름.m4a.part2of3  ├─►  이름.m4a   (합친 뒤 조각은 삭제)
이름.m4a.part3of3  ┘
```

## 동작 방식

1. inbound 폴더를 `POLL_INTERVAL_MS`(기본 5초)마다 스캔
2. `이름.partNofM` 조각을 원본별로 묶음
3. **모든 조각(1..M)이 도착했고**, 각 조각이 `STABLE_FOR_MS`(기본 10초) 동안
   안 바뀌었으면(다운로드 완료) → 번호순으로 이어붙여 원본 생성
4. 합치기는 임시파일(`.reassembling`)에 쓴 뒤 원자적으로 rename, 그 후 조각 삭제
5. 같은 이름의 원본이 이미 있으면 덮어쓰지 않고 건너뜀

> 조각 파일 확장자는 `.partNofM` 이라 `.m4a` 가 아니므로, `.m4a` 확장자만 처리하는
> 전사 파이프라인은 조각을 무시하고 **합쳐진 원본만** 처리하게 됩니다.

## 실행 (맥미니)

```bash
node reassemble-parts.js /Users/donoh/.openclaw/media/inbound
```

또는 환경변수로:
```bash
INBOUND_DIR=/Users/donoh/.openclaw/media/inbound node reassemble-parts.js
```

기존 수신기와 **나란히** 켜두면 됩니다. 백그라운드 상시 실행:
```bash
nohup node reassemble-parts.js /Users/donoh/.openclaw/media/inbound > ~/reassemble.log 2>&1 &
tail -f ~/reassemble.log
```

(원하면 launchd 데몬으로 등록해 부팅 시 자동 실행할 수도 있습니다.)

## 설정 (환경변수)

| 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `INBOUND_DIR` (또는 첫 번째 인자) | `/Users/donoh/.openclaw/media/inbound` | 감시할 폴더 |
| `POLL_INTERVAL_MS` | `5000` | 스캔 주기(ms) |
| `STABLE_FOR_MS` | `10000` | 조각이 이만큼 안 변하면 다운로드 완료로 간주 |

## 폰 쪽 짝꿍 설정

이 스크립트가 의미가 있으려면, 폰 `telegram-recorder-sync/.env` 에서:
- `CHUNK_UPLOAD_URL` 비활성화(주석 처리) — 큰 파일을 직접 푸시하지 않고 텔레그램으로 보냄
- `MAX_PART_BYTES=19000000` — 맥이 받을 수 있도록 19MB 이하로 분할

## 테스트

```bash
npm test
```
