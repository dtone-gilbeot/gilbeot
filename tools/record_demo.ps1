<#
.SYNOPSIS
  시연 영상용 원본 두 개를 에뮬레이터에서 녹화한다.
  1) before: 시각 주입 15:30 → 일몰 전 안내 → 50km/h 주행 → 500m/300m 안내 → 전조등 켜기 → 정차
  2) after : 시각 주입 17:00 → 해 진 뒤 도착 안내 → 편안한 경로 요청 표시
  각 동작의 녹화 기준 경과 시간을 captures/video/*_marks.json에 남겨 자막 편집(make_demo_video.py)에 쓴다.
#>
param(
    [string]$Apk = ".\apk\DTONE_길벗_release.apk"
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$base = 'http://127.0.0.1:8088'
$outDir = Join-Path $repo 'captures\video'
New-Item -ItemType Directory -Force $outDir | Out-Null

function Put($path) { $null = Invoke-RestMethod -Method Put -Uri ($base + $path) -TimeoutSec 5 }
function Beat { try { $null = Invoke-RestMethod -Method Post -Uri "$base/browser/session?client=video" -TimeoutSec 4 } catch {} }

<# 표시 대기 중에도 Virtual Console 세션이 끊기지 않게 1초마다 heartbeat를 보낸다. #>
function Wait-Sec([double]$sec) {
    $end = (Get-Date).AddSeconds($sec)
    while ((Get-Date) -lt $end) { Beat; Start-Sleep -Milliseconds 900 }
}

<# GILBEOT 로그에 pattern이 나올 때까지 기다린다. #>
function Wait-Log([string]$pattern, [int]$timeoutSec) {
    $end = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $end) {
        Beat
        $hit = & $adb logcat -d -s GILBEOT:I | Select-String -Pattern $pattern | Select-Object -First 1
        if ($hit) { return $hit.Line }
        Start-Sleep -Milliseconds 700
    }
    throw "로그 대기 시간 초과: $pattern"
}

<# 녹화를 시작하고 기준 시각을 돌려준다. #>
function Start-Record([string]$name, [int]$limit) {
    & $adb shell rm -f "/sdcard/$name.mp4" | Out-Null
    $p = Start-Process -FilePath $adb -ArgumentList @('shell', 'screenrecord', '--size', '1920x1080', '--bit-rate', '8000000', '--time-limit', "$limit", "/sdcard/$name.mp4") -NoNewWindow -PassThru
    Start-Sleep -Milliseconds 600
    return @{ proc = $p; t0 = Get-Date; marks = [ordered]@{} }
}

function Mark($rec, [string]$key) { $rec.marks[$key] = [math]::Round(((Get-Date) - $rec.t0).TotalSeconds, 2) }

<# 녹화를 끝내고 파일과 경과 시간 표식을 저장한다. #>
function Stop-Record($rec, [string]$name) {
    Mark $rec 'end'
    & $adb shell pkill -INT screenrecord | Out-Null
    $rec.proc.WaitForExit(15000) | Out-Null
    Start-Sleep -Seconds 2
    & $adb pull "/sdcard/$name.mp4" (Join-Path $outDir "$name.mp4") | Out-Null
    $rec.marks | ConvertTo-Json | Out-File (Join-Path $outDir "${name}_marks.json") -Encoding utf8
    Write-Host "$name marks: $($rec.marks | ConvertTo-Json -Compress)"
}

function Reset-Vehicle {
    Beat
    try { Put '/vehicle/controls?speed_kph=0' } catch {}
    try { Put '/vehicle/gear?gear=Park' } catch {}
    try { Put '/vehicle/lights?feature=headlamp_mode&value=Off' } catch {}
    try { Put '/vehicle/turn-signal?mode=Off' } catch {}
}

function Start-App([string]$now) {
    & $adb shell am force-stop ai.gilbeot.app | Out-Null
    & $adb logcat -c
    & $adb shell am start --user 10 -n ai.gilbeot.app/.ui.MainActivity --es now $now | Out-Null
}

& $adb forward tcp:8088 tcp:8088 | Out-Null
& $adb install -r $Apk | Out-Null
foreach ($perm in 'android.car.permission.CAR_SPEED', 'android.permission.ACCESS_FINE_LOCATION') {
    & $adb shell pm grant --user 10 ai.gilbeot.app $perm
}

try {
    # ---- 1) before ----
    Reset-Vehicle; Beat
    & $adb shell am force-stop ai.gilbeot.app | Out-Null
    Wait-Sec 2
    $rec = Start-Record 'demo_before' 175
    Wait-Sec 1.5
    Mark $rec 'launch'
    Start-App '15:30'
    $null = Wait-Log 'notice priority=100 key=route-1-promise-sunset' 40
    Mark $rec 'sunset'
    $null = Wait-Log 'onTBTInfo size=[1-9]' 20
    # 에뮬레이터에서는 TTS 두 단계가 시간 초과된 뒤 자막으로 나온다(약 16초). 자막이 뜬 뒤 출발한다.
    $null = Wait-Log 'tts subtitle-only text=해 지는' 40
    Mark $rec 'sunsetShown'
    Wait-Sec 6
    Mark $rec 'drive'
    Put '/vehicle/gear?gear=Drive'
    Put '/vehicle/controls?speed_kph=50'
    $null = Wait-Log 'key=route-1-turn-\d+-500' 90
    Mark $rec 'turn500'
    $null = Wait-Log 'key=route-1-turn-\d+-300' 60
    Mark $rec 'turn300'
    Wait-Sec 7
    Mark $rec 'headlamp'
    Put '/vehicle/lights?feature=headlamp_mode&value=LowBeam'
    $null = Wait-Log 'changeRouteOption call option=COMFORTABLE reason=headlamp ok' 15
    Wait-Sec 9
    Mark $rec 'stop'
    Put '/vehicle/controls?speed_kph=0'
    Wait-Sec 7
    Stop-Record $rec 'demo_before'

    # ---- 2) after ----
    Reset-Vehicle
    & $adb shell am force-stop ai.gilbeot.app | Out-Null
    Wait-Sec 3
    $rec = Start-Record 'demo_after' 60
    Wait-Sec 1.5
    Mark $rec 'launch'
    Start-App '17:00'
    $null = Wait-Log 'notice priority=100 key=route-1-promise-sunset' 40
    Mark $rec 'sunset'
    $null = Wait-Log 'changeRouteOption call option=COMFORTABLE reason=sunset ok' 15
    $null = Wait-Log 'tts subtitle-only text=도착은' 40
    Mark $rec 'sunsetShown'
    Wait-Sec 8
    Stop-Record $rec 'demo_after'
}
finally {
    Reset-Vehicle
}
