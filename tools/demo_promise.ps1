[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('before', 'after', 'headlamp', 'break')]
    [string]$Scenario,
    [string]$Destination = '37.5665,126.9780,서울시청',
    [string]$Apk
)

$ErrorActionPreference = 'Stop'
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$repo = Split-Path -Parent $PSScriptRoot
$apk = if ([string]::IsNullOrWhiteSpace($Apk)) {
    Join-Path $repo 'app\build\outputs\apk\debug\app-debug.apk'
} else {
    $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Apk)
}
$captureDir = Join-Path $repo 'captures'
$logPath = Join-Path $captureDir "M3_${Scenario}_log.txt"
$screenshotPath = Join-Path $captureDir "M3_${Scenario}.png"
$stderrPath = Join-Path ([IO.Path]::GetTempPath()) "gilbeot-promise-$Scenario-$PID.txt"
$package = 'ai.gilbeot.app'
$baseUri = 'http://127.0.0.1:8088'
$heartbeatJob = $null
$logProcess = $null
$readCount = 0

function Invoke-VehiclePut {
    param([Parameter(Mandatory = $true)][string]$Path)
    Invoke-RestMethod -Method Put -Uri ($baseUri + $Path) -TimeoutSec 5
}

function Set-Speed {
    param([Parameter(Mandatory = $true)][int]$Kph)
    $null = Invoke-VehiclePut -Path "/vehicle/controls?speed_kph=$Kph"
}

function Get-NewLogLines {
    if (-not (Test-Path -LiteralPath $logPath)) { return @() }
    $all = @(Get-Content -LiteralPath $logPath -Encoding UTF8)
    if ($all.Count -le $script:readCount) { return @() }
    $new = @($all[$script:readCount..($all.Count - 1)])
    $script:readCount = $all.Count
    return $new
}

function Show-NewLogLines {
    $new = @(Get-NewLogLines)
    foreach ($line in $new) { Write-Host $line }
    return $new
}

function Wait-LogMatch {
    param(
        [Parameter(Mandatory = $true)][string]$Pattern,
        [Parameter(Mandatory = $true)][int]$TimeoutSec,
        [int]$StartAt = 0,
        [switch]$Quiet
    )
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSec)
    while ([DateTime]::UtcNow -lt $deadline) {
        Start-Sleep -Milliseconds 200
        $null = Show-NewLogLines
        foreach ($line in @(Get-AllLogLines | Select-Object -Skip $StartAt)) {
            if ($line -match $Pattern) { return $line }
        }
    }
    if ($Quiet) { return $null }
    throw "${TimeoutSec}초 안에 로그를 찾지 못했습니다: $Pattern"
}

function Save-Screenshot {
    $remote = "/sdcard/M3_${Scenario}.png"
    & $adb shell screencap -p $remote | Out-Null
    if ($LASTEXITCODE -ne 0) { throw '에뮬레이터 스크린샷 생성에 실패했습니다.' }
    & $adb pull $remote $screenshotPath | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "스크린샷 가져오기에 실패했습니다: $screenshotPath" }
    & $adb shell rm $remote | Out-Null
}

function Get-AllLogLines {
    if (-not (Test-Path -LiteralPath $logPath)) { return @() }
    return @(Get-Content -LiteralPath $logPath -Encoding UTF8)
}

function Wait-OptionChangeResult {
    $result = Wait-LogMatch -Pattern 'newTbtAfterOptionChange|noNewTbtAfterOptionChange 30s' -TimeoutSec 35
    Write-Host "경로 옵션 변경 뒤 TBT 관측: $result"
    $routeStarted = @(Get-AllLogLines | Where-Object { $_ -match 'routeStartedAfterOptionChange' })
    if ($routeStarted.Count -gt 0) {
        Write-Host "경로 옵션 변경 뒤 onRouteStarted 관측: $($routeStarted[-1])"
    } else {
        Write-Host '경로 옵션 변경 뒤 onRouteStarted: 관측되지 않음'
    }
}

function Assert-CommonRoute {
    $sunset = Wait-LogMatch -Pattern 'promise sunset now=[0-9]{2}:[0-9]{2} arrival=[0-9]{2}:[0-9]{2}' -TimeoutSec 35
    Write-Host "실제 도착 판단: $sunset"
}

if (-not (Test-Path -LiteralPath $adb)) { throw "adb를 찾을 수 없습니다: $adb" }
if (-not (Test-Path -LiteralPath $apk)) { throw "APK를 찾을 수 없습니다: $apk" }
if (-not (Test-Path -LiteralPath $captureDir)) {
    New-Item -ItemType Directory -Path $captureDir | Out-Null
}

try {
    & $adb wait-for-device | Out-Null
    do {
        Start-Sleep -Seconds 1
        $bootCompleted = (& $adb shell getprop sys.boot_completed).Trim()
    } until ($bootCompleted -eq '1')

    & $adb install -r $apk
    if ($LASTEXITCODE -ne 0) { throw "adb install -r 실패: exit=$LASTEXITCODE" }
    & $adb shell pm grant --user 10 $package android.car.permission.CAR_SPEED | Out-Null
    & $adb shell pm grant --user 10 $package android.permission.ACCESS_FINE_LOCATION | Out-Null
    & $adb forward tcp:8088 tcp:8088 | Out-Null

    $heartbeatJob = Start-Job -ArgumentList $baseUri -ScriptBlock {
        param($Uri)
        while ($true) {
            try {
                $null = Invoke-RestMethod -Method Post -Uri "$Uri/browser/session?client=promise" -TimeoutSec 4
            } catch { }
            Start-Sleep -Seconds 2
        }
    }
    Start-Sleep -Seconds 1

    Set-Speed -Kph 0
    $null = Invoke-VehiclePut -Path '/vehicle/lights?feature=headlamp_mode&value=Off'
    $null = Invoke-VehiclePut -Path '/vehicle/turn-signal?mode=Off'
    $gear = Invoke-VehiclePut -Path '/vehicle/gear?gear=Drive'
    if ($gear.actual.value -ne 'Drive') {
        Write-Warning "기어 응답이 Drive가 아닙니다: $($gear.actual.value)"
        Read-Host 'Virtual Console에서 Drive로 바꾼 뒤 Enter를 누르세요'
    }

    & $adb logcat -G 16M | Out-Null
    & $adb logcat -c | Out-Null
    foreach ($path in @($logPath, $stderrPath, $screenshotPath)) {
        if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Force }
    }
    $logProcess = Start-Process -FilePath $adb -ArgumentList @(
        'logcat', '-v', 'time', '-s', 'GILBEOT:I'
    ) -RedirectStandardOutput $logPath -RedirectStandardError $stderrPath -NoNewWindow -PassThru

    & $adb shell am force-stop $package | Out-Null
    $startArgs = @('shell', 'am', 'start', '--user', '10', '-n', "$package/.ui.MainActivity", '--es', 'dest', $Destination)
    if ($Scenario -eq 'after') {
        $startArgs += @('--es', 'now', '17:00')
    } elseif ($Scenario -in @('before', 'headlamp')) {
        $startArgs += @('--es', 'now', '15:30')
    } elseif ($Scenario -eq 'break') {
        $startArgs += @('--ef', 'timeScale', '60')
    }
    & $adb @startArgs | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "앱 시작 실패: exit=$LASTEXITCODE" }

    Assert-CommonRoute

    if ($Scenario -eq 'before') {
        $null = Wait-LogMatch -Pattern 'notice priority=.*text=.*해 지기 전' -TimeoutSec 10
        $null = Wait-LogMatch -Pattern 'tts subtitle-only text=.*해 지기 전' -TimeoutSec 25
        Start-Sleep -Milliseconds 250
        Save-Screenshot
        $lines = @(Get-AllLogLines)
        if (@($lines | Where-Object { $_ -match 'changeRouteOption call' }).Count -ne 0) {
            throw 'before 시나리오에서 changeRouteOption이 호출됐습니다.'
        }
    }

    if ($Scenario -eq 'after') {
        $null = Wait-LogMatch -Pattern 'notice priority=.*text=.*해(가 )?진 뒤' -TimeoutSec 10
        $null = Wait-LogMatch -Pattern 'tts subtitle-only text=.*해(가 )?진 뒤' -TimeoutSec 25
        Start-Sleep -Milliseconds 250
        Save-Screenshot
        $null = Wait-LogMatch -Pattern 'changeRouteOption call option=COMFORTABLE reason=sunset' -TimeoutSec 10
        $null = Wait-LogMatch -Pattern 'changeRouteOption call option=COMFORTABLE reason=sunset ok' -TimeoutSec 10
        Wait-OptionChangeResult
    }

    if ($Scenario -eq 'headlamp') {
        Set-Speed -Kph 50
        Start-Sleep -Seconds 15
        $null = Invoke-VehiclePut -Path '/vehicle/lights?feature=headlamp_mode&value=LowBeam'
        $null = Wait-LogMatch -Pattern 'promise headlamp state=ON notices=2 requestComfortable=true' -TimeoutSec 10
        $null = Wait-LogMatch -Pattern 'notice priority=.*key=.*promise-headlamp-' -TimeoutSec 10
        $null = Wait-LogMatch -Pattern 'notice priority=.*key=.*promise-rest-' -TimeoutSec 10
        $null = Wait-LogMatch -Pattern 'changeRouteOption call option=COMFORTABLE reason=headlamp' -TimeoutSec 10
        $null = Wait-LogMatch -Pattern 'changeRouteOption call option=COMFORTABLE reason=headlamp ok' -TimeoutSec 10

        $null = Invoke-VehiclePut -Path '/vehicle/lights?feature=headlamp_mode&value=Off'
        Start-Sleep -Seconds 1
        $null = Invoke-VehiclePut -Path '/vehicle/lights?feature=headlamp_mode&value=LowBeam'
        $null = Wait-LogMatch -Pattern 'promise headlamp state=ON notices=0 requestComfortable=false' -TimeoutSec 10
        Set-Speed -Kph 0
        Start-Sleep -Seconds 1
        Save-Screenshot
        Wait-OptionChangeResult

        $onWithNotices = @(Get-AllLogLines | Where-Object {
            $_ -match 'promise headlamp state=ON notices=([1-9][0-9]*)'
        })
        if ($onWithNotices.Count -ne 1) {
            throw "전조등 안내가 정확히 1회가 아닙니다: $($onWithNotices.Count)회"
        }
    }

    if ($Scenario -eq 'break') {
        Set-Speed -Kph 50
        $breakNotice = Wait-LogMatch -Pattern 'notice priority=.*text=.*(쉬어|휴식)' -TimeoutSec 145
        Write-Host "휴식 안내 관측: $breakNotice"
        $null = Wait-LogMatch -Pattern 'tts subtitle-only text=.*(쉬어|휴식)' -TimeoutSec 10
        Start-Sleep -Milliseconds 250
        Save-Screenshot
        Set-Speed -Kph 0
        Start-Sleep -Seconds 5
        Set-Speed -Kph 50
        $resetStart = @(Get-AllLogLines).Count
        $reset = Wait-LogMatch -Pattern 'promise break drivenScaledMin=([0-5])([^0-9]|$)' -TimeoutSec 8 -StartAt $resetStart
        Write-Host "정차 뒤 타이머 재시작 관측: $reset"
        Start-Sleep -Seconds 1

        $breakNotices = @(Get-AllLogLines | Where-Object { $_ -match 'notice priority=.*text=.*(쉬어|휴식)' })
        if ($breakNotices.Count -ne 1) {
            throw "휴식 안내가 정확히 1회가 아닙니다: $($breakNotices.Count)회"
        }
    }

    $null = Show-NewLogLines
    Write-Host "M3 $Scenario 데모 완료: log=$logPath screenshot=$screenshotPath"
} finally {
    try { Set-Speed -Kph 0 } catch { Write-Warning "속도 0 복구 실패: $_" }
    try { $null = Invoke-VehiclePut -Path '/vehicle/gear?gear=Park' } catch { Write-Warning "Park 복구 실패: $_" }
    try { $null = Invoke-VehiclePut -Path '/vehicle/lights?feature=headlamp_mode&value=Off' } catch { Write-Warning "전조등 Off 복구 실패: $_" }
    try { $null = Invoke-VehiclePut -Path '/vehicle/turn-signal?mode=Off' } catch { Write-Warning "지시등 Off 복구 실패: $_" }
    if ($logProcess -and -not $logProcess.HasExited) {
        Stop-Process -Id $logProcess.Id -Force
        $logProcess.WaitForExit()
    }
    if (Test-Path -LiteralPath $stderrPath) {
        Remove-Item -LiteralPath $stderrPath -Force -ErrorAction SilentlyContinue
    }
    if ($heartbeatJob) {
        Stop-Job -Job $heartbeatJob -ErrorAction SilentlyContinue
        Remove-Job -Job $heartbeatJob -Force -ErrorAction SilentlyContinue
    }
}
