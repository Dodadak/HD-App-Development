# 카로그 — Pleos Connect

앱을 열면 **카카오 QR 로그인**(매번, 자동 로그인 없음) → **메인 페이지**에서 세 기능으로 갑니다.

| 기능 | 코드 | 출처 |
|---|---|---|
| 운전 점수 (보험 연동 UBI) | `app/src/main/java/com/hdapp/drivingscore/` | 이 프로젝트 |
| 충전 기록 | `app/src/main/java/com/example/evbizassistant/charge/` | HD-App-Development-C-2 |
| 운행일지 | `app/src/main/java/com/example/hyundai_sdk/`, `app/src/main/java/triplog/` | Hyundai_SDK v0.14.10 |
| 로그인 · 메인 | `app/src/main/java/com/hdapp/home/` | 이 프로젝트 |

- 카카오 QR 로그인 만들기(키 발급·서버): [server/kakao-qr/README.md](../server/kakao-qr/README.md). 서버가 없으면 시연용 로그인으로 동작
- 로그인·로그아웃·주행 기록: 앱 안 `login_history.csv` (`시간,이름,번호,카카오ID,구분,내용`)
- 로그인한 사람이 운전 점수의 운전자(`이름·번호뒤4자리`)와 운행일지의 기록 주인(`kakao:카카오ID`)이 됩니다

---

# 운전 점수 (보험 연동 UBI)

주행 중 급가속·급정거·과속·깜빡이 없는 차선 변경·밤 운전을 자동으로 감지해 점수를 매기고, 보험사에 점수를 공유합니다(Mock).
앱 화면을 닫아도 백그라운드 서비스가 계속 기록합니다.

## 파일

```
app/src/main/java/com/hdapp/drivingscore/
├── DrivingScore.kt            기능 1 — 판정 규칙, 주행 기록기, 요약·배지 계산, 차량 신호, 보험사 연동, 공개 함수
├── DrivingScoreBackground.kt  기능 2 — 저장(Room), 설정, 백그라운드 서비스, 부팅 시 자동 시작
└── DrivingScoreScreen.kt      UI — 대시보드, 주행 중 화면, 주행 상세(삭제·옮기기), 보험사 공유, 운전자, 배지
app/schemas/                   Room 표 구조 (버전별, 마이그레이션 확인용)
app/src/main/res/drawable/badge_*.xml   배지 그림 32개 (받음 16 + 잠김 16)
design/badges/                 배지 디자인 원본 SVG · 업그레이드 연출 사양 (README.md)
scripts/svg2vd.py              배지 SVG → 안드로이드 벡터 변환기
app/src/test/java/com/hdapp/drivingscore/
├── DrivingRulesTest.kt        판정·점수·센서 튐·임시 저장 이어 받기
├── ScoreReportTest.kt         달력 기간·흐름·오늘 집계·배지·밤 운전·저장 변환
├── TripDbTest.kt              Room DB (Robolectric)
├── DashboardUiTest.kt         대시보드 화면 (Robolectric + Compose)
└── BadgeDrawableTest.kt       배지 그림 32개가 모두 열리는지
```

테스트는 에뮬레이터 없이 돌아갑니다: `./gradlew testDebugUnitTest`

## 함수로 부르기

```kotlin
val score = DrivingScore.get(context)  // 앱 전체에서 하나 (화면과 서비스가 같이 씀)
DrivingScoreService.start(context)     // 앱을 닫아도 계속 기록
score.state                            // StateFlow: 주행 중 여부, 이번 주행 점수, 방금 감점, 오늘 집계, 운전자
score.summary(Period.MONTH)            // 종합 점수, 항목별 점수, 고칠 점, 흐름, 연속 무감점, 배지
score.trips(Period.MONTH)              // 기간 안 주행 목록
score.scoreOf(trip)                    // 주행 한 건 점수
score.shareWithInsurer(Period.MONTH)   // 보험사 공유 (Mock, 위치·경로 제외)
score.setAutoShare(false)              // "묻지 않고 바로 보내기" 켜기/끄기 (저장됨)
score.addDriver("엄마"); score.selectDriver("나")  // 운전자별 점수 (최대 4명)
score.renameDriver("엄마", "Mom"); score.removeDriver("Mom")
score.deleteTrip(trip); score.moveTrip(trip, "엄마")
score.events                           // 감점이 생길 때마다 (Flow)
score.today()                          // 오늘 실시간 집계
score.clearAllTrips()                  // 저장된 주행 기록 모두 지우기
```

## 기간
달력 기준: 이번 주(월요일부터) · 이번 달(1일부터) · 최근 3개월(두 달 전 1일부터). "지난번과 비교"는 바로 전 같은 기간(지난주, 지난달)과.

## 배지 (10·20대 타깃)
6종류, 단계마다 모양이 진화 (1단계 샌드 → 2단계 블루 → 3단계 골드). 운전자별로 따로 모으고, 한 번 받은 단계는 기록을 지워도 유지.

| 배지 | 1단계 | 2단계 | 3단계 |
|---|---|---|---|
| 클린 스트릭 | 감점 없이 5번 연속 | 20번 | 50번 |
| 로드 러너 | 누적 100km | 1,000km | 5,000km |
| 쿨 크루저 | 최근 30일 과속 0회 + 주행 5번 | 15번 | 30번 |
| 나이트 아울 | 밤(22~6시) 감점 없이 3번 | 10번 | 30번 |
| 버터 드라이버 | 감점 없는 주행 1번 | 30번 | 100번 |
| 첫 시동 | 첫 주행 | — | — |

- 단계가 오르면 주차 후 축하 화면 1.5초 연출 (소리 없음, 시스템 애니메이션을 끄면 바로 끝 모습)
- 배지 디자인을 바꾸면: `design/badges/` 의 SVG 교체 → `python3 scripts/svg2vd.py design/badges app/src/main/res/drawable`

## 앱이 꺼져도
- 달리는 주행을 30초마다 임시 저장 → 앱이 강제로 꺼졌다 10분 안에 다시 켜지면 이어서 기록, 더 지나면 그때까지를 한 건으로 저장
- 1초에 40km/h 넘게 바뀌는 차속은 센서 튐으로 보고 감점하지 않음

## 차량 신호

| 판정 | 출처 | 권한 |
|---|---|---|
| 주행 시작·끝 | Vehicle SDK · Gear | `pleos.car.permission.CAR_DRIVE` |
| 브레이크 | Vehicle SDK · Brake | `pleos.car.permission.CAR_DRIVE` |
| 차속 (급정거·과속·차선 변경) | Android Automotive `PERF_VEHICLE_SPEED` (Vehicle SDK에 차속 없음) | `android.car.permission.CAR_SPEED` (런타임) |
| 가속 페달 (급가속) | Android Automotive `ACCELERATOR_PEDAL_COMPRESSION_PERCENTAGE` | `android.car.permission.CAR_POWERTRAIN` |
| 깜빡이 (차선 변경) | Vehicle SDK · `TurnSignal.registerTurnSignalState` | `pleos.car.permission.CAR_LIGHTS` |
| 핸들 각도 (차선 변경) | Vehicle SDK · `SteeringWheel.registerSteeringWheelAngle` | `pleos.car.permission.CAR_DRIVE` |
| 제한속도 | NaviHelper · `onCurrentRoadSpeedLimit` | `pleos.car.permission.NAVI_ROUTE` |
| 밤 운전 | 시각 22시~6시, **감점이 나온 주행의 밤 시간만** 감점 | — |

- 과속: 제한속도 + 20km/h 초과가 5초 이어지면 1회 (교통안전공단 위험운전 과속 정의)
- 제한속도를 모르면 100km/h로 봄 (120 초과 시 과속). 화면에 "제한속도 정보 없음 · 내비를 켜면 더 정확해요"
- 급가속: 가속 페달 값이 200(0~255 기준, 약 78%)을 넘는 순간 1회. 페달 신호가 없는 차는 1초에 10km/h 이상 빨라질 때
- 깜빡이 없는 차선 변경(추정): 시속 40km 이상에서 핸들을 한쪽으로 5도 넘게 꺾었다가 6초 안에 반대로 5도 넘게 되돌렸는데, 그 앞 5초부터 깜빡이가 안 켜졌을 때. Pleos SDK에 차선 정보가 없어 핸들 각도로 추정
- 급정거 12km/h/s 는 추정치 → 보험사 기준을 받으면 `DrivingRules`에서 변경

## 에뮬레이터에서 보기

1. `Pleos_Connect_v2.1.0` AVD를 **Cold boot**로 켜고 앱 목록에서 "운전 점수"를 엽니다.
2. 차속·알림 권한 팝업에서 허용합니다.
3. Virtual Console(`adb forward tcp:8088 tcp:8088` → `http://127.0.0.1:8088/`)에서 기어를 D로 바꾸고 ACCEL을 누릅니다.
4. 로그: `adb logcat -s DrivingScore`

## 아직 Mock / TODO
- 보험사 API (`InsuranceApi`)
- 급정거·차선 변경 기준값 확정 (실차 주행 기록으로)
