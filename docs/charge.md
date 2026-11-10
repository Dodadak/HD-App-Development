# 충전 파트 (charge) 사용법

충전기를 꽂고 빼는 것을 자동으로 감지해서 충전 기록(시작·종료 시각, 시작·종료 %)을 저장한다.

## 필요한 설정
- 의존성: `implementation("ai.pleos.playground:Vehicle:2.3.2")`
- 매니페스트 권한: `<uses-permission android:name="pleos.car.permission.CAR_ENERGY" />`
- 파일: `charge` 패키지 폴더를 통째로 복사 (package 줄만 프로젝트에 맞게 수정)

## 기본 사용
```kotlin
val charge = ChargeHost.app(applicationContext)

charge.setListener(object : ChargeListener {
    override fun onStateChanged(state: ChargeState) { /* 게이지, 상태 표시 갱신 */ }
    override fun onSessionSaved(session: ChargeSession) { /* 기록 목록 갱신 */ }
})
charge.start()      // 자동 감지 시작 (약 1.5초 뒤부터 동작)

// 화면 종료 시
charge.setListener(null)
charge.release()
```

## 주요 함수
| 함수 | 설명 |
|---|---|
| `start()` / `stop()` | 자동 감지 시작 / 중지 |
| `release()` | 감지 중지 + Vehicle 연결 해제 (앱 종료 시) |
| `state` | 지금 상태: `ChargeState.Idle(currentPercent)` 또는 `ChargeState.Charging(startTimeMs, startPercent, currentPercent)` |
| `records()` | 저장된 충전 기록 목록 (최근 순) |
| `monthlySummary(year, month)` | 그 달의 충전 건수, 총 충전량(%) |
| `delete(id)` / `deleteAll()` | 기록 삭제 |

## 감지 방식
- 충전 스위치(`getEvChargeSwitchState`)를 5초마다 확인: false→true면 시작, true→false면 종료
- 배터리 %는 변화 알림(`registerEvBatteryStateOfCharge`)으로 받음
- 충전량이 0 이하면 저장하지 않음
- 저장 위치: 앱 내부 `charge_sessions.json`

## 한계
- 앱이 켜져 있을 때만 감지
- 시작·종료 시각이 최대 5초 늦을 수 있음
- 충전 스위치는 "충전 설정값"이라 실차에서는 실제 충전 여부와 다를 수 있음

## 터미널 명령어 (Virtual Console 테스트용)
`adb`를 못 찾으면 `(SDK 경로)\platform-tools\adb.exe` 전체 경로로 실행

| 목적 | 명령 |
|---|---|
| Virtual Console 연결 (에뮬레이터 켤 때마다) | `adb forward tcp:8088 tcp:8088` 후 브라우저에서 `http://127.0.0.1:8088/` |
| 연결된 기기 확인 | `adb devices` |
| adb 재시작 (연결 오류 시) | `adb kill-server` → `adb start-server` |
| 충전 로그만 보기 | `adb logcat -s EvCharge` |
| 앱 완전 삭제 (예전 버전이 남아 있을 때) | `adb uninstall com.example.evbizassistant` |

- 브라우저에서 빈 응답(`ERR_EMPTY_RESPONSE`)이 나오면: Device Manager → 에뮬레이터 ⋮ → Cold Boot Now → 2~3분 기다린 뒤 forward 다시 실행

## 테스트 순서 (Virtual Console → Energy)
1. Battery SOC 60%, Charge Switch Off
2. 앱 실행 → "대기 중 · 배터리 60%"
3. Charge Switch On → "충전 중"
4. Battery SOC 85%로 올리기 → "충전 중 · 85% (+25%)"
5. Charge Switch Off → 기록 1건 저장 (+25%)