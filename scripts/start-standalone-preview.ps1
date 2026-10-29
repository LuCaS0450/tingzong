param(
    [ValidatePattern('^[A-Za-z0-9_-]+$')][string]$Avd='Pixel_10a',
    [string]$Sdk="$env:LOCALAPPDATA\Android\Sdk",
    [switch]$ColdBoot,
    [ValidateSet('cn.musicpilgrimage.app.performance.RhythmComparisonActivity','cn.musicpilgrimage.app.MainActivity','cn.musicpilgrimage.app.MusicSpacePreviewActivity')]
    [string]$Activity='cn.musicpilgrimage.app.MainActivity'
)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskAdb=Join-Path $Sdk 'platform-tools\adb.exe'
$taskEmulator=Join-Path $Sdk 'emulator\emulator.exe'
$taskConfig=Join-Path $env:USERPROFILE ".android\avd\$Avd.avd\config.ini"
$taskLog=Join-Path $taskRoot 'artifacts\standalone-preview.log'
$taskPackage='cn.musicpilgrimage.app.review'
$taskActivity=$Activity
$taskIsMusicSpace=$taskActivity -in @('cn.musicpilgrimage.app.MainActivity','cn.musicpilgrimage.app.MusicSpacePreviewActivity')
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
function Ensure-TaskMusic([string]$Serial) {
    $taskPreferences=Invoke-TaskAdb -Arguments @('-s',$Serial,'shell','run-as',$taskPackage,'test','-f','shared_prefs/rhythm-comparison-local.xml') -AllowFailure
    $taskExisting=Invoke-TaskAdb -Arguments @('-s',$Serial,'shell','run-as',$taskPackage,'test','-f','files/rhythm-comparison/original.pcmz') -AllowFailure
    if($taskPreferences.Code -eq 0 -or $taskExisting.Code -eq 0){Write-TaskLog 'Existing local audio retained.';return}
    $taskAudio=Join-Path $taskRoot '.tooling\release-reference\comparison'
    $taskNames=@('original.pcmz','comparison.json')
    if(@($taskNames | Where-Object {!(Test-Path -LiteralPath (Join-Path $taskAudio $_))}).Count -gt 0) {
        $taskAudio=Join-Path $taskRoot '.tooling\standalone-preview-audio'
        New-Item -ItemType Directory -Path $taskAudio -Force | Out-Null
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $taskZip=[System.IO.Compression.ZipFile]::OpenRead((Join-Path $taskRoot 'artifacts\performance\Tingzong-rhythm-audio-local.zip'))
        try {foreach($taskName in $taskNames) {
            $taskEntry=$taskZip.GetEntry($taskName)
            if($null -eq $taskEntry -or $taskEntry.Length -gt 16000000){throw 'Invalid local audio bundle.'}
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($taskEntry,(Join-Path $taskAudio $taskName),$true)
        }} finally {$taskZip.Dispose()}
    }
    $taskTemporary='/data/local/tmp/tingzong-preview-'+[guid]::NewGuid().ToString()
    Invoke-TaskAdb -Arguments @('-s',$Serial,'shell','mkdir','-p',$taskTemporary) | Out-Null
    Invoke-TaskAdb -Arguments @('-s',$Serial,'shell','run-as',$taskPackage,'mkdir','-p','files/rhythm-comparison') | Out-Null
    foreach($taskName in $taskNames) {
        Invoke-TaskAdb -Arguments @('-s',$Serial,'push',(Join-Path $taskAudio $taskName),"$taskTemporary/$taskName") | Out-Null
        Invoke-TaskAdb -Arguments @('-s',$Serial,'shell','run-as',$taskPackage,'cp',"$taskTemporary/$taskName","files/rhythm-comparison/$taskName") | Out-Null
        Invoke-TaskAdb -Arguments @('-s',$Serial,'shell','rm',"$taskTemporary/$taskName") | Out-Null
    }
    Invoke-TaskAdb -Arguments @('-s',$Serial,'shell','rmdir',$taskTemporary) | Out-Null
    Write-TaskLog 'Local comparison audio prepared; no account or travel data changed.'
}

try {
    try {$taskOwnsLock=$taskLock.WaitOne(0)} catch [System.Threading.AbandonedMutexException] {$taskOwnsLock=$true}
    if(!$taskOwnsLock){exit 0}
    New-Item -ItemType Directory -Path (Split-Path $taskLog -Parent) -Force | Out-Null
    [System.IO.File]::WriteAllText($taskLog,'')
    if(!(Test-Path -LiteralPath $taskEmulator) -or !(Test-Path -LiteralPath $taskConfig)){throw 'Android emulator or Pixel_10a configuration was not found.'}
    $taskSerial=@(Get-TaskDevices) | Select-Object -First 1
    $taskProcesses=Get-TaskEmulatorProcesses
    if($taskProcesses | Where-Object {$_.CommandLine -match '-qt-hide-window|-no-window'}) {
        if(!$taskSerial){throw 'The embedded emulator is still starting. Try again after Android has booted.'}
        Write-TaskLog "Stopping embedded/background emulator $taskSerial gracefully."
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
        $taskProcess=Start-Process -FilePath $taskEmulator -ArgumentList $taskArguments -WindowStyle Normal -PassThru -RedirectStandardOutput (Join-Path $taskRoot 'artifacts\standalone-emulator.log') -RedirectStandardError (Join-Path $taskRoot 'artifacts\standalone-emulator-error.log')
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
    if($taskResolved.Text -notmatch [regex]::Escape($taskActivity)) {
        $taskApk=if($taskIsMusicSpace){
            $taskMusicApk=Join-Path $taskRoot 'artifacts\music-space\Tingzong-swiss-unified-private.apk'
            if(!(Test-Path -LiteralPath $taskMusicApk)){$taskMusicApk=Join-Path $taskRoot 'artifacts\music-space\Tingzong-three-walls-private.apk'}
            if(!(Test-Path -LiteralPath $taskMusicApk)){$taskMusicApk=Join-Path $taskRoot 'artifacts\music-space\Tingzong-home-furniture-private.apk'}
            if(!(Test-Path -LiteralPath $taskMusicApk)){$taskMusicApk=Join-Path $taskRoot 'artifacts\music-space\Tingzong-shelf-refinement-private.apk'}
            if(!(Test-Path -LiteralPath $taskMusicApk)){$taskMusicApk=Join-Path $taskRoot 'artifacts\performance\Tingzong-performance-fixes-private.apk'}
            if(!(Test-Path -LiteralPath $taskMusicApk)){$taskMusicApk=Join-Path $taskRoot 'artifacts\performance\Tingzong-fifteen-tracks-private.apk'}
            if(!(Test-Path -LiteralPath $taskMusicApk)){$taskMusicApk=Join-Path $taskRoot 'artifacts\music-space\Tingzong-visual-fixes-private.apk'}
            if(!(Test-Path -LiteralPath $taskMusicApk)){$taskMusicApk=Join-Path $taskRoot 'artifacts\performance\Tingzong-three-tracks-private.apk'}
            if(!(Test-Path -LiteralPath $taskMusicApk)){$taskMusicApk=Join-Path $taskRoot 'artifacts\music-space\Tingzong-music-space-stage8-acceptance.apk'}
            if(!(Test-Path -LiteralPath $taskMusicApk)){$taskMusicApk=Join-Path $taskRoot 'artifacts\music-space\Tingzong-music-space-stage8.apk'}
            if(!(Test-Path -LiteralPath $taskMusicApk)){$taskMusicApk=Join-Path $taskRoot 'artifacts\music-space\Tingzong-music-space-stage7.apk'}
            if(!(Test-Path -LiteralPath $taskMusicApk)){$taskMusicApk=Join-Path $taskRoot 'artifacts\music-space\Tingzong-music-space-stage6.apk'}
            if(!(Test-Path -LiteralPath $taskMusicApk)){$taskMusicApk=Join-Path $taskRoot 'artifacts\music-space\Tingzong-music-space-stage5.apk'}
            if(!(Test-Path -LiteralPath $taskMusicApk)){$taskMusicApk=Join-Path $taskRoot 'artifacts\music-space\Tingzong-music-space-stage4.apk'}
            if(Test-Path -LiteralPath $taskMusicApk){$taskMusicApk}else{
                $taskPreviousMusicApk=Join-Path $taskRoot 'artifacts\music-space\Tingzong-music-space-stage3.apk'
                if(Test-Path -LiteralPath $taskPreviousMusicApk){$taskPreviousMusicApk}else{
                    $taskActivity='cn.musicpilgrimage.app.MainActivity'
                    $taskOlderMusicApk=Join-Path $taskRoot 'artifacts\music-space\Tingzong-music-space-stage2.apk'
                    if(Test-Path -LiteralPath $taskOlderMusicApk){$taskOlderMusicApk}else{Join-Path $taskRoot 'artifacts\music-space\Tingzong-music-space-stage1.apk'}
                }
            }
        }else{Join-Path $taskRoot 'artifacts\performance\Tingzong-rhythm-comparison-private.apk'}
        if(!(Test-Path -LiteralPath $taskApk)){throw 'The requested preview APK has not been built yet.'}
        Write-TaskLog 'Installing the existing private preview APK.'
        Invoke-TaskAdb -Arguments @('-s',$taskSerial,'install','-r',$taskApk) | Out-Null
    }
    if($taskActivity -eq 'cn.musicpilgrimage.app.performance.RhythmComparisonActivity'){Ensure-TaskMusic $taskSerial}
    $taskOpened=Invoke-TaskAdb -Arguments @('-s',$taskSerial,'shell','am','start','-W','--activity-single-top','-n',"$taskPackage/$taskActivity")
    if($taskOpened.Text -match 'Error:|Error type'){throw $taskOpened.Text}
    Show-TaskWindow
    Write-TaskLog $(if($taskIsMusicSpace){'Ready: native music space opened in the independent emulator window.'}else{'Ready: rhythm comparison opened in the independent emulator window.'})
} catch {
    Write-TaskLog $_.Exception.Message
    Add-Type -AssemblyName System.Windows.Forms
    [System.Windows.Forms.MessageBox]::Show("启动没有完成。请把此日志交给我检查：`n$taskLog`n`n$($_.Exception.Message)",'听踪试玩') | Out-Null
    exit 1
} finally {
    if($taskOwnsLock){$taskLock.ReleaseMutex()}
    $taskLock.Dispose()
}
