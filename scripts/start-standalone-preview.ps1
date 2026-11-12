param(
    [ValidatePattern('^[A-Za-z0-9_-]+$')][string]$Avd='Pixel_10a',
    [string]$Sdk="$env:LOCALAPPDATA\Android\Sdk",
    [switch]$ColdBoot,
    [ValidateSet('cn.musicpilgrimage.app.MainActivity','cn.musicpilgrimage.app.MusicSpacePreviewActivity')]
    [string]$Activity='cn.musicpilgrimage.app.MainActivity'
)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskAdb=Join-Path $Sdk 'platform-tools\adb.exe'
$taskEmulator=Join-Path $Sdk 'emulator\emulator.exe'
$taskConfig=Join-Path $env:USERPROFILE ".android\avd\$Avd.avd\config.ini"
$taskLog=Join-Path $taskRoot 'artifacts\standalone-preview.log'
$taskActivity=$Activity
$taskPackage='cn.musicpilgrimage.app'
$taskLock=New-Object System.Threading.Mutex($false,'Local\TingzongStandalonePreview')
$taskOwnsLock=$false

function Write-TaskLog([string]$Message) {
    Add-Content -LiteralPath $taskLog -Value ("{0} {1}" -f [DateTime]::UtcNow.ToString('O'),$Message) -Encoding UTF8
}
function Invoke-TaskAdb([string[]]$Arguments,[switch]$AllowFailure) {
    $taskOldPreference=$ErrorActionPreference
    $ErrorActionPreference='Continue'
    try {
        $taskOutput=@(& $taskAdb @Arguments 2>&1 | ForEach-Object {"$_"})
        $taskExit=$LASTEXITCODE
    } finally {$ErrorActionPreference=$taskOldPreference}
    $taskResult=[pscustomobject]@{Code=$taskExit;Text=($taskOutput -join "`n")}
    if($taskExit -ne 0 -and !$AllowFailure){throw "ADB failed: $($taskResult.Text)"}
    return $taskResult
}
function Get-TaskDevices {
    $taskList=Invoke-TaskAdb -Arguments @('devices')
    foreach($taskLine in ($taskList.Text -split "`n")) {
        if($taskLine -match '^(emulator-\d+)\s+device\s*$') {
            $taskSerial=$Matches[1]
            $taskName=Invoke-TaskAdb -Arguments @('-s',$taskSerial,'emu','avd','name') -AllowFailure
            if(@($taskName.Text -split "`n" | ForEach-Object {$_.Trim()}) -contains $Avd){$taskSerial}
        }
    }
}
function Get-TaskEmulatorProcesses {
    @(Get-CimInstance Win32_Process | Where-Object {
        $_.Name -match '^(emulator|qemu-system-x86_64)\.exe$' -and
        $_.CommandLine -match ('-avd\s+"?'+[regex]::Escape($Avd)+'(?:"|\s|$)')
    })
}
function Set-TaskPreviewWindow {
    # Guest resolution and desktop window scale are independent. -scale is obsolete.
    Add-Type -AssemblyName System.Windows.Forms
    $taskArea=[System.Windows.Forms.Screen]::PrimaryScreen.WorkingArea
    $taskSkinLayout=Join-Path $Sdk 'skins\pixel_10a\layout'
    $taskSkinWidth=1218.0
    $taskSkinHeight=2553.0
    if(Test-Path -LiteralPath $taskSkinLayout) {
        $taskLayout=[System.IO.File]::ReadAllText($taskSkinLayout)
        $taskLayoutMatch=[regex]::Match($taskLayout,'(?s)layouts\s*\{\s*portrait\s*\{\s*width\s+(\d+)\s+height\s+(\d+)')
        if($taskLayoutMatch.Success){$taskSkinWidth=[double]$taskLayoutMatch.Groups[1].Value;$taskSkinHeight=[double]$taskLayoutMatch.Groups[2].Value}
    }
    $taskScale=[Math]::Min(.28,[Math]::Min(($taskArea.Height-112)/$taskSkinHeight,($taskArea.Width-160)/$taskSkinWidth))
    $taskScale=[Math]::Max(.1,[Math]::Floor($taskScale*1000)/1000)
    $taskWindowIni=Join-Path (Split-Path $taskConfig -Parent) 'emulator-user.ini'
    $taskWindowText=if(Test-Path -LiteralPath $taskWindowIni){[System.IO.File]::ReadAllText($taskWindowIni)}else{''}
    $taskWindowValues=@{
        'window.scale'=$taskScale.ToString('0.000000',[Globalization.CultureInfo]::InvariantCulture)
        'window.x'=($taskArea.X+40).ToString()
        'window.y'=($taskArea.Y+40).ToString()
    }
    foreach($taskWindowKey in $taskWindowValues.Keys) {
        $taskWindowPattern='(?m)^'+[regex]::Escape($taskWindowKey)+'\s*=.*$'
        $taskWindowLine=$taskWindowKey+' = '+$taskWindowValues[$taskWindowKey]
        if($taskWindowText -match $taskWindowPattern){$taskWindowText=[regex]::Replace($taskWindowText,$taskWindowPattern,$taskWindowLine)}
        else{$taskWindowText+="`r`n$taskWindowLine"}
    }
    [System.IO.File]::WriteAllText($taskWindowIni,$taskWindowText,(New-Object System.Text.UTF8Encoding($false)))
    Write-TaskLog "Desktop window scale $taskScale; work area $($taskArea.Width)x$($taskArea.Height)."
}
function Show-TaskWindow {
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class TingzongPreviewWindow {
    [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr window);
    [DllImport("user32.dll")] public static extern bool ShowWindowAsync(IntPtr window, int command);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr window);
}
'@
    foreach($taskNativeProcess in (Get-TaskEmulatorProcesses)) {
        $taskWindowProcess=Get-Process -Id $taskNativeProcess.ProcessId -ErrorAction SilentlyContinue
        if($taskWindowProcess -and $taskWindowProcess.MainWindowHandle -ne [IntPtr]::Zero) {
            $taskHandle=$taskWindowProcess.MainWindowHandle
            if([TingzongPreviewWindow]::IsIconic($taskHandle)){[TingzongPreviewWindow]::ShowWindowAsync($taskHandle,9) | Out-Null}
            [TingzongPreviewWindow]::SetForegroundWindow($taskHandle) | Out-Null
        }
    }
}
try {
    try {$taskOwnsLock=$taskLock.WaitOne(0)} catch [System.Threading.AbandonedMutexException] {$taskOwnsLock=$true}
    if(!$taskOwnsLock){exit 0}
    New-Item -ItemType Directory -Path (Split-Path $taskLog -Parent) -Force | Out-Null
    [System.IO.File]::WriteAllText($taskLog,'')
    if(!(Test-Path -LiteralPath $taskEmulator) -or !(Test-Path -LiteralPath $taskConfig)){throw 'Android emulator or Pixel_10a configuration was not found.'}
    $taskSerial=@(Get-TaskDevices) | Select-Object -First 1
    $taskProcesses=Get-TaskEmulatorProcesses
    $taskColdBootRequested=$ColdBoot -and [bool]$taskSerial
    if($taskColdBootRequested) {
        Write-TaskLog "Cold boot requested for $taskSerial; user data will be retained."
    }
    if($taskColdBootRequested -or ($taskProcesses | Where-Object {$_.CommandLine -match '-qt-hide-window|-no-window'})) {
        if(!$taskSerial){throw 'The embedded emulator is still starting. Try again after Android has booted.'}
        Write-TaskLog "Stopping emulator $taskSerial gracefully."
        Invoke-TaskAdb -Arguments @('-s',$taskSerial,'shell','input','keyevent','KEYCODE_HOME') | Out-Null
        Invoke-TaskAdb -Arguments @('-s',$taskSerial,'emu','kill') | Out-Null
        $taskDeadline=[DateTime]::UtcNow.AddSeconds(60)
        while(@(Get-TaskEmulatorProcesses).Count -gt 0) {
            if([DateTime]::UtcNow -gt $taskDeadline){throw 'The previous emulator is still closing. Try again shortly.'}
            Start-Sleep -Milliseconds 500
        }
        $taskSerial=$null
    }
    $taskIni=[System.IO.File]::ReadAllText($taskConfig)
    $taskDefaults=@{'hw.lcd.width'='720';'hw.lcd.height'='1616';'hw.lcd.density'='280';'hw.gpu.enabled'='yes';'hw.gpu.mode'='host'}
    $taskIniBefore=$taskIni
    foreach($taskKey in $taskDefaults.Keys) {
        $taskPattern='(?m)^'+[regex]::Escape($taskKey)+'=.*$'
        if($taskIni -match $taskPattern){$taskIni=[regex]::Replace($taskIni,$taskPattern,($taskKey+'='+$taskDefaults[$taskKey]))}
        else {$taskIni+="`r`n$taskKey=$($taskDefaults[$taskKey])"}
    }
    if($taskIni -ne $taskIniBefore){[System.IO.File]::WriteAllText($taskConfig,$taskIni,(New-Object System.Text.UTF8Encoding($false)))}
    if(!$taskSerial) {
        Set-TaskPreviewWindow
        # Restoring an older snapshot can hang before ADB is online. Cold boot keeps user data.
        $taskArguments=@('-avd',$Avd,'-gpu','host','-port','5580','-no-snapshot-load')
        $taskAudioOverrides=@()
        try {
            if($Avd -eq 'Pixel_10a') {
                # Let virtio-snd deliver its next PCM period before QEMU drains it.
                # Keep the output at the guest mixer's rate and allow enough DirectSound lead time.
                $taskAudioDefaults=@{QEMU_AUDIO_TIMER_PERIOD='80';QEMU_AUDIO_DAC_FIXED_FREQ='48000';QEMU_DSOUND_LATENCY_MILLIS='40'}
                foreach($taskAudioSetting in $taskAudioDefaults.GetEnumerator()) {
                    if($null -eq [Environment]::GetEnvironmentVariable($taskAudioSetting.Key,'Process')) {
                        [Environment]::SetEnvironmentVariable($taskAudioSetting.Key,$taskAudioSetting.Value,'Process')
                        $taskAudioOverrides+=$taskAudioSetting.Key
                    }
                }
                Write-TaskLog "Pixel_10a audio: $env:QEMU_AUDIO_TIMER_PERIOD Hz mixer, $env:QEMU_AUDIO_DAC_FIXED_FREQ Hz output, $env:QEMU_DSOUND_LATENCY_MILLIS ms DirectSound lead."
            }
            $taskProcess=Start-Process -FilePath $taskEmulator -ArgumentList $taskArguments -WindowStyle Normal -PassThru -RedirectStandardOutput (Join-Path $taskRoot 'artifacts\standalone-emulator.log') -RedirectStandardError (Join-Path $taskRoot 'artifacts\standalone-emulator-error.log')
        } finally {
            foreach($taskAudioKey in $taskAudioOverrides){[Environment]::SetEnvironmentVariable($taskAudioKey,$null,'Process')}
        }
        Write-TaskLog "Standalone visible emulator started (PID $($taskProcess.Id))."
    }
    $taskDeadline=[DateTime]::UtcNow.AddSeconds(180)
    while($true) {
        $taskSerial=@(Get-TaskDevices) | Select-Object -First 1
        if($taskSerial) {
            $taskBoot=Invoke-TaskAdb -Arguments @('-s',$taskSerial,'shell','getprop','sys.boot_completed') -AllowFailure
            if($taskBoot.Code -eq 0 -and $taskBoot.Text.Trim() -eq '1'){break}
        }
        if([DateTime]::UtcNow -gt $taskDeadline){throw 'Emulator startup timed out. See the launcher log.'}
        Start-Sleep -Seconds 2
    }
    Invoke-TaskAdb -Arguments @('-s',$taskSerial,'shell','wm','size','720x1616') | Out-Null
    Invoke-TaskAdb -Arguments @('-s',$taskSerial,'shell','wm','density','280') | Out-Null
    Write-TaskLog "Android ready on $taskSerial; 720x1616, density 280."
    $taskResolved=Invoke-TaskAdb -Arguments @('-s',$taskSerial,'shell','cmd','package','resolve-activity','--brief','-n',"$taskPackage/$taskActivity") -AllowFailure
    $taskResolvedLines=$taskResolved.Text -split '\r?\n'
    $taskShortActivity=$taskActivity
    if($taskActivity.StartsWith("$taskPackage.")){$taskShortActivity=$taskActivity.Substring($taskPackage.Length)}
    if($taskResolved.Code -ne 0 -or
        ("$taskPackage/$taskActivity" -notin $taskResolvedLines -and "$taskPackage/$taskShortActivity" -notin $taskResolvedLines)) {
        $taskApk=Join-Path $taskRoot 'artifacts\standalone\Tingzong-full-debug.apk'
        if(!(Test-Path -LiteralPath $taskApk)){throw "The APK for $taskPackage is missing: $taskApk"}
        Write-TaskLog "Installing $taskPackage from $taskApk"
        Invoke-TaskAdb -Arguments @('-s',$taskSerial,'install','-r',$taskApk) | Out-Null
    }
    $taskOpened=Invoke-TaskAdb -Arguments @('-s',$taskSerial,'shell','am','start','-W','--activity-reorder-to-front','--activity-single-top','-n',"$taskPackage/$taskActivity")
    if($taskOpened.Text -match 'Error:|Error type'){throw $taskOpened.Text}
    Show-TaskWindow
    Write-TaskLog 'Ready: full app opened in the independent emulator window.'
} catch {
    Write-TaskLog $_.Exception.Message
    Add-Type -AssemblyName System.Windows.Forms
    [System.Windows.Forms.MessageBox]::Show("启动没有完成。请把此日志交给我检查：`n$taskLog`n`n$($_.Exception.Message)",'听踪试玩') | Out-Null
    exit 1
} finally {
    if($taskOwnsLock){$taskLock.ReleaseMutex()}
    $taskLock.Dispose()
}
