# 문자 자동응답 (SMS Auto Reply)

특정 사람이 보낸 **문자(SMS)** 에 미리 정해둔 문구로 자동 답장하는 개인용 Android 앱입니다.
갤럭시 Z 폴드7(Android 16, One UI 8)에 직접 설치해서 쓰는 것을 기준으로 만들었습니다.

- 번호마다 다른 응답 문구와 재응답 대기 시간(쿨다운) 설정
- 앱을 켜두지 않아도 동작 (매니페스트 등록 BroadcastReceiver)
- 자동응답끼리 무한 반복되지 않도록 쿨다운 + 동일 문구 감지
- 듀얼 SIM이면 문자를 받은 SIM으로 답장
- 최근 응답/건너뜀 기록 확인

SMS 전용입니다. RCS(채팅+), 카카오톡 등 다른 메신저는 대상이 아닙니다.

## 빌드 및 설치

1. [Android Studio](https://developer.android.com/studio) 최신 버전 설치
2. Android Studio에서 **Open** → 이 `sms-auto-reply` 폴더 선택 → Gradle 동기화가 끝날 때까지 대기
   (SDK Platform 36이 없다고 하면 안내에 따라 설치)
3. 폴드7에서 **설정 → 휴대전화 정보 → 소프트웨어 정보 → 빌드번호 7회 탭**으로 개발자 옵션을 켜고,
   **개발자 옵션 → USB 디버깅** 활성화
4. USB로 연결하고 Android Studio 상단에서 기기 선택 후 **Run ▶**

USB 없이 설치하려면 **Build → Generate Signed App Bundle / APK → APK**로 서명된 APK를 만들어
폰으로 옮긴 뒤 설치하세요. 개인용이라 Play 스토어 배포는 고려하지 않았습니다.
(Play 스토어는 SMS 권한을 쓰는 앱을 기본 문자 앱 등으로 제한합니다.)

## 처음 실행 시 설정

1. **권한 허용** 카드에서 SMS 수신/전송 권한 허용
2. **배터리 최적화 예외** 카드에서 이 앱을 예외로 등록
   - 추가로 **설정 → 배터리 → 백그라운드 사용 제한 → 절전 예외 앱**에 이 앱을 넣어두면 더 안전합니다.
   - **사용하지 않는 앱 절전 상태로 전환** 옵션 때문에 오래 안 열면 잠들 수 있으니, 위 예외 등록을 권장합니다.
3. 오른쪽 아래 **번호 추가** → 전화번호, 이름(선택), 자동응답 문구, 재응답 대기 시간 입력
4. 상단 **자동응답** 스위치를 켬

## 동작 방식

```
문자 수신 (SMS_RECEIVED)
  → SmsReceiver: PDU 합치기, 발신 번호/SIM 추출
  → AutoReplyEngine.decide()
      - 마스터 스위치 꺼짐?          → 건너뜀
      - 등록된 번호가 아님?          → 건너뜀
      - 내 응답 문구와 같은 내용?    → 건너뜀 (루프 방지)
      - 쿨다운 시간 안?              → 건너뜀
  → SmsSender.send() (80자 초과 시 분할 전송)
  → 마지막 응답 시각 저장, 기록 추가
```

번호 비교는 `010-1234-5678`, `01012345678`, `+82 10 1234 5678` 을 모두 같은 번호로 취급합니다.

## 프로젝트 구조

```
app/src/main/java/com/dongmyeong/smsautoreply/
├── MainActivity.kt          Compose 진입점
├── SmsReceiver.kt           문자 수신 → 판단 → 전송
├── AutoReplyEngine.kt       순수 판단 로직 (Android 의존성 없음, 단위 테스트 대상)
├── SmsSender.kt             SmsManager 래퍼 (듀얼 SIM, 분할 전송)
├── PhoneNumbers.kt          한국 번호 정규화/비교
├── data/Models.kt           Rule, ReplyLog, AppState
├── data/SettingsRepository.kt  DataStore(JSON) 저장소
└── ui/                      HomeScreen, RuleEditorDialog, Theme
```

## 테스트

```
./gradlew :app:testDebugUnitTest
```

`AutoReplyEngine`(쿨다운, 루프 방지, 미등록 번호 등)과 `PhoneNumbers`(번호 정규화)에 대한 단위 테스트가 들어 있습니다.

## 알아둘 점

- 문자 앱에 알림이 뜨는 것과 별개로, 이 앱은 문자를 가로채거나 숨기지 않습니다.
- 발신자가 전화번호가 아닌 문자(은행, 인증번호 등 알파벳 발신자)는 무조건 무시합니다.
- 재응답 대기 시간을 0으로 두면 그 번호의 모든 문자에 매번 답장합니다. 상대도 자동응답이면 요금이 폭발할 수 있으니 0은 피하세요.
