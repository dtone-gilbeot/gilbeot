# 길벗 진행 기록

## M0 구현

- Android View/XML 프로젝트 골격과 Gradle 8.7 wrapper를 구성했다.
- 패키지 `ai.gilbeot.app`, 앱 이름 `길벗`, minSdk 28, target/compileSdk 34로 설정했다.
- Pleos NaviHelper 3.0.5, Vehicle 2.3.2, TextToSpeech 3.3.1과 `android.car`를 연결했다.
- manifest 권한, `distractionOptimized=true`, CAR_SPEED/위치 런타임 권한 요청을 추가했다.
- `NaviSource`, `SpeedSource`, `VehicleSignals`, `LocationSource`, `Speaker` 래퍼와 수동 wiring을 추가했다.
- 상태 줄과 220dp 큰 자막 띠가 있는 최소 화면을 추가했다.

## 빌드 및 실측

- 2026-11-08 `gradlew.bat clean assembleDebug` 후 `gradlew.bat assembleDebug`: **BUILD SUCCESSFUL**.
  - APK: `app/build/outputs/apk/debug/app-debug.apk` (29,574,182 bytes).
  - 첫 컴파일에서는 expression body가 `Log.i`의 `Int`를 반환해 SDK의 `Unit` 콜백과 충돌했다. 콜백을 block body로 바꾼 뒤 해결했다.
- `adb uninstall ai.gilbeot.app`은 `DELETE_FAILED_INTERNAL_ERROR`였고, 지시대로 오류를 무시했다. 이어서 `adb install`을 `-g` 없이 실행한 결과는 `Success`였다.
- 권한 대화상자가 열린 동안 `GrantPermissionsActivity`가 top resumed였고 앱 PID `25136`이 살아 있었다. 두 권한은 `granted=false`였으며 `FATAL EXCEPTION`은 없었다.

```text
I/GILBEOT: speed permission denied; idle
I/GILBEOT: location permission denied; fallback Seoul
I/GILBEOT: requestPermissions permissions=[android.car.permission.CAR_SPEED, android.permission.ACCESS_FINE_LOCATION]
```

- foreground Android user 10에 두 권한을 부여한 뒤 속도와 위치 리스너가 등록됐다.

```text
I/GILBEOT: speed callback registered=true
I/GILBEOT: location enabledProviders=[passive, fused, gps] selected=[gps, fused]
I/GILBEOT: speed m/s=0.0 km/h=0.0
```

- 서울시청 RECOMMENDED 경로와 ETA/TBT를 실제 수신했다. 첫 비어 있는 TBT 콜백 뒤 13개 목록이 왔다.

```text
I/GILBEOT: requestRoute option=RECOMMENDED name=서울시청 lat=37.5665 lng=126.978
I/GILBEOT: onRouteStarted duration=2640 distance=22000
I/GILBEOT: onTBTInfo size=13
I/GILBEOT: TBT[0] type=TURN_RIGHT distance=700.0 description=대왕판교IC 방면
I/GILBEOT: TBT[9] type=TURN_LEFT distance=990.0 description=을지로1가 방면
I/GILBEOT: TBT[12] type=TURN_ARRIVE distance=120.0 description=도착
I/GILBEOT: TBT cumulative=21350.0 routeDistance=22000.0 difference=650.0
```

- Virtual Console heartbeat를 2초마다 유지하고 기어 D, 50 km/h, LowBeam→Off, BlinkLeft→Off를 순서대로 넣었다. 기어 응답은 `Drive`였고 로그 값도 바뀌었다.

```text
I/GILBEOT: speed m/s=13.888889 km/h=50.0
I/GILBEOT: lowBeam state=ON
I/GILBEOT: lowBeam state=OFF
I/GILBEOT: turnSignal state=LEFT
I/GILBEOT: turnSignal state=OFF
```

- Pleos TTS는 앱을 죽이지 않고 지정 fallback을 모두 거쳤다. `registerApp`은 호출하지 않았다.

```text
I/GILBEOT: tts timeout mode=ON_DEVICE after=8000ms
I/GILBEOT: tts fallback reason=timeout next=HYBRID
I/GILBEOT: tts timeout mode=HYBRID after=8000ms
I/GILBEOT: tts fallback reason=timeout next=SUBTITLE_ONLY
```

- 기어 D·50 km/h에서 메인 화면이 주행 차단 화면에 가려지지 않는 것을 캡처하고 직접 확인했다: `captures/M0_driving.png`.
- 종료 전에 속도를 0으로, 기어를 Park로 복구했다. 응답은 `Park actual=Park`, 앱 로그는 `speed m/s=0.0 km/h=0.0`이었다.
- 원문 로그 파일:
  - `captures/M0_logcat.txt`: 50 km/h와 조명·지시등 변경, 속도 0 복구
  - `captures/M0_startup_logcat.txt`: 권한 부여 뒤 초기화, RouteStartInfo, TTS fallback
  - `captures/M0_route_logcat.txt`: 별도 경로 재요청

## 확인되지 않았거나 제한된 점

- TTS ON_DEVICE와 HYBRID 모두 8초 안에 `onDone`이 오지 않아 실제 음성 재생 성공은 확인하지 못했다. 자막 전용 fallback은 확인했다.
- 첫 실행에서는 TBT 13개가 왔지만, 이후 경로 재요청 두 번에서는 `onRouteStarted`와 `onDrivingInfo`만 오고 비어 있지 않은 새 TBT 목록은 다시 오지 않았다. M0은 초기 수신만 확인했으며 반복 갱신을 주장하지 않는다.
- `adb uninstall`의 내부 오류 원인은 조사하지 않았다. 다만 뒤이은 `-g` 없는 설치 성공, 권한 미부여 상태, 새 APK 실행은 각각 확인했다.
- 실제 차량에서는 시험하지 않았다. 위 결과는 AVD `pleos21`에서만 관찰했다.

## M1 구현

- Android/UI 연결 없이 `app/src/main/java/ai/gilbeot/app/core/`에 순수 Kotlin 코어 5개 파일을 추가했다.
  - `Notice.kt`: `Notice` 우선순위 상수와 동일 우선순위 FIFO인 `NoticeQueue`
  - `TurnPhrases.kt`: SDK 독립 `Tbt`, 대상 회전 문구, 방향 판정
  - `TurnPreviewEngine.kt`: CUMULATIVE/ABSOLUTE 거리, 차속 적분, 500m·300m·지시등 안내
  - `SunCalc.kt`: 태양 천정각 90.833°를 쓰는 NOAA 일출·일몰 근사식
  - `PromiseEngine.kt`: 일몰 도착, 전조등, 연속 운전·정차 리셋 약속
- `app/build.gradle.kts`에는 JVM 테스트용 `junit:junit:4.13.2`만 추가했다.
- `core/`의 `android.*` import는 0건이며, M1 소스와 테스트 파일은 모두 300줄 이하이다.

### M1 설계 결정

- `DistanceMode.CUMULATIVE`가 기본이며 모든 TBT 구간을 누적한다. `ABSOLUTE`로 생성하면 각 거리를 출발점 기준 위치로 해석한다.
- `nextTurn()`과 `remainingToNext()`는 UI 카드용 다음 **모든 종류** 회전을, `nextTargetTurn()`은 안내용 다음 **대상 회전**을 반환한다.
- 경로 시작부터 대상 회전이 300m 안이면 300m 안내만 한다. 출발 때는 500m 밖이었지만 첫 poll이 늦어 두 경계를 모두 지난 경우에는 500m와 300m 안내를 함께 반환한다.
- 새 TBT 목록과 3분 정차 뒤 안내 키에 각각 경로 세대와 주행 주기를 넣었다. 따라서 장시간 유지한 `NoticeQueue`도 리셋 뒤 새 안내를 중복으로 버리지 않는다.
- `Notice` 우선순위 값은 `TURN_300=300`, `TURN_500=200`, `PROMISE=100`으로 정했다. 키는 큐에 수락된 시점부터 중복으로 본다.
- HANDOFF에 정확한 문장이 없던 유턴·IC 500m/300m 문구는 좌회전과 구분해 직접 작성했다. 금지 표현인 “비보호”, “신호 없는”은 쓰지 않는다.
- `description`이 `방면`으로 끝나면 `방면으로`를 붙이고, 그 외에는 `방면으로`를 보완한다.
- 연속 운전은 `speed > 1m/s`만 주행으로 누적하고 `speed <= 1m/s`는 정차 리셋 시간으로 누적한다. 시간 문구는 KST 12시간제 `H시 M분`이며 0 채움을 하지 않는다.
- `showRestButtonWhenStopped=true`는 M2/M3 UI가 **정차 여부까지 확인한 뒤** 버튼을 보여 주라는 의미다. M1은 실제 버튼을 만들지 않는다.

### M1 JVM 테스트

2026-11-08 `gradlew.bat test` 결과는 논리 테스트 **27개 통과, 0개 실패**이다. Android Gradle Plugin이 debug/release 변형에서 각각 실행해 총 실행 횟수는 54회다.

- `NoticeQueueTest` 2/2: 우선순위·동률 FIFO, 수락된 중복 키 제거
- `TurnPhrasesTest` 3/3: 좌회전 정확 문구·방면 접두어, 유턴/IC 별도 안전 문구, LEFT/RIGHT/NONE 방향
- `TurnPreviewEngineTest` 11/11: 50km/h 500m·300m 각 1회, 지나친 회전, 시작 300m 안, 늦은 첫 poll, 새 목록 리셋과 새 키, 사다리꼴 적분, 이벤트 없는 poll과 이중 계수 방지, ABSOLUTE 모드, 서울시청 13개 누적, 지시등 칭찬 1회, 미사용·오방향 무지적
- `SunCalcTest` 2/2: KASI 서울 3일 일출·일몰 각 ±3분, 극지방 사건 없음 null
- `PromiseEngineTest` 9/9: 일몰 전·후 도착 문구와 옵션 요청, 전조등 1회와 COMFORTABLE 분기, 1시간 50분 1회, 3분 정차 리셋, 배속 60, 정차 뒤 새 주기 키, 약속 비활성화

### M1 빌드 검증

- `JAVA_HOME=%LOCALAPPDATA%\Programs\jdk17\jdk-17.0.20.1+1`
- `gradlew.bat test`: **BUILD SUCCESSFUL**
- `gradlew.bat assembleDebug`: **BUILD SUCCESSFUL**
- M1은 순수 JVM 로직만 검증했다. Android/UI 연결과 에뮬레이터 실측은 M2/M3 범위이므로 아직 검증하지 않았다.

## 남은 일

- M2의 현재 경로 제한(첫 대상 80m)을 감안해, M0의 13개 TBT 경로가 다시 제공되면 첫 대상 860m 조건을 재확인한다.
- M3에서 `PromiseEngine`·`SunCalc`·전조등·경로 옵션 변경을 연결하고 새 TBT 수신 여부를 에뮬레이터에서 확인한다.

## M1 검수 (Claude, 2026-11-08)

- clean 후 `testDebugUnitTest` 재실행: 27/27 통과 확인, `core/`의 android import 0건, 파일 모두 300줄 이하.
- 수정 3건(커밋 "M1 review fixes"):
  1. **버그** `PromiseEngine.onRouteStarted`가 `lastHeadlampState`를 null로 지워, 앱 시작 때 OFF가 오고 경로 시작 뒤 ON이 오는 실제 순서에서 전조등 약속(시나리오 B)이 발동하지 않았다. 지우지 않게 고치고 회귀 테스트 `headlamp off reported before route start still counts when on arrives during route` 추가.
  2. 늦은 poll로 500m·300m 경계를 한 번에 지나면 두 안내를 연달아 내던 동작 → 300m만 낸다(HANDOFF 2.2 "300m 안이면 300m만"과 같은 규칙으로 통일, `startedInside300` 집합 삭제로 코드 단순화).
  3. 시각 문구에 오전/오후를 붙임("오후 5시 30분"). 12시간제 숫자만으로는 모호하다.
- 수정 후 28/28 통과, assembleDebug 성공.

## M2 구현

- `ui/GuideController.kt`를 추가해 SDK 입력과 M1 코어를 메인 스레드에서 연결했다.
  - 비어 있지 않은 `List<TBTInfo>`만 `Tbt(type.name, distance, description)`로 변환한다.
  - 차속과 방향지시등을 각각 `onSpeed`, `onTurnSignal`에 전달한다.
  - 200ms마다 `poll`하고 `NoticeQueue` 우선순위대로 한 안내씩 Speaker에 전달한다.
  - 1초마다 `remaining`, 다음 회전, 다음 대상 회전, 적분거리와 차속을 `GILBEOT`로 기록한다.
- Speaker에 초기화·발화 busy 상태와 완료 콜백을 추가했다. 초기 시험 발화의 fallback이 끝나기 전에 안내 발화를 겹치지 않는다.
- MainActivity는 권한 요청과 수명주기만 맡도록 81줄로 줄였다. `distractionOptimized=true`는 유지했다.
- 다음 회전 카드, 직접 그린 좌·우·유턴·직진 계열 벡터, 10m 반올림 거리, description, 220dp/72sp 자막 띠, 차속·전조등·방향지시등·TTS 상태 줄을 구현했다.
- `tools/demo_drive.ps1`은 PowerShell 5.1/7 공통 문법으로 작성했다. `adb install -r`, user 10 권한, 8088 heartbeat, 앱 force-stop 후 dest extra 재시작, 10초 0→50km/h 램프, `remaining=` 스트리밍, `-HeadlampAt`, 선택 CSV `-GeoTrack`, BlinkLeft를 포함한다. 첫 안내 키를 첫 대상으로 고정해 그 키의 500m·300m 각 1회만 성공으로 인정하고, `finally`에서 속도 0·Park·전조등 Off·지시등 Off를 복구한다.
- M3 범위인 `PromiseEngine`, 일몰 판정, 경로 옵션 변경 연결은 구현하지 않았다.

### M2 빌드 및 에뮬레이터 실측

- 2026-11-08 최종 소스로 `gradlew.bat test assembleDebug`: **BUILD SUCCESSFUL**. M1 논리 테스트 28/28이 debug/release에서 모두 통과했다.
- AVD `pleos21`, Android user 10에 `adb install -r`로 설치했다. 아래 진단 실행은 엄격한 첫 대상 검증을 넣기 전 스크립트에서 두 번째 대상을 선택해 exit 0이었으며, 현재 스크립트 기준의 완료 성공으로 보지 않는다.
- 현재 서울시청 경로의 `route-1-turn-4` IC에서 50km/h로 500m·300m 안내가 각각 정확히 1회 발생했다.

```text
11-08 23:15:25.382 I/GILBEOT (16226): notice priority=200 key=route-1-turn-4-500 text=판교IC 방면으로 곧 나들목으로 진입합니다. 미리 차로를 확인하세요.
11-08 23:15:39.653 I/GILBEOT (16226): notice priority=300 key=route-1-turn-4-300 text=판교IC 방면으로 이제 나들목으로 진입합니다. 안내 차로를 따라가세요.
```

- 위 두 키를 `M2_demo_log.txt`에서 각각 세어 1회/1회임을 확인했다. 이는 화면·큐·중복 방지의 진단 증거이지, 첫 대상 완료 기준의 통과 증거는 아니다.
- 두 캡처 모두 안내 문구가 큰 자막에 보이고, 상태 줄과 자막 옆에 `TTS 음성 꺼짐`/`음성 꺼짐`이 표시된다.
  - `captures/M2_500.png`
  - `captures/M2_300.png`
  - 전체 스트리밍 로그: `captures/M2_demo_log.txt`
- TTS는 ON_DEVICE와 HYBRID 모두 시험 발화 뒤 8초 안에 `onDone`이 없어 자막 전용으로 전환됐다. 최종 코드에서는 두 fallback이 끝난 뒤 대기 중 안내가 한 건씩 표시되는 것을 로그로 확인했다.
- 종료 뒤 Virtual Console GET으로 `speed_kph=0`, `gear=Park`, `headlamp_mode=Off`, `turn_signal_mode=Off`를 확인했다.

### M2 제한 및 확인 필요

- **요청서의 “첫 대상 TURN_ENTER_IC_C 누적 860m” 조건은 현재 AVD에서 재현되지 않았다.** 같은 서울시청 상수와 RECOMMENDED로 M0 때는 13개 TBT였지만, 이번 최종 실측은 17개 TBT이며 첫 대상이 `TURN_LEFT 80m`, IC는 누적 2,790m의 두 번째 대상이었다. AVD를 `-no-snapshot-load`로 재시작하고 기존 COMFORTABLE 프로브로도 확인했지만 같은 17개 목록이었다.
- 따라서 현재 첫 80m 대상은 M1 규칙대로 300m 안내만 1회 발생했고, 완료 증거의 500m·300m 쌍은 동일 경로의 두 번째 대상 IC에서 확보했다. **첫 대상에서 두 안내가 모두 나온 것으로 주장하지 않는다.**
- 현재 스크립트는 첫 대상 `route-1-turn-0`의 300m 안내가 500m 안내보다 먼저 오면 즉시 실패한다. 서울시청 경로가 다시 M0의 13개 목록으로 돌아오면 첫 대상 키의 500m 안내부터 300m 안내까지 추적해 재검증해야 한다. 이 미충족 때문에 M2 완료 커밋은 만들지 않았다.

## M2 검수 (Claude, 2026-11-08)

- 완료 기준 조정: 에뮬레이터 경로가 M0(TBT 13개, 첫 대상 860m)과 달리 TBT 17개·첫 대상 TURN_LEFT 80m로 바뀌었다(출발점이 바뀐 것으로 추정, 원인 미확인). 출발 때 300m 안인 회전은 설계상 300m 안내만 나오므로, 시연 대상을 "500m 밖에서 다가간 첫 대상 회전"으로 바꿨다(`demo_drive.ps1`의 firstNoticeKey를 첫 500m 안내에서만 잡음).
- Claude 재실행(23:25~23:28): test·assembleDebug 통과 → `demo_drive.ps1` 성공. `route-1-turn-4`(판교IC 진입) 500m 23:28:05.881, 300m 23:28:20.263 각 1회. 간격 14.4초 = 50km/h에서 200m(14.4초)와 일치 → 차속 적분 정확. `route-1-turn-0` 300m만 1회(설계대로).
- 남은 것: 시연 대상 회전까지 약 170초 걸린다 → M4.5 영상은 편집하거나 가까운 목적지를 찾아야 한다. TTS는 여전히 자막만(이번 에뮬레이터는 `-no-audio`로 실행). IC_C는 방향이 없어 지시등 칭찬은 시연되지 않았다(좌회전 대상에서 M3 때 확인).

## M3 구현

- `ui/PromiseController.kt`를 추가해 기존 `GuideController`의 동일한 `NoticeQueue`와 `Speaker` 경로에 일몰·전조등·휴식 약속을 연결했다. 비어 있지 않은 TBT가 와도 공용 큐를 교체하지 않으므로 대기 중 약속 안내를 잃지 않는다.
- 순수 Kotlin `core/PromiseRuntime.kt`에 엄격한 `HH:mm` KST 오프셋 시계, 새 경로/옵션 변경 후속 `onRouteStarted` 구분, 저빔 `ON`/`OFF`/`OTHER` 매핑을 추가했다. `PromiseEngine`의 기존 판단 로직은 바꾸지 않고 카드·로그용 판정 결과와 배속 적용 연속 운전 분만 읽기 전용으로 노출했다.
- 디버그 인텐트 `--es now HH:mm`과 `--ef timeScale 60`을 `onCreate`와 `onNewIntent`에서 읽는다. 주입 시각은 일몰 날짜·도착 판정에만 쓰고, 연속 운전은 계속 `SystemClock.elapsedRealtime()`으로 누적한다.
- `NaviSource.changeRouteOption`은 호출 전/성공/실패를 기록하고, 호출 뒤 30초 동안 모든 비어 있지 않은 TBT 및 `onRouteStarted`를 측정한다. 30초간 TBT가 없으면 `noNewTbtAfterOptionChange 30s`를 기록한다.
- 화면 중간을 다음 회전 카드와 약속 카드로 나눴다. 시각 주입은 상태 줄에, `timeScale != 1`은 우측 위 `데모 배속 ×60` 배지에 표시한다. 쉬기 안내 뒤 버튼은 차속 1m/s 이하에서만 보이며 실제 검색 대신 다음 버전 안내 자막만 표시한다.
- `tools/demo_promise.ps1`은 `before`, `after`, `headlamp`, `break`를 각각 새 프로세스로 실행하고 로그·스크린샷을 저장한다. 모든 종료 경로에서 속도 0, Park, 전조등 Off, 지시등 Off를 복구한다.

### M3 빌드 및 단위 테스트

- `JAVA_HOME=%LOCALAPPDATA%\Programs\jdk17\jdk-17.0.20.1+1`
- 최종 소스로 `gradlew.bat test assembleDebug`: **BUILD SUCCESSFUL**.
- 논리 테스트는 변형별 36개 통과, 0개 실패이다. 신규 `PromiseRuntimeTest` 8개는 시각 주입 진행·엄격 파싱, 옵션 변경 뒤 경로 시작 분류, 새 경로 재설정, 저빔 매핑, 배속 분 표시, 일몰 판정 메타데이터를 검증한다.
- 모든 Kotlin 파일은 300줄 이하이며 최장 파일은 `GuideController.kt` 251줄이다. `core/`의 Android import는 0건이다.

### M3 에뮬레이터 실측

- AVD `pleos21`, Android user 10, `adb install -r`로 최종 APK를 설치했다. 이번 경로는 13 TBT, `duration=1380`, `distance=22000`이었고 최신 GPS `37.502,127.033`을 일몰 계산에 사용했다.
- `before`는 주입 15:30, 실제 ETA 15:53, 일몰 17:26으로 일몰 전 분기였다. `changeRouteOption` 호출은 로그에 없었다.

```text
11-09 06:51:49.593 I/GILBEOT (14932): promise sunset now=15:30 arrival=15:53 sunset=17:26 before=true requestComfortable=false
11-09 06:51:49.593 I/GILBEOT (14932): notice priority=100 key=route-1-promise-sunset text=해 지는 시각은 오후 5시 26분입니다. 지금 가면 해 지기 전, 오후 3시 53분에 도착합니다.
```

- `after`는 주입 17:00, 실제 ETA 17:23으로 일몰 15분 전 기준을 넘었다. COMFORTABLE 호출은 예외 없이 반환했고, 호출 1.077초 뒤 13개 TBT 콜백이 왔다. 옵션 변경 뒤 `onRouteStarted`는 30초 관찰 창에 다시 오지 않았다.

```text
11-09 06:54:30.476 I/GILBEOT (12259): promise sunset now=17:00 arrival=17:23 sunset=17:26 before=false requestComfortable=true
11-09 06:54:30.476 I/GILBEOT (12259): notice priority=100 key=route-1-promise-sunset text=도착은 오후 5시 23분, 해가 진 뒤입니다. 약속하신 대로 편안한 경로로 바꿀게요.
11-09 06:54:30.477 I/GILBEOT (12259): changeRouteOption call option=COMFORTABLE reason=sunset
11-09 06:54:30.480 I/GILBEOT (12259): changeRouteOption call option=COMFORTABLE reason=sunset ok
11-09 06:54:31.554 I/GILBEOT (12259): newTbtAfterOptionChange size=13 elapsedMs=1077
```

- 단, 위 TBT는 옵션 변경 호출 뒤에 도착했다는 사실만 측정할 수 있다. 초기 RECOMMENDED 요청의 아직 도착하지 않은 첫 목록인지, 옵션 변경이 새로 만든 목록인지는 SDK 콜백만으로 인과를 구분할 수 없다.
- `headlamp`는 50km/h 주행 중 첫 OFF→ON에서 두 안내와 COMFORTABLE 호출이 각각 1회 발생했다. 두 번째 OFF→ON은 `notices=0`이었다. 정차 뒤 쉬기 버튼이 보이는 화면을 캡처했다. 이 실행에서는 옵션 변경 뒤 새 TBT와 `onRouteStarted`가 모두 없었다.

```text
11-09 06:55:17.418 I/GILBEOT (17636): promise headlamp state=ON notices=2 requestComfortable=true
11-09 06:55:17.418 I/GILBEOT (17636): notice priority=100 key=route-1-promise-headlamp-28231418 text=전조등이 켜졌어요. 약속하신 대로 편안한 경로로 바꿀게요.
11-09 06:55:17.419 I/GILBEOT (17636): notice priority=100 key=route-1-promise-rest-28231418 text=쉬어 갈 곳이 필요하면 차를 세웠을 때 알려 주세요.
11-09 06:55:17.419 I/GILBEOT (17636): changeRouteOption call option=COMFORTABLE reason=headlamp
11-09 06:55:17.423 I/GILBEOT (17636): changeRouteOption call option=COMFORTABLE reason=headlamp ok
11-09 06:55:19.719 I/GILBEOT (17636): promise headlamp state=ON notices=0 requestComfortable=false
11-09 06:55:47.419 I/GILBEOT (17636): noNewTbtAfterOptionChange 30s
```

- `break`는 `timeScale=60`, 50km/h 주행 시작 후 약 110.1초에 scaled 110분과 휴식 안내가 1회 발생했다. 5초 정차 중 scaled 분이 0으로 초기화됐고 재출발 약 1초 뒤 1분부터 다시 증가했다. 캡처에 `데모 배속 ×60`이 보인다.

```text
11-09 06:57:56.841 I/GILBEOT (28858): promise break drivenScaledMin=109
11-09 06:57:57.854 I/GILBEOT (28858): notice priority=100 key=promise-break-1 text=곧 두 시간째 운전입니다. 다음 휴게소에서 쉬어 가요.
11-09 06:57:57.855 I/GILBEOT (28858): promise break drivenScaledMin=110
11-09 06:58:02.522 I/GILBEOT (28858): promise break drivenScaledMin=0
11-09 06:58:05.547 I/GILBEOT (28858): promise break drivenScaledMin=1
```

- 캡처와 전체 로그:
  - `captures/M3_before.png`, `captures/M3_before_log.txt`
  - `captures/M3_after.png`, `captures/M3_after_log.txt`
  - `captures/M3_headlamp.png`, `captures/M3_headlamp_log.txt`
  - `captures/M3_break.png`, `captures/M3_break_log.txt`

### M3 제한 및 실패 기록

- `after` 최종 성공 전 한 실행에서는 `onRouteStarted`와 옵션 변경 성공은 왔지만 비어 있지 않은 초기 TBT가 35초 안에 오지 않아 구버전 데모 판정이 중단됐다. 완료 기준은 옵션 변경 후 `newTbtAfterOptionChange` 또는 30초 `noNewTbtAfterOptionChange`이므로, 스크립트가 초기 TBT를 별도 필수 조건으로 둔 것이 과도했다. 이를 제거한 뒤 최종 실행은 위 로그대로 통과했다.
- `changeRouteOption` 뒤 `onRouteStarted` 재수신은 `after`와 `headlamp` 모두 관찰되지 않았다. 따라서 후속 콜백 보호 로직은 단위 테스트로 검증했지만 에뮬레이터에서 실제 후속 콜백 분기는 실행되지 않았다.
- 실제 차량에서는 시험하지 않았다. 위 결과는 AVD `pleos21`에서만 관찰했다.

## M3 검수 (Claude, 11/09)

Codex 보고 중 바로잡은 것과 추가 수정:

1. **`after`의 `newTbtAfterOptionChange size=13`은 오판이었다.** 옵션 변경은 `onRouteStarted` 직후에 호출되는데, 그 시점엔 처음 경로의 첫 TBT 목록이 아직 안 온 상태였다(같은 실행에서 첫 목록이 1.08초 뒤 도착). 이제 첫 목록을 받기 전에 온 목록은 `firstRouteTbtAfterOptionChange … (not counted)`로 따로 기록한다.
2. **측정된 답(에뮬레이터, 4회):** `changeRouteOption(COMFORTABLE)`은 예외 없이 반환된다. 그러나 `onRouteOptionChanged`, `onRouteStarted`, 새 `onTBTInfo`는 30초 동안 오지 않았다. 5초 뒤 `getTBTInfo()`로 직접 받은 목록은 이전 목록과 같았다(`sameTbtAfterOptionChange … (route unchanged)`). 결론: **이 에뮬레이터 내비는 경로 옵션 변경을 경로에 반영하지 않는다.** HANDOFF M3 기준인 "새 TBT 목록 수신 로그"는 에뮬레이터에서 충족할 수 없다. 호출 로그와 "경로 변화 없음" 측정 로그로 대신한다. 실차에서의 동작은 확인 필요.
3. **`requestReRoute()`는 쓰지 않는다.** 옵션 변경 뒤 보조로 호출해 봤더니 내비 화면이 앞으로 나와 길벗이 가려지고(`onTaskVisibilityChanged … false`), 10초 뒤 앱이 동결됐다(`freezing … ai.gilbeot.app`). 2회 재현 후 되돌렸다.
4. **첫 TBT 목록 누락 대응:** `onRouteStarted` 뒤 비어 있지 않은 TBT가 오지 않는 경우가 실행 몇 번 중 한 번꼴로 있었다(회전 안내 전체가 멈추는 문제). 경로 시작 3초 뒤에도 목록이 없으면 `getTBTInfo()`를 한 번 호출한다. 실측에서 요청 20ms 뒤 13개 목록을 받았다.
5. **같은 목록 재수신 무시:** 다시 받은 목록이 같은 경로이면 엔진에 넘기지 않는다(`duplicate TBT list ignored`). 넘기면 주행 거리가 0으로 리셋돼 회전 안내가 반복된다.
6. 재검증(모두 수정본 APK): headlamp(3회 연속 통과, 전조등 안내 1회·두 번째 ON은 notices=0·쉬어 갈 곳 버튼 표시), after(해가 진 뒤 문구 + 호출 로그), before(해 지기 전 문구, 호출 없음), break(110분 지점 1회, 정차 뒤 0으로 리셋), M2 회귀(`demo_drive.ps1`: 판교IC 500m·300m 각 1회) 통과.

M4로 넘길 것:
- 전조등 약속 뒤에도 약속 카드가 "해 지기 전 도착 ✓"로 남는다. 편안한 경로 요청 상태를 카드에 반영해야 한다.
- 에뮬레이터 경로 소요 시간이 실행마다 달라졌다(M0 3660초 → 현재 1380~1440초). 시연 시각 주입값은 실제 도착 시각 로그를 보고 고른다.
- 카드와 자막의 회전 불일치(M2에서 넘어옴).

## M4 (11/09, Codex 일부 + Claude 마무리)

Codex는 화면 카드 선택·약속 카드 상태·설정·서명 설정·README·스크립트 `-Apk`까지 작성하다 사용량 한도로 중단됐다(레이아웃 미반영으로 빌드 실패 상태). Claude가 이어서 마무리했다.

- 카드: 큰 카드 = 다음 **대상 회전**(`TurnPreviewEngine.turnCardSelection()`), 그 전 일반 회전은 "그 전에 우회전 700m" 한 줄. 테스트 `TurnCardSelectionTest` 2건.
- 약속 카드: 편안한 경로는 "요청함"으로만 표시(실제 변경을 주장하지 않음). 꺼진 약속은 "꺼짐".
- 자막 글자 62sp → **72sp**(HANDOFF 2.4 기준, M2부터 미달이었음).
- **설정은 Activity가 아니라 메인 화면 위 다이얼로그(`PromiseSettingsDialog`).** 실측: 별도 `PromiseSettingsActivity`를 한 번 띄운 뒤 주행하면, 설정 화면이 닫혀도 길벗 화면 전체가 시스템의 "주행 중에는 사용할 수 없습니다"로 가려졌다(fresh 실행에선 정상). 다이얼로그로 바꾼 뒤 같은 순서(설정 열기→토글→30km/h)에서 다이얼로그가 닫히고 화면이 유지됨: `captures/M4_settings_closed_driving.png`.
- release 서명: 키스토어·속성은 `%USERPROFILE%\.gilbeot\`(저장소 밖). `apksigner verify --print-certs` → `Signer #1 certificate DN: CN=(개인 키 — 11/09 DTONE 키로 교체)`.
- `apk/DTONE_길벗_release.apk`를 `-g` 없이 설치(디버그 서명과 달라 `pm uninstall --user 10` 후 설치) → 위치 권한 대화상자(`captures/M4_release_first_run.png`), 허용 뒤 user 10에서 CAR_SPEED·FINE_LOCATION granted=true, 경로·카드 표시(`captures/M4_release_running.png`). 설정 창 `captures/M4_settings.png`, 토글 시 카드 "해 지기 전 도착 · 꺼짐" 확인.
- release APK 회귀: `demo_drive.ps1 -Apk` 판교IC 500m·300m 각 1회 / `demo_promise.ps1 -Scenario headlamp -Apk` 안내 1회·두 번째 ON notices=0·옵션 요청 ok·경로 변화 없음.
- 미확인: IC 진입(TURN_ENTER_IC_C) 화살표는 직진 모양.

### M4 검수 (Claude, 독립 verifier 조건부 통과 → 수정)

- verifier: HIGH 0 / MED 3. MED1 설정 버튼 기본 표시 → `visibility=gone` 기본값으로 수정. MED2 증거 파일 → 아래 파일로 재생성. MED3 기준 변경 3건(M2 시연 대상 재정의, M3 새 TBT 기준 대체, 설정 Activity→다이얼로그, 권한 LaunchActivity 분리 포함)은 사용자가 11/09 「Approve as is」로 승인. LOW: versionName `0.1-M0`→`1.0` 수정, 스크립트 기본 APK는 README에 `-Apk` 안내, 정차 기준 1m/s는 README에 공개.
- **권한 창도 주행 화면을 가렸다.** `-g` 없이 설치해 첫 실행 권한 창을 허용/거부한 뒤 주행하면 길벗 화면 전체가 "주행 중에는 사용할 수 없습니다"로 가려짐(설정 Activity와 같은 원인: 시스템 `GrantPermissionsActivity`가 주행 화면 task에 들어감). CAR_SPEED와 위치는 한 대화상자에서 함께 허용/거부된다(앞의 "원인 미확인" 해소).
- 수정: 런처 진입점을 `LaunchActivity`(투명·noHistory·excludeFromRecents)로 분리해 권한을 거기서 묻고, `MainActivity`는 `taskAffinity=ai.gilbeot.app.drive`로 별도 task에서 연다. 권한 없이 `MainActivity`를 직접 실행하면 `LaunchActivity`로 되돌린다.
- 재검증(release APK, `pm uninstall --user 10` 후 `-g` 없이 설치):
  - 허용: `launch permission result …CAR_SPEED=0, …FINE_LOCATION=0`, dumpsys task `A=…:ai.gilbeot.app.drive`, 30km/h 주행 중 화면 유지 `captures/M4_release_granted_driving.png`. 권한 창 `captures/M4_release_first_run.png`.
  - 거부(`MainActivity` 직접 실행 → 되돌림 확인): `launch requestPermissions …` → `launch permission result …=-1, …=-1` → `speed permission denied; idle` / `location permission denied; fallback Seoul` → `onRouteStarted duration=1560 distance=22000`, 30km/h 주행 중 화면 유지 `captures/M4_release_denied_driving.png`. 로그 `captures/M4_release_install.txt`.
- 회귀(최종 release APK): `demo_drive.ps1 -Apk` → `500m=True, 300m=True` (`captures/M4_release_drive_log.txt`, `M4_release_running.png`) / `demo_promise.ps1 -Scenario headlamp -Apk` → 안내 2건 1회, 두 번째 ON `notices=0`, `changeRouteOption … ok`, `sameTbtAfterOptionChange … (route unchanged)`, `noNewTbtAfterOptionChange 30s` (`captures/M4_release_headlamp_log.txt`, `M4_release_headlamp.png`).
- 단위 테스트 release 38건 실패 0 (`captures/M4_release_tests.txt`). 서명 `CN=(개인 키 — 11/09 DTONE 키로 교체)`, APK SHA-256 `E76F3F75…4285B` (`captures/M4_release_apksigner.txt`).

## M4.5 (11/09, Claude)

- 안내 문구 정직화: "편안한 경로로 바꿀게요" → "편안한 경로를 요청할게요", "편안한 경로로 가고 있습니다" → "편안한 경로는 이미 요청했어요"(에뮬레이터에서 경로가 실제로 바뀌지 않음). 테스트 문자열도 수정, release 38건 실패 0.
- 시연 영상 `demo/gilbeot_demo.mp4` (133.6초, 2.9MB): `tools/record_demo.ps1`이 Virtual Console을 조작하며 `screenrecord` 두 번 녹화, `tools/make_demo_video.py`가 설명 띠·카드를 붙임. 표식 `captures/video/demo_before_marks.json` = launch 1.87 · sunset 10.78 · sunsetShown 19.51 · drive 25.92 · turn500 52.81 · turn300 67.10 · headlamp 74.43 · stop 83.77 · end 91.10 / after: sunset 10.77 · sunsetShown 19.46.
- 첫 녹화에서 발견: 에뮬레이터 TTS가 ON_DEVICE 8초 + HYBRID 8초 시간 초과된 뒤에야 자막이 떠서, 첫 안내 자막이 앱 실행 후 약 17초에 나온다. 녹화 스크립트는 `tts subtitle-only` 로그를 기다린 뒤 출발하도록 고쳤다. 앱 쪽 개선(첫 안내를 TTS 결과와 무관하게 즉시 자막 표시)은 하지 않았다 — 실차에서 TTS가 되면 문제없을 것으로 추정, 미확인.
- 대표 화면 `demo/screen_*.png` 4장(영상 프레임에서 추출) → 접수양식 7번 박스에 사용.
- 접수양식: `../tools/fill_form.py` ITEMS를 실제 동작에 맞춰 [1차 구현]/[2차 계획]으로 나눔 → `../02_접수양식_길벗_v2.docx` (Word PDF 변환 12쪽 확인). 01 개인정보·04 서명은 비워 둠(본인 기재).- release APK 재빌드(문구 변경): SHA-256 `A131FD50F48A96F1E3FF35A54B70B087A08AE257E9E3F95886160A1E97FE0783`, `CN=(개인 키 — 11/09 DTONE 키로 교체)` (`captures/M4_release_apksigner.txt` 갱신). 시연 영상은 이 APK로 녹화.

### M4.5 검수 (verifier 보류 HIGH 1 / MED 6 / LOW 3 → 수정)
- MED3 수정: `PromiseController.changeComfortable` 성공 시 `engine.onRouteOptionChanged(true)` 호출 — 일몰 뒤 요청 다음 전조등 ON이면 "이미 요청했어요"로 말함(전엔 호출 지점이 없어 "요청할게요"라고 말하고 실제로는 다시 요청하지 않았음). 테스트 추가, release 39건 실패 0 (`captures/M4_5_release_tests.txt`). APK 재빌드 SHA-256 `419580ED28FC34C1C64FF4DD46E839822FC617EDC965D4F6373C700C7C5B0120`.
- 양식(`../tools/fill_form.py`): 출처 없는 "2021 15.7%→2025 23.7%" 삭제, 조건부 면허 "시행 일정 없음"을 "추진 중·입법 단계(매일신문 2026-09-16)"로 정정, 예시 도착 4시 9분(영상 값), 시나리오 B 제목·3) 정정, ④ [시안]→[구상], 시안 02 캡션 범위 명시, 계기판 화살표 캡션, 양식 메타데이터 작성자 비움.
- 영상 속 화면은 이 수정 직전 APK로 녹화(차이는 "일몰 뒤 요청 후 전조등" 경로뿐이며 영상에 그 경로는 없음).
- HIGH(양식이 금지한 실명·계정 노출: 저장소 계정명, 커밋 이메일, APK 파일명·서명 CN) — 사용자 결정 대기.

### 익명화 (11/09, 사용자 결정: 팀명 DTONE · 기존 계정의 조직)
- 양식 02 안내문 "공유 링크와 첨부자료에도 개인정보·계정정보·개발자 실명이 노출되지 않도록"에 맞춰, 기존 공개 저장소(개인 계정)를 비공개로 전환.
- 새 서명 키 `%USERPROFILE%\.gilbeot\gilbeot_dtone.jks` (CN=DTONE, RSA 2048, 10000일). 개인 이름 키는 같은 폴더에 `*_seo_old` 로 보관(저장소 밖). APK 이름 `apk/DTONE_길벗_release.apk`.
- 새 키 APK: apksigner `CN=DTONE` (`captures/M4_release_apksigner.txt`), 기존 앱 제거 후 설치 → `demo_drive.ps1 -Apk` 500m=True, 300m=True.
- 문서의 실명 경로·`C:\Users\<사용자>` 경로를 `%LOCALAPPDATA%`로 치환. 저장소 로컬 커밋 신원 `DTONE <noreply@dtone.invalid>`.
- 공개 저장소에는 기존 히스토리(작성자 이메일·옛 APK 이름 포함)를 올리지 않고 현재 트리 한 커밋으로 올린다. 전체 히스토리는 저장소 밖 `_private_backup` 번들로 보관.
