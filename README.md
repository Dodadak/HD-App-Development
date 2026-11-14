# HDapp

Pleos Connect(AAOS) 법인차용 앱. 운전 점수 / 충전 기록 / 운행일지 통합 + 카카오 QR 로그인(mock).

- package: `com.hdapp`
- minSdk 26 / targetSdk 34
- Kotlin 1.9, Jetpack Compose, Room

## Install

Pleos Connect 에뮬레이터 또는 차량에서만 설치됨 (`android.hardware.type.automotive` 필수).

```bash
adb install -r apk/HDapp-debug.apk
adb shell pm grant --user 10 com.hdapp android.car.permission.CAR_SPEED
```

## Usage

- 실행 시 매번 로그인 (자동 로그인 없음)
- QR 로그인은 mock → `다른 아이디로 로그인`에서 이름/번호로 로그인
- `관리자로 로그인` 체크 시 로그인 기록 열람 가능 (mock 빌드 한정)
- 메인 상단 계정 버튼으로 계정 변경
- 기어 D → 주행 기록 시작, P → 저장

## Virtual Console

```bash
adb forward tcp:8088 tcp:8088
# http://127.0.0.1:8088/
```

- 급정거: 속도 올린 후 BRAKE
- 깜빡이 없는 차선 변경: 40km/h 이상에서 Steering Angle ±5° 이상 왕복 (6s 이내)
- 깜빡이 켠 차선 변경: Turn Signal State → Blink 후 동일 동작 (감점 없음)
- 충전: Energy → Charge Switch On → SOC 변경 → Off

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
- `ai.pleos.playground:TextToSpeech:3.3.1` — voice
- `android.car` — `PERF_VEHICLE_SPEED`, `ACCELERATOR_PEDAL_COMPRESSION_PERCENTAGE`

## Known issues

- debug 서명 APK
- 카카오 QR 로그인, 보험사 전송 mock
- 번호 로그인 SMS 인증 없음
- 차선 변경은 steering 기반 추정 (차선 API 없음)
- 에뮬레이터(API 34)는 accelerator pedal 미지원 → 속도 변화로 판정
- 데이터는 기기 로컬 저장만 함

상세 구조·함수 목록은 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).
