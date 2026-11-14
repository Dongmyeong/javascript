# telegram-user-sync

폰의 녹음 폴더를 감시하다가, 새 녹음을 **당신의 텔레그램 계정으로**(봇이 아니라)
대상 봇에게 보냅니다. 그러면 자동 업로드가 **수동 업로드와 완전히 똑같이** 보여서
(체크표시가 붙고), 맥 수신기의 `getUpdates` 폴러가 그대로 받아갑니다.

## 왜 이게 필요한가

기존 `telegram-recorder-sync` 는 **봇 API**로 파일을 올립니다(= "봇이 보낸 메시지").
그런데 텔레그램 규칙상 **봇은 자기가 보낸 메시지를 `getUpdates`로 못 봅니다.**
그래서 맥 수신기(봇 폴링)가 그 파일들을 못 받았습니다.

이 도구는 **MTProto(사용자 API)** 로 보내서, 파일이 "사용자→봇"으로 들어가게
합니다 → 맥이 수동 업로드와 똑같이 받아갑니다. 옆길(터널) 불필요.

> 큰 파일(>20MB)은 여전히 `name.partNofM` 으로 쪼갭니다. 받는 **봇의 다운로드
> 한도가 20MB**라서, 조각이 그보다 작아야 맥이 받아 합칠 수 있기 때문입니다.

## 설치 & 설정 (폰 Termux)

```bash
cd ~/javascript/telegram-user-sync
npm install            # gramjs(telegram) 설치 — 몇 분 걸릴 수 있음
cp .env.example .env
nano .env
```

### 1) API_ID / API_HASH 발급
- 브라우저로 **https://my.telegram.org** 접속 → 로그인 → **API development tools**
- 앱 하나 만들고 **api_id** 와 **api_hash** 를 `.env` 에 입력

### 2) 로그인 (한 번만)
```bash
node login.js
```
- 전화번호(+8210...), 텔레그램으로 받은 **코드**, (있으면) 2단계 비밀번호 입력
- 성공하면 `SESSION=...` 가 출력되고 `.session` 파일에도 저장됨
- 출력된 `SESSION=...` 한 줄을 `.env` 에 붙여넣기 (또는 .session 자동 사용)

### 3) 대상/폴더 확인 (.env)
- `TARGET=@pe_uploader_bot` (맥 수신기가 듣는 그 봇)
- `WATCH_DIR=/storage/emulated/0/Recordings`, `WATCH_RECURSIVE=true`

### 4) 실행
```bash
node index.js
```
`Logged in as @... Sending to @pe_uploader_bot` 가 뜨면 정상. 새 녹음이 생기면
당신 계정으로 그 봇에게 전송되고, 맥이 받아갑니다.

백그라운드 상시:
```bash
termux-wake-lock
nohup node index.js > ~/user-sync.log 2>&1 &
tail -f ~/user-sync.log
```

## 설정 항목 (.env)

| 변수 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- |
| `API_ID` / `API_HASH` | ✅ | — | my.telegram.org 에서 발급 |
| `SESSION` | ✅ | — | `node login.js` 결과 (.session 자동 사용 가능) |
| `TARGET` | ✅ | — | 보낼 대상 봇 (예: `@pe_uploader_bot`) |
| `WATCH_DIR` | ✅ | — | 감시 폴더 |
| `WATCH_RECURSIVE` |  | `false` | 하위 폴더까지 |
| `MAX_PART_BYTES` |  | `19000000` | 이보다 크면 분할(봇 20MB 다운로드 한도) |
| `POLL_INTERVAL_MS` |  | `5000` | 폴더 검사 주기 |
| `STABLE_FOR_MS` |  | `10000` | 녹음 완료 판단 대기 |

## 기존 telegram-recorder-sync 와 관계

- 이 도구가 **보내는 역할을 대신**합니다. 둘을 동시에 돌리면 중복 전송되니,
  하나만 쓰세요(이 도구 권장 — 맥이 받을 수 있으므로).
- 맥에서 큰 파일 조각 합치기는 `mac-receiver/reassemble-parts.js` 가 담당.

## 주의

- `API_HASH`, `SESSION` 은 **계정 접근 권한**입니다. 외부에 노출 금지(.env 는 gitignore).
- 보내는 주체가 "당신 계정"이라, 받는 봇 입장에선 당신이 직접 올린 것과 동일합니다.
