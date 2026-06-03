# telegram-recorder-sync

휴대폰(또는 PC)의 특정 폴더를 감시하다가 **새 녹음 파일이 생기면 자동으로 텔레그램 봇으로 전송**하는 작은 Node.js 프로그램입니다.

- 외부 npm 패키지 **0개** — Node.js 18+ 만 있으면 됩니다.
- 안드로이드 **Termux** 에서 바로 실행 가능 (백그라운드 상시 실행).
- 녹음이 **끝난 파일만** 전송 (파일 크기/수정시각이 안정되면 전송 → 녹음 중 전송 방지).
- 이미 보낸 파일은 다시 보내지 않음 (상태 파일로 추적).
- 전송 실패 시 지수 백오프로 자동 재시도.
- **50MB 초과 파일은 자동 분할** 전송하고, 복원용 `cat` 명령을 알려줌.
- **전송 알림/요약**: 시작 메시지, 분할 안내, 종료 시 세션 요약을 텔레그램으로 전송.

---

## 1. 텔레그램 봇 만들기 (토큰 + chat_id)

1. 텔레그램에서 **@BotFather** 를 열고 `/newbot` → 안내에 따라 봇 이름을 정하면 **봇 토큰**을 줍니다. (`123456789:ABC...` 형태)
2. 방금 만든 내 봇을 검색해서 대화를 열고 **아무 메시지나 한 번 보냅니다.** (봇이 나에게 메시지를 보내려면 내가 먼저 말을 걸어야 합니다.)
3. 브라우저에서 아래 주소를 열어 `"chat":{"id": ...}` 값을 확인합니다. 이게 **chat_id** 입니다.

   ```
   https://api.telegram.org/bot<봇토큰>/getUpdates
   ```

   > 그룹/채널로 보내려면 봇을 그 그룹에 초대한 뒤 메시지를 보내고 같은 방법으로 chat_id(그룹은 보통 `-`로 시작)를 확인하면 됩니다.

---

## 2. 안드로이드(Termux)에서 설치 — 권장

> 통화/음성 녹음 파일을 휴대폰에서 바로 자동 전송하고 싶을 때.

1. **Termux** 설치 — [F-Droid](https://f-droid.org/packages/com.termux/) 버전 권장 (구글 플레이 버전은 업데이트가 끊겼습니다).
2. Termux를 열고 아래를 실행:

   ```bash
   pkg update && pkg install nodejs git -y
   termux-setup-storage      # 저장소 접근 권한 허용 (팝업에서 "허용")
   git clone https://github.com/dongmyeong/javascript.git
   cd javascript/telegram-recorder-sync
   ```

3. 설정 파일 만들기:

   ```bash
   cp .env.example .env
   nano .env                 # 토큰, chat_id, 녹음 폴더 경로 입력 후 저장(Ctrl+O, Enter, Ctrl+X)
   ```

   - 녹음 폴더 경로는 기기마다 다릅니다. 아래로 찾아볼 수 있습니다:

     ```bash
     ls /storage/emulated/0/ | grep -iE 'record|call|sound|voice'
     ```

     흔한 위치: `/storage/emulated/0/Recordings`, `/storage/emulated/0/Call`, `/storage/emulated/0/Sounds`

4. 실행:

   ```bash
   node index.js
   ```

   `Connected to Telegram as @봇이름` 이 보이면 정상입니다. 이제 그 폴더에 새 녹음이 생기면 자동으로 텔레그램에 도착합니다.

### 백그라운드 / 화면 꺼도 계속 실행

```bash
pkg install termux-services -y      # 한 번만
```

가장 간단하게는 **Termux:Boot** 앱을 설치하고, 화면이 꺼져도 죽지 않도록 Termux 알림에서 **Acquire wakelock** 을 켠 뒤 위 `node index.js` 를 실행해 두면 됩니다.

부팅 시 자동 시작하려면 `~/.termux/boot/` 에 시작 스크립트를 두면 됩니다:

```bash
mkdir -p ~/.termux/boot
cat > ~/.termux/boot/recorder-sync <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
termux-wake-lock
cd ~/javascript/telegram-recorder-sync
node index.js
EOF
chmod +x ~/.termux/boot/recorder-sync
```

---

## 3. PC/서버에서 실행

```bash
git clone https://github.com/dongmyeong/javascript.git
cd javascript/telegram-recorder-sync
cp .env.example .env   # 값 입력
node index.js
```

---

## 설정 항목 (`.env`)

| 변수 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- |
| `TELEGRAM_BOT_TOKEN` | ✅ | — | BotFather가 준 봇 토큰 |
| `TELEGRAM_CHAT_ID` | ✅ | — | 받을 대화/그룹의 chat_id |
| `WATCH_DIR` | ✅ | — | 감시할 폴더 경로 |
| `WATCH_EXTENSIONS` |  | `.m4a,.mp3,.amr,.aac,.wav,.ogg,.opus,.3gp` | 전송할 확장자 |
| `WATCH_RECURSIVE` |  | `false` | 하위 폴더까지 감시 |
| `POLL_INTERVAL_MS` |  | `5000` | 폴더 검사 주기(ms) |
| `STABLE_FOR_MS` |  | `10000` | 이 시간 동안 파일이 안 변하면 "녹음 완료"로 보고 전송 |
| `SEND_AS_AUDIO` |  | `false` | 재생 가능한 오디오 메시지로 전송 (false면 원본 파일로) |
| `DELETE_AFTER_SEND` |  | `false` | 전송 성공 후 원본 삭제 |
| `CAPTION_TEMPLATE` |  | `🎙 {name} ({size})` | 캡션. `{name}` `{size}` `{date}` 사용 가능 |
| `STATE_FILE` |  | `.sent-state.json` | 전송 기록 저장 위치 |
| `MAX_PART_BYTES` |  | `51380224` (49MB) | 이 크기를 넘으면 파트로 분할 전송 |
| `NOTIFY_SUMMARY` |  | `true` | 시작 메시지 + 종료 시 세션 요약 전송 |
| `NOTIFY_SPLIT_INSTRUCTIONS` |  | `true` | 분할 시 복원 명령 안내 메시지 전송 |
| `NOTIFY_ON_SEND` |  | `false` | 분할되지 않은 파일도 매번 텍스트 확인 메시지 전송 |
| `FORWARD_URL` |  | (없음) | 전송 응답(file_id)을 POST할 외부 수신기 URL (맥미니 등) |

---

## 동작 방식

1. `POLL_INTERVAL_MS` 마다 `WATCH_DIR` 를 스캔합니다.
2. 지정한 확장자이고, 크기가 0이 아니며, **마지막 수정 후 `STABLE_FOR_MS` 가 지난** 파일을 "전송 대상"으로 봅니다.
3. 텔레그램 Bot API(`sendDocument` 또는 `sendAudio`)로 업로드합니다.
4. 성공하면 `STATE_FILE` 에 (경로+크기+수정시각)을 기록해 중복 전송을 막습니다.

## 큰 파일(50MB 초과) 처리

텔레그램 봇의 업로드 한도는 파일당 50MB 입니다. 이 도구는 한도를 넘는 녹음을
`MAX_PART_BYTES`(기본 49MB) 단위로 **자동 분할**해 `이름.m4a.part01of03` 처럼
순서대로 전송하고, 마지막에 복원 명령을 텔레그램으로 보내줍니다. 받은 파트들을
한 폴더에 모아 아래처럼 합치면 원본이 됩니다(분할은 단순 바이트 분할이라 무손실):

```bash
cat "녹음.m4a".part*of03 > "녹음.m4a"
```

## 외부 수신기로 자동 전달 (예: 맥미니)

`FORWARD_URL` 을 설정하면, 파일을 텔레그램에 보낸 직후 **그 응답 JSON(파일의
`file_id` 포함)을 지정한 URL로 POST** 합니다. 맥미니 같은 수신기가 이 `file_id`
로 `getFile` → 다운로드해서 원하는 폴더에 저장하는 식으로 파이프라인을 이을 수
있습니다.

```
FORWARD_URL=https://your-tunnel.example.com/telegram-file-id?token=xxxxx
```

전송되는 본문은 텔레그램 Bot API 응답 형태입니다:

```json
{ "ok": true, "result": { "document": { "file_id": "...", "file_name": "통화_녹음.m4a" } } }
```

- 전달은 **best-effort** 입니다. 수신기가 꺼져 있어 실패해도 파일은 이미 텔레그램에
  안전히 올라가 있으므로 동기화를 막지 않고 경고만 남깁니다.
- ⚠️ 봇 API의 **다운로드 한도는 파일당 20MB** 입니다(업로드는 50MB). 수신기가
  `getFile` 로 받아야 한다면 `MAX_PART_BYTES` 를 약 `19000000` 으로 낮춰
  모든 조각이 20MB 이하가 되게 하세요.
- ⚠️ `trycloudflare.com` 같은 임시 터널 URL은 **재시작할 때마다 주소가 바뀝니다.**
  바뀌면 `.env` 의 `FORWARD_URL` 을 새 주소로 고치고 다시 실행하세요.
- `FORWARD_URL` 의 토큰은 비밀입니다. `.env`(gitignore됨)에만 두고 외부에 노출하지 마세요.

## 알림 / 요약

- 시작 시: `▶️ recorder-sync 시작 …`
- 파일 분할 전송 시: `📎 … N개로 분할 전송 … 복원: cat …`
- 종료(Ctrl+C / 종료 신호) 시: `📊 세션 요약 — 파일 N개 / X MB / 청크 M개`

`NOTIFY_SUMMARY`, `NOTIFY_SPLIT_INSTRUCTIONS`, `NOTIFY_ON_SEND` 로 켜고 끌 수 있습니다.

## 테스트

```bash
npm test
```

## 라이선스

MIT
