# mac-receiver / reassemble-parts

맥미니의 **inbound 폴더**를 감시하다가, 폰에서 분할 전송된 조각 파일
(`이름.m4a.part<번호>of<총개수>`)이 **다 모이면 자동으로 원본으로 합쳐주는**
작은 스크립트입니다. 의존성 0개, Node.js 18+ 만 있으면 됩니다.

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
