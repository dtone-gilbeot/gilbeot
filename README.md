# 길벗

## ① 앱 이름·한 줄 소개

**길벗**은 회전 지점을 미리 차분한 말로 알려 주고, 운전자가 정한 일몰 전 도착·주기적 휴식 약속을 상기시키는 Pleos 차량용 Android 앱입니다.

현재 실제 동작 범위는 ① **미리, 차분히 안내**와 ③ **나의 운전 약속**입니다. ② 피하고 싶은 길 기억(STT·경유지 추가), ④ 길 헤맴 도우미, ⑤ 운전 건강 리포트·가족 공유는 이번 APK의 구현 범위가 아닙니다.

## ② 실행 방법

### APK 설치와 첫 실행

Pleos Connect Emulator를 부팅한 뒤 저장소 루트의 PowerShell에서 다음과 같이 설치합니다. `-g`를 붙이지 않아야 첫 실행 권한 요청 흐름을 확인할 수 있습니다.

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb install -r ".\apk\DTONE_길벗_release.apk"
& $adb shell am start --user 10 -n "ai.gilbeot.app/.ui.LaunchActivity"
```

런처의 길벗 아이콘으로 실행해도 같습니다. `LaunchActivity`는 권한만 묻고 바로 주행 화면(`MainActivity`)을 연 뒤 사라집니다. 권한을 이미 받은 상태라면 `MainActivity`를 직접 실행해도 되고, 권한이 없을 때 `MainActivity`를 직접 실행하면 `LaunchActivity`로 돌아갑니다.

서명이 다른 debug APK가 이미 설치되어 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`이 나오면 Android user 10의 기존 앱을 먼저 제거합니다.

```powershell
& $adb shell pm uninstall --user 10 ai.gilbeot.app
# 위 명령만으로 해결되지 않을 때
& $adb shell pm uninstall ai.gilbeot.app
```

첫 실행의 런타임 권한 대화상자에서 차량 속도와 위치 권한을 허용합니다. 거부해도 앱은 멈추지 않고 주행 화면이 열리지만, 차속을 받지 못해 회전까지 남은 거리가 줄지 않고 일몰 계산은 서울 좌표로 대신합니다. 앱은 실행되면 기본 목적지인 **서울시청**으로 `RECOMMENDED` 경로를 요청합니다. 다른 목적지는 `위도,경도,이름` 형식의 `dest` extra로 지정할 수 있습니다.

```powershell
& $adb shell am force-stop ai.gilbeot.app
& $adb shell am start --user 10 -n "ai.gilbeot.app/.ui.MainActivity" `
  --es dest "37.5665,126.9780,서울시청"
```

경로와 회전 카드를 받은 뒤 Virtual Console에서 기어를 **D(Drive)**로 바꾸고 차속을 올리면 주행을 재현할 수 있습니다. 약속 설정과 쉬어 갈 곳 버튼은 차속이 1m/s 이하일 때만 표시됩니다.

## ③ 테스트 환경·설정

- Pleos Connect Emulator **v2.1.0**, AVD `pleos21`
- Android 14/API 34 Pleos Automotive x86_64 이미지, 2560×1440, 160 dpi, RAM 4096MB
- 앱 설정: minSdk 28, target/compileSdk 34, JDK 17, Gradle 8.7, AGP 8.5.2, Kotlin 1.9.25
- Pleos SDK: NaviHelper 3.0.5, Vehicle 2.3.2, TextToSpeech 3.3.1
- Virtual Console 서버는 에뮬레이터의 TCP 8088을 사용합니다. 호스트에서 `adb forward tcp:8088 tcp:8088`을 먼저 실행해야 합니다.
- 이 에뮬레이터에서는 Pleos TTS의 ON_DEVICE와 HYBRID가 완료되지 않아 앱이 큰 자막 안내로 fallback합니다. 서버 TTS 자격 증명은 포함하지 않았습니다.
- 메인 액티비티는 `distractionOptimized=true`로 선언되어 주행 중에도 회전·약속 자막을 표시합니다. 설정 화면은 정차 중에만 앱에서 열 수 있습니다.
- 이 에뮬레이터의 홈 화면은 task에 마지막으로 들어온 화면을 기준으로 주행 중 표시 여부를 정합니다. 시스템 권한 창(`GrantPermissionsActivity`)이나 별도 설정 Activity가 주행 화면과 같은 task에 한 번이라도 들어오면, 그 창이 닫힌 뒤에도 주행 중 앱 화면 전체가 "주행 중에는 사용할 수 없습니다"로 가려졌습니다. 그래서 권한은 별도 task의 `LaunchActivity`에서 묻고(`MainActivity`는 `taskAffinity=ai.gilbeot.app.drive`), 설정은 다이얼로그로 띄웁니다. 허용·거부 두 경우 모두 30km/h 주행 중 화면이 가려지지 않는 것을 확인했습니다(`captures/M4_release_granted_driving.png`, `captures/M4_release_denied_driving.png`).

## ④ APK 위치

제출용 release APK: [`apk/DTONE_길벗_release.apk`](apk/DTONE_길벗_release.apk)

## 동작 범위

### 구현됨

- ① 회전 미리 안내: NaviHelper의 TBT 목록을 구간 거리로 누적하고, 에뮬레이터 차속을 적분해 좌회전·유턴·IC 진입의 500m/300m 안내를 각각 한 번 표시합니다. 큰 카드는 다음 **대상 회전**을 보여 주며, 그 전에 대상이 아닌 회전이 있으면 카드 아래 한 줄로 표시합니다.
- ③ 나의 운전 약속: 일몰 전 도착과 2시간마다 쉬기 약속을 각각 켜고 끌 수 있습니다. 일몰 뒤 도착 예상 또는 주행 중 전조등 점등 때 `COMFORTABLE` 옵션을 요청하며, 실제 경로가 바뀌었다고 표시하지 않고 **요청 상태**만 표시합니다.
- 안내는 큰 자막을 항상 사용하며, TTS가 실패하면 자막 전용 상태를 화면에 표시합니다.

### 이번 APK 범위 밖

- ② 피하고 싶은 길 기억: STT, 어려운 지점 저장, 우회 경유지 추가
- ④ 길 헤맴 도우미: 재탐색 감지와 안전한 정차 지점 검색
- ⑤ 운전 건강 리포트: 월간 통계와 가족 공유
- [쉬어 갈 곳 보기]는 다음 버전 안내만 표시하며 실제 장소를 검색하지 않습니다.

## 시연 영상

[`demo/gilbeot_demo.mp4`](demo/gilbeot_demo.mp4) (2분 14초, 음성 없음). release APK를 에뮬레이터에서 녹화한 화면을 자르거나 빨리 감지 않고, 아래에 설명 띠와 앞뒤 카드만 붙였습니다. 순서: 시각 주입 15:30으로 실행 → 일몰 전 도착 안내 → 50km/h 주행 → 나들목 500m·300m 안내 → 전조등 ON과 편안한 경로 요청 → 정차 시 버튼 표시 → 시각 주입 17:00으로 다시 실행해 일몰 뒤 도착 안내.

다시 만들려면 에뮬레이터를 켠 상태에서 아래 두 단계를 실행합니다. 원본 녹화(`captures/video/*.mp4`)는 용량 때문에 커밋하지 않고, 자막 시점 표식(`*_marks.json`)만 커밋합니다.

```powershell
.\tools\record_demo.ps1                 # 원본 두 개 녹화 + 표식 저장
py -3 -X utf8 tools\make_demo_video.py  # ffmpeg로 설명 띠를 붙여 demo/gilbeot_demo.mp4 생성
```

## 데모 스크립트

스크립트는 기본적으로 `app/build/outputs/apk/debug/app-debug.apk`를 설치하므로 먼저 `gradlew.bat assembleDebug`가 필요합니다. 빌드 없이 돌리려면 `-Apk ".\apk\DTONE_길벗_release.apk"`를 넘기세요. 다른 APK도 `-Apk`로 경로를 넘겨 시험할 수 있습니다. 두 스크립트 모두 Android user 10 권한을 부여하고 Virtual Console 세션을 유지하며, 종료 시 속도 0·Park·전조등 Off·지시등 Off 복구를 시도합니다.

```powershell
# 회전 500m/300m 안내 회귀
.\tools\demo_drive.ps1

# release APK로 같은 회귀 실행
.\tools\demo_drive.ps1 -Apk ".\apk\DTONE_길벗_release.apk"

# 약속 시나리오
.\tools\demo_promise.ps1 -Scenario before
.\tools\demo_promise.ps1 -Scenario after
.\tools\demo_promise.ps1 -Scenario headlamp
.\tools\demo_promise.ps1 -Scenario break
```

`demo_promise.ps1`의 시나리오는 다음과 같습니다.

- `before`: 오후 3시 30분을 주입해 일몰 전 도착 안내와 옵션 변경 미호출을 확인
- `after`: 오후 5시를 주입해 일몰 뒤 도착 안내와 COMFORTABLE 요청을 확인
- `headlamp`: 주행 중 전조등을 켜 첫 안내·COMFORTABLE 요청 1회를 확인
- `break`: `timeScale=60`으로 약 110초 뒤 휴식 안내와 정차 뒤 타이머 초기화를 확인

`now`(`HH:mm`)와 `timeScale`은 심사용 시간 의존 시나리오를 짧고 반복 가능하게 만드는 **데모 전용 intent extra**입니다. 일반 설정 화면에는 노출하지 않으며, 주입 중에는 화면에 `시각 주입 HH:MM` 또는 `데모 배속 ×N` 배지를 표시해 실제 시간처럼 오해하지 않도록 했습니다.

```powershell
& $adb shell am start --user 10 -n "ai.gilbeot.app/.ui.MainActivity" `
  --es now "17:00" --ef timeScale 60
```

## 에뮬레이터에서 확인된 제한

- `changeRouteOption(COMFORTABLE)` 호출은 예외 없이 반환됐지만, Pleos Connect Emulator v2.1.0에서는 30초 동안 새 `onRouteStarted`·새 TBT 목록이 오지 않았고 `getTBTInfo()` 결과도 기존 목록과 같았습니다. 따라서 UI는 “편안한 경로로 요청함”이라고만 표시합니다. 실차 동작은 확인하지 않았습니다.
- 경로 시작 뒤 첫 비어 있지 않은 TBT 콜백이 가끔 오지 않았습니다. 3초 동안 목록이 없으면 `getTBTInfo()`를 한 번 호출하는 fallback을 사용하고, 같은 목록의 재수신은 주행거리 초기화를 막기 위해 무시합니다.
- 에뮬레이터 내비 위치는 Virtual Console 차속을 올려도 고정됩니다. 회전까지 남은 거리는 차량 속도를 시간에 따라 적분한 시뮬레이션 값입니다. 실제 차량에서 검증하지 않았습니다.
- 경로 소요 시간과 TBT 목록은 실행에 따라 달라진 적이 있습니다. 데모의 `now` 값은 해당 실행의 도착 판단 로그와 함께 확인해야 합니다.

## 빌드

PowerShell에서 JDK 17을 지정한 뒤 Gradle wrapper를 실행합니다.

```powershell
$env:JAVA_HOME = "$env:LOCALAPPDATA\Programs\jdk17\jdk-17.0.20.1+1"
.\gradlew.bat test assembleDebug
.\gradlew.bat assembleRelease
```

release 서명 정보는 저장소 밖의 `%USERPROFILE%\.gilbeot\keystore.properties`에서 읽습니다. 파일에는 `storeFile`, `storePassword`, `keyAlias`, `keyPassword`가 있어야 합니다. 키스토어와 속성 파일은 커밋하지 않습니다. 파일이 없거나 네 항목 중 하나가 비어 있으면 Gradle은 경고를 출력하고 unsigned release APK를 구성하며, debug 빌드는 실패시키지 않습니다. 난독화·축소는 설명 가능성을 위해 release에서도 사용하지 않습니다(`minifyEnabled=false`).

## 모듈 맵

### `car/` — Pleos·Android 입력과 출력

- `NaviSource.kt`: 서울시청 기본 경로 요청, TBT/ETA 전달, COMFORTABLE 옵션 요청과 후속 관측
- `SpeedSource.kt`: `CarPropertyManager` 차량 속도 콜백을 m/s로 전달
- `VehicleSignals.kt`: 전조등과 방향지시등 상태 콜백을 전달
- `LocationSource.kt`: 일몰 계산용 위치를 받고, 위치가 없으면 서울 좌표를 사용
- `Speaker.kt`: Pleos TTS를 ON_DEVICE→HYBRID 순으로 시도하고 실패하면 자막 전용으로 전환

### `core/` — Android에 의존하지 않는 판단 로직

- `Notice.kt`: 안내 우선순위, FIFO 큐, 중복 키 제거
- `TurnPhrases.kt`: SDK 독립 TBT 자료형과 회전 종류별 한국어 안내 문구
- `TurnPreviewEngine.kt`: 구간 거리 누적, 차속 적분, 다음 대상 카드 선택, 500m/300m·지시등 안내 판정
- `SunCalc.kt`: NOAA 근사식으로 지정 위치·날짜의 일출과 일몰 계산
- `PromiseEngine.kt`: 일몰 전 도착, 전조등, 연속 운전·정차 리셋 약속 판정
- `PromiseRuntime.kt`: 데모 시각 주입, 경로 시작 원인 구분, 전조등 상태 변환

### `ui/` — 화면과 기능 연결

- `LaunchActivity.kt`: 런처 진입점. 주행 화면과 다른 task에서 권한을 묻고 `MainActivity`를 연 뒤 종료
- `MainActivity.kt`: 주행 화면 수명주기, 권한 없을 때 `LaunchActivity`로 되돌리기, 정차 중 약속 설정 진입
- `GuideController.kt`: Navi/Vehicle 입력을 회전 엔진·안내 큐·화면에 연결
- `PromiseController.kt`: 위치·ETA·전조등·차속을 약속 엔진과 약속 카드에 연결
- `PromisePreferences.kt`: 두 약속의 켜짐 상태를 저장하는 SharedPreferences 파일·키 이름
- `PromiseSettingsDialog.kt`: 정차 중 여는 두 약속 스위치 창(메인 화면 위 다이얼로그)이며 이동이 시작되면 닫힘. 별도 Activity로 띄우면 주행 중 앱 화면 전체가 시스템에 의해 가려져서 다이얼로그로 만들었습니다.

### 앱 조립

- `App.kt`: SDK 래퍼와 공용 앱 객체를 DI 라이브러리 없이 생성

