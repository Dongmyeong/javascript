# telegram-user-sync-py (Telethon)

`telegram-user-sync` 의 **Python(Telethon) 버전**입니다. gramjs(Node)가 Termux의
새 Node에서 네이티브 빌드로 막힐 때 쓰는 안정적인 대안이에요. 기능은 동일합니다:

폰의 녹음 폴더를 감시하다가, 새 녹음을 **당신의 텔레그램 계정으로** 대상 봇에게
보냅니다 → 수동 업로드와 똑같이 보여서, 맥 수신기의 `getUpdates` 폴러가 받아갑니다.
20MB 초과 파일은 `name.partNofM` 으로 쪼개 보냅니다(봇 다운로드 20MB 한도).

## 설치 & 실행 (폰 Termux)

```bash
cd ~/javascript/telegram-user-sync-py
pkg install python -y          # 이미 있으면 생략
pip install -r requirements.txt   # telethon 설치 (네이티브 빌드 없음, 안정적)
cp .env.example .env
nano .env                      # API_ID, API_HASH, TARGET, WATCH_DIR 입력
```

`.env` 값:
- `API_ID`, `API_HASH` → https://my.telegram.org → API development tools
- `TARGET=@pe_uploader_bot` (맥 수신기가 듣는 봇)
- `WATCH_DIR=/storage/emulated/0/Recordings`, `WATCH_RECURSIVE=true`

### 첫 실행 (로그인 — 한 번만 대화형)
```bash
python sync.py
```
- 전화번호(+8210...), 텔레그램으로 받은 **코드**, (있으면) 2단계 비밀번호 입력
- 로그인되면 `user.session` 파일이 생기고 바로 감시를 시작합니다
- `Logged in as @... Sending to @pe_uploader_bot` 가 뜨면 정상

### 백그라운드 상시 실행 (로그인 후)
```bash
termux-wake-lock
nohup python sync.py > ~/user-sync.log 2>&1 &
tail -f ~/user-sync.log
```

## 설정 (.env)

| 변수 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- |
| `API_ID` / `API_HASH` | ✅ | — | my.telegram.org 발급 |
| `TARGET` | ✅ | — | 보낼 대상 봇 (예: `@pe_uploader_bot`) |
| `WATCH_DIR` | ✅ | — | 감시 폴더 |
| `WATCH_RECURSIVE` |  | `false` | 하위 폴더까지 |
| `MAX_PART_BYTES` |  | `19000000` | 이보다 크면 분할(봇 20MB 한도) |
| `POLL_INTERVAL_MS` |  | `5000` | 폴더 검사 주기 |
| `STABLE_FOR_MS` |  | `10000` | 녹음 완료 판단 대기 |

## 주의
- 동시에 보내는 도구는 **하나만** 쓰세요(중복 방지). 봇으로 보내던 기존
  `telegram-recorder-sync` 는 끄세요.
- `API_HASH`, `user.session` 은 **계정 접근 권한**입니다. 외부 노출 금지(.gitignore 처리됨).
- 큰 파일 조각 합치기는 맥에서 `mac-receiver/reassemble-parts.js` 가 담당.
