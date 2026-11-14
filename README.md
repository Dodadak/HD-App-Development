# 카로그

> 법인차 운전자를 위한 업무 비서 — 운전 점수 · 충전 기록 · 운행일지를 로그인 한 번으로 자동 기록하는 Pleos Connect 앱

- 팀: 카로그랩
- package: `com.hdapp`
- minSdk 26 / targetSdk 34
- Kotlin 1.9, Jetpack Compose, Room

## APK

[`apk/카로그랩_카로그_release.apk`](apk/카로그랩_카로그_release.apk) — release 빌드 (심사용, debug 키로 서명)

## Test environment

| 항목 | 값 |
|---|---|
| 에뮬레이터 | Pleos Connect v2.1.0 (AVD `Pleos_Connect_v2.1.0`, Android 14 / API 34, arm64-v8a) |
| 화면 규격 | 2560 × 1440, 160 dpi |
| 차량 입력 | 에뮬레이터 Virtual Console (`http://127.0.0.1:8088/`) |
| 빌드 | Android Studio / Gradle 8.7, AGP 8.5, **JDK 17~21** (JDK 26은 빌드 실패) |

실제 차량 없이 에뮬레이터만으로 전체 기능 확인 가능. API 키·서버 설정 없이 바로 실행됨 (카카오 로그인은 시연용 mock).

## Install

Pleos Connect 에뮬레이터 또는 차량에서만 설치됨 (`android.hardware.type.automotive` 필수).

```bash
adb install -r apk/카로그랩_카로그_release.apk
adb shell pm grant --user 10 com.hdapp android.car.permission.CAR_SPEED
```

`CAR_SPEED` 권한은 앱 안에서 "차속 신호 없음 · 눌러서 허용"을 눌러도 받을 수 있음.

## Build

```bash
./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

`local.properties`에 `sdk.dir`만 있으면 됨. 선택: `kakaoQrServer=`(실제 카카오 QR 서버, [server/kakao-qr](server/kakao-qr)), `adminPhones=`(관리자 번호).

## Usage

- 실행 시 매번 로그인 (자동 로그인 없음)
- QR 로그인은 mock → `다른 아이디로 로그인`에서 이름/번호로 로그인
- `관리자로 로그인` 체크 시 로그인 기록 열람 가능 (mock 빌드 한정)
- 메인 상단 계정 버튼으로 계정 변경
- 기어 D → 주행 기록 시작, P → 저장

## Background

로그인 후에는 화면을 안 열어도(다른 앱 사용 중이어도) 3개 기능 모두 기록됨. `DrivingScoreService`(foreground) 하나가 같이 돌림.

| 기능 | 시작 | 끝 |
|---|---|---|
| 운전 점수 | 기어 D | 기어 P (자동 저장) |
| 충전 기록 | Charge Switch On | Off (자동 저장) |
| 운행일지 | D + 주행거리 증가 | P 5초 → `운행 종료 확인` + 분류는 직접 |

- 재부팅(차 재시동) 시 서비스 자동 시작 → 운행일지는 마지막 로그인 운전자 이름으로 이어서 기록 (`TripLogBackground.resume`)
- 마지막 운전자는 ID·이름만 저장 (`triplog_background` prefs, 관리자 권한 X)
- 로그인 화면으로 돌아가도 기록 안 끊김. 다른 사람이 로그인하면 그 사람으로 전환
- 앞 운행이 종료 확인 전이면 운전자 전환 불가 → 앞 사람 기록 유지

## Virtual Console

```bash
adb forward tcp:8088 tcp:8088
# http://127.0.0.1:8088/
```

- 급정거: 속도 올린 후 BRAKE
- 깜빡이 없는 차선 변경: 40km/h 이상에서 Steering Angle ±5° 이상 왕복 (6s 이내)
- 깜빡이 켠 차선 변경: Turn Signal State → Blink 후 동일 동작 (감점 없음)
- 충전: Energy → Charge Switch On → SOC 변경 → Off
- 운행일지: 에뮬레이터 주행거리는 속도로 안 늘어남 → Odometer 슬라이더 직접 올려야 함

콘솔 탭이 백그라운드로 가면 입력이 안 먹음.

## Scoring

| 항목 | 기준 | 비중 |
|---|---|---|
| 급가속 | 가속 페달 200/255 초과 | 20% |
| 급정거 | 1초에 12km/h 이상 감속 | 25% |
| 과속 | 제한속도 +20km/h, 5초 이상 | 25% |
| 차선 변경 | 깜빡이 없이 핸들 S자 (40km/h 이상) | 15% |
| 밤 운전 | 22~06시 감점 주행 | 15% |

점수는 100km당 횟수 기준, 최소 10km로 계산.

## SDK / API

- `ai.pleos.playground:Vehicle:2.3.2` — Gear, Brake, TurnSignal, SteeringWheel, EvBattery
- `ai.pleos.playground:NaviHelper:3.0.5` — speed limit
- `android.car` — `PERF_VEHICLE_SPEED`, `ACCELERATOR_PEDAL_COMPRESSION_PERCENTAGE`

## Known issues

- release APK도 debug 키로 서명 (배포용 키 없음)
- 카카오 QR 로그인, 보험사 전송 mock
- 번호 로그인 SMS 인증 없음
- 차선 변경은 steering 기반 추정 (차선 API 없음)
- 에뮬레이터(API 34)는 accelerator pedal 미지원 → 속도 변화로 판정
- 데이터는 기기 로컬 저장만 함
- 로그인 없이 운전하면 운행일지가 마지막 운전자 이름으로 남음 (운전자 오기록 가능)

상세 구조·함수 목록은 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).
