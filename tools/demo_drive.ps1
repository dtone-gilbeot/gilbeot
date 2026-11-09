[CmdletBinding()]
param(
    [string]$Destination = '37.5665,126.9780,서울시청',
    [int]$HeadlampAt = -1,
    [string]$GeoTrack,
    [int]$MaxDriveSeconds = 240,
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
$logPath = Join-Path $captureDir 'M2_demo_log.txt'
$stderrPath = Join-Path ([IO.Path]::GetTempPath()) "gilbeot-demo-log-$PID.txt"
$package = 'ai.gilbeot.app'
$baseUri = 'http://127.0.0.1:8088'
$heartbeatJob = $null
$geoJob = $null
$logProcess = $null

function Invoke-VehiclePut {
    param([Parameter(Mandatory = $true)][string]$Path)
    Invoke-RestMethod -Method Put -Uri ($baseUri + $Path) -TimeoutSec 5
}

function Set-Speed {
    param([Parameter(Mandatory = $true)][int]$Kph)
    $null = Invoke-VehiclePut -Path "/vehicle/controls?speed_kph=$Kph"
}

function Get-NewLogLines {
    param([ref]$ReadCount)
    if (-not (Test-Path -LiteralPath $logPath)) { return @() }
    $all = @(Get-Content -LiteralPath $logPath -Encoding UTF8)
    if ($all.Count -le $ReadCount.Value) { return @() }
    $new = @($all[$ReadCount.Value..($all.Count - 1)])
    $ReadCount.Value = $all.Count
    return $new
}

function Save-Screenshot {
    param([Parameter(Mandatory = $true)][string]$Name)
    $remote = "/sdcard/$Name"
    & $adb shell screencap -p $remote | Out-Null
    & $adb pull $remote (Join-Path $captureDir $Name) | Out-Null
    & $adb shell rm $remote | Out-Null
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
                $null = Invoke-RestMethod -Method Post -Uri "$Uri/browser/session?client=demo" -TimeoutSec 4
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
    foreach ($path in @($logPath, $stderrPath)) {
        if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Force }
    }
    $logProcess = Start-Process -FilePath $adb -ArgumentList @(
        'logcat', '-v', 'time', '-s', 'GILBEOT:I'
    ) -RedirectStandardOutput $logPath -RedirectStandardError $stderrPath -NoNewWindow -PassThru

    & $adb shell am force-stop $package | Out-Null
    & $adb shell am start --user 10 -n "$package/.ui.MainActivity" --es dest $Destination | Out-Null

    $readCount = 0
    $routeDeadline = [DateTime]::UtcNow.AddSeconds(35)
    $routeReady = $false
    while (-not $routeReady -and [DateTime]::UtcNow -lt $routeDeadline) {
        Start-Sleep -Milliseconds 250
        foreach ($line in @(Get-NewLogLines ([ref]$readCount))) {
            Write-Host $line
            if ($line -match 'onTBTInfo size=([1-9][0-9]*)') { $routeReady = $true }
        }
    }
    if (-not $routeReady) { throw '35초 안에 비어 있지 않은 TBT 목록을 받지 못했습니다.' }

    if ($GeoTrack) {
        $resolvedGeoTrack = (Resolve-Path -LiteralPath $GeoTrack).Path
        $geoJob = Start-Job -ArgumentList $adb, $resolvedGeoTrack -ScriptBlock {
            param($AdbPath, $CsvPath)
            foreach ($row in @(Import-Csv -LiteralPath $CsvPath)) {
                $names = @($row.PSObject.Properties.Name)
                $lat = if ($names -contains 'lat') { $row.lat } else { $row.latitude }
                $lng = if ($names -contains 'lng') { $row.lng } else { $row.longitude }
                if ($lat -and $lng) { & $AdbPath emu geo fix $lng $lat | Out-Null }
                Start-Sleep -Seconds 1
            }
        }
    }

    $driveStart = [DateTime]::UtcNow
    $headlampSet = $false
    for ($step = 1; $step -le 10; $step++) {
        Set-Speed -Kph ($step * 5)
        $elapsed = ([DateTime]::UtcNow - $driveStart).TotalSeconds
        if (-not $headlampSet -and $HeadlampAt -ge 0 -and $elapsed -ge $HeadlampAt) {
            $null = Invoke-VehiclePut -Path '/vehicle/lights?feature=headlamp_mode&value=LowBeam'
            $headlampSet = $true
        }
        Start-Sleep -Seconds 1
    }

    $captured500 = $false
    $captured300 = $false
    $firstNoticeKey = $null
    $currentTargetType = $null
    $demoTargetType = $null
    $blinkLeft = $false
    $slowed = $false
    $targetPassed = $false
    while (([DateTime]::UtcNow - $driveStart).TotalSeconds -lt $MaxDriveSeconds) {
        Start-Sleep -Milliseconds 200
        $elapsed = ([DateTime]::UtcNow - $driveStart).TotalSeconds
        if (-not $headlampSet -and $HeadlampAt -ge 0 -and $elapsed -ge $HeadlampAt) {
            $null = Invoke-VehiclePut -Path '/vehicle/lights?feature=headlamp_mode&value=LowBeam'
            $headlampSet = $true
        }

        foreach ($line in @(Get-NewLogLines ([ref]$readCount))) {
            Write-Host $line
            if ($line -match 'notice priority=(200|300) key=(route-[0-9]+-turn-[0-9]+)-(500|300) ') {
                $noticeKey = $Matches[2]
                $noticeDistance = $Matches[3]
                # 시연 대상 = 500m 밖에서 다가간 첫 대상 회전. 출발 때 이미 300m 안인 회전은 300m 안내만 나오므로 건너뛴다(11/08: 경로가 실행마다 바뀜).
                if ($null -eq $firstNoticeKey -and $noticeDistance -eq '500') {
                    $firstNoticeKey = $noticeKey
                    $demoTargetType = $currentTargetType
                }
                if ($noticeKey -eq $firstNoticeKey -and $noticeDistance -eq '500' -and -not $captured500) {
                    Start-Sleep -Milliseconds 150
                    Save-Screenshot -Name 'M2_500.png'
                    $captured500 = $true
                }
                if ($noticeKey -eq $firstNoticeKey -and $noticeDistance -eq '300' -and -not $captured300) {
                    Start-Sleep -Milliseconds 150
                    Save-Screenshot -Name 'M2_300.png'
                    $captured300 = $true
                }
            }
            if ($line -match 'remaining=(-?[0-9]+).*target=([A-Z0-9_]+):(-?[0-9]+)') {
                $targetType = $Matches[2]
                $targetRemaining = [int]$Matches[3]
                $currentTargetType = $targetType
                if ($null -ne $firstNoticeKey -and $null -eq $demoTargetType) {
                    $demoTargetType = $targetType
                }
                if ($captured500 -and -not $blinkLeft -and $targetType -eq $demoTargetType -and $targetRemaining -le 200) {
                    $null = Invoke-VehiclePut -Path '/vehicle/turn-signal?mode=BlinkLeft'
                    $blinkLeft = $true
                }
                if ($captured500 -and -not $slowed -and $targetType -eq $demoTargetType -and $targetRemaining -le 50) {
                    Set-Speed -Kph 20
                    $slowed = $true
                }
                if ($captured300 -and $targetType -ne $demoTargetType) {
                    $targetPassed = $true
                }
            }
        }
        if ($targetPassed) {
            Set-Speed -Kph 50
            $null = Invoke-VehiclePut -Path '/vehicle/turn-signal?mode=Off'
            Start-Sleep -Seconds 2
            break
        }
    }

    if (-not ($captured500 -and $captured300)) {
        throw "첫 대상 회전 캡처 누락: key=$firstNoticeKey 500m=$captured500 300m=$captured300"
    }
    $finalLines = @(Get-Content -LiteralPath $logPath -Encoding UTF8)
    $count500 = @($finalLines | Where-Object { $_ -match "notice priority=200 key=$firstNoticeKey-500 " }).Count
    $count300 = @($finalLines | Where-Object { $_ -match "notice priority=300 key=$firstNoticeKey-300 " }).Count
    if ($count500 -ne 1 -or $count300 -ne 1) {
        throw "첫 대상 회전 안내 횟수 오류: key=$firstNoticeKey 500m=$count500 300m=$count300"
    }
    Write-Host "M2 데모 완료: log=$logPath, 500m=$captured500, 300m=$captured300"
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
    if ($geoJob) {
        Stop-Job -Job $geoJob -ErrorAction SilentlyContinue
        Remove-Job -Job $geoJob -Force -ErrorAction SilentlyContinue
    }
    if ($heartbeatJob) {
        Stop-Job -Job $heartbeatJob -ErrorAction SilentlyContinue
        Remove-Job -Job $heartbeatJob -Force -ErrorAction SilentlyContinue
    }
}
