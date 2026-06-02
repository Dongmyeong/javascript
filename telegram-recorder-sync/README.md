# telegram-recorder-sync

휴대폰(또는 PC)의 특정 폴더를 감시하다가 **새 녹음 파일이 생기면 자동으로 텔레그램 봇으로 전송**하는 작은 Node.js 프로그램입니다.

- 외부 npm 패키지 **0개** — Node.js 18+ 만 있으면 됩니다.
- 안드로이드 **Termux** 에서 바로 실행 가능 (백그라운드 상시 실행).
- 녹음이 **끝난 파일만** 전송 (파일 크기/수정시각이 안정되면 전송 → 녹음 중 전송 방지).
- 이미 보낸 파일은 다시 보내지 않음 (상태 파일로 추적).
- 전송 실패 시 지수 백오프로 자동 재시도.

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

---

## 동작 방식

1. `POLL_INTERVAL_MS` 마다 `WATCH_DIR` 를 스캔합니다.
2. 지정한 확장자이고, 크기가 0이 아니며, **마지막 수정 후 `STABLE_FOR_MS` 가 지난** 파일을 "전송 대상"으로 봅니다.
3. 텔레그램 Bot API(`sendDocument` 또는 `sendAudio`)로 업로드합니다.
4. 성공하면 `STATE_FILE` 에 (경로+크기+수정시각)을 기록해 중복 전송을 막습니다.

> 텔레그램 봇의 일반 업로드 파일 크기 제한은 50MB 입니다. 더 큰 파일은 분할하거나 다른 방식이 필요합니다.

## 테스트

```bash
npm test
```

## 라이선스

MIT
