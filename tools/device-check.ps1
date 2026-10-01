# device-check.ps1 -- on-device acceptance for the native app, in one command.
#
# Why: the last open acceptance item for the native client is real hardware. Plug the
# phone in with USB debugging on and this script does the mechanical part: install the
# APK, launch it, prove it did not crash, run the in-app self-check headlessly, then
# sweep dark mode / rotation / background-foreground and prove the window really drew.
#
# The state channel is logcat, not screenshots: the app logs its own state under the
# tag "dsh-remote" (see MainActivity/Ui/ChatActivity), because uiautomator dump does
# not work on MIUI ("cmd: Can't find service: uiautomator") and a byte-diff of pixels
# proves nothing about content. Screenshots are still saved for a human to look at.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\device-check.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\device-check.ps1 -NoInstall -Quick
#
# Exit codes: 0 all green | 2 no device | 3 app crashed | 4 install failed | 5 self-check incomplete
# NOTE: keep this file ASCII only -- Windows PowerShell 5.1 reads BOM-less UTF-8 as GBK.
param(
  [string]$Apk = "",
  [string]$Serial = "",
  [switch]$NoInstall,
  [switch]$Quick
)

# Continue (not Stop) on purpose: adb writes warnings to stderr and PS turns those into
# terminating errors under Stop, which would abort the sweep on a harmless warning.
$ErrorActionPreference = "Continue"
$Root = Split-Path -Parent $PSScriptRoot

# adb writes its log to %TEMP%, and the sandbox denies writes outside the workspace.
$tmpRoot = Join-Path $Root "android\.tmp"
New-Item -ItemType Directory -Force $tmpRoot | Out-Null
$env:TEMP = $tmpRoot
$env:TMP = $tmpRoot

$adb = Join-Path $Root "android\android-sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { throw "adb.exe not found -- put platform-tools in android\android-sdk\platform-tools" }
if (-not $Apk) { $Apk = Join-Path $Root "android\dist\dsh-remote.apk" }
if (-not (Test-Path $Apk)) { throw "APK not found: $Apk" }

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$out = Join-Path $Root "tmp\device\$stamp"
New-Item -ItemType Directory -Force $out | Out-Null
$TAG = "dsh-remote:I"
$fails = 0

function Say([string]$m) { Write-Host $m }
function Sh([string]$cmd) { & $adb -s $Serial shell $cmd 2>$null }
function AppLogs() { return (& $adb -s $Serial logcat -d -s $TAG 2>$null) }
function WaitLog([string]$pattern, [int]$seconds) {
  $deadline = (Get-Date).AddSeconds($seconds)
  while ((Get-Date) -lt $deadline) {
    $hit = AppLogs | Select-String -Pattern $pattern -SimpleMatch
    if ($hit) { return $hit }
    Start-Sleep -Seconds 2
  }
  return $null
}
function Launch([string]$extra) {
  # force-stop first: on MIUI an am start over an already-resumed MainActivity only
  # delivers onNewIntent, so the cold-start onStart log (start/insets) never appears.
  Sh "am force-stop app.dsh.remote"
  Start-Sleep -Seconds 1
  Sh "logcat -c"
  if ($extra) {
    Sh "am start -W -n app.dsh.remote/.MainActivity $extra"
  } else {
    Sh "am start -W -n app.dsh.remote/.MainActivity"
  }
}
function Shot([string]$name) {
  $remote = "/sdcard/dsh-$name.png"
  Sh "screencap -p -d 0 $remote"
  $local = Join-Path $out "$name.png"
  & $adb -s $Serial pull $remote $local 2>$null | Out-Null
  if (Test-Path $local) { return $local }
  return ""
}
function Crashed() {
  $c = & $adb -s $Serial logcat -d -b crash 2>$null
  return ($c | Select-String -Pattern "app.dsh.remote" -SimpleMatch)
}
function Show([string]$title, $lines) {
  Say "  [$title]"
  if (-not $lines) { Say "    (nothing)"; return }
  # .Line, not ToString(): MatchInfo.ToString() prints blank here under PS 5.1.
  $lines | Select-Object -First 30 | ForEach-Object { Say "    " + ("" + $_.Line).Trim() }
}
function Pass([string]$what) { Say "PASS  $what" }
function Bad([string]$what) { Say "FAIL  $what"; $script:fails = $script:fails + 1 }

# ---------------------------------------------------------------- device
$devices = (& $adb devices) | Select-String -Pattern "\sdevice$"
if (-not $devices) {
  Say "NO_DEVICE: adb sees no authorized device."
  Say "  1) phone: USB cable in, Settings -> Developer options -> USB debugging ON"
  Say "  2) phone: accept the 'Allow USB debugging?' prompt"
  Say "  3) pc:    '$adb devices' must list it as 'device' (not 'unauthorized'/'offline')"
  Say "  4) some MIUI builds also need 'USB debugging (Security settings)' ON"
  exit 2
}
if (-not $Serial) { $Serial = ($devices[0].ToString() -split "\s+")[0] }
$model = (Sh "getprop ro.product.manufacturer").Trim() + " " + (Sh "getprop ro.product.model").Trim()
$os = (Sh "getprop ro.build.version.release").Trim() + " (API " + (Sh "getprop ro.build.version.sdk").Trim() + ")"
Say "device : $Serial  $model  Android $os"

# ---------------------------------------------------------------- install
if (-not $NoInstall) {
  $res = (& $adb -s $Serial install -r $Apk 2>&1) -join " | "
  Say "install: $res"
  if ($res -notmatch "Success") {
    Say "  INSTALL_FAILED_UPDATE_INCOMPATIBLE -> adb uninstall app.dsh.remote, then re-run"
    Say "  INSTALL_FAILED_USER_RESTRICTED     -> MIUI: allow 'Install via USB' in Developer options"
    exit 4
  }
  Pass "install"
}

# ---------------------------------------------------------------- phase 1: launch + session list
Say ""
Say "phase 1: launch and load the session list"
Sh "am force-stop app.dsh.remote"
& $adb -s $Serial logcat -b crash -c | Out-Null
Launch ""
$start = WaitLog "start ready=" 30
$list = WaitLog "sessions n=" 30
Show "start" $start
Show "list" $list
if ($start) { Pass "app started on device" } else { Bad "no start log (app did not run?)" }
if ($list -and ($list -match "n=0")) { Bad "session list came back empty" }
elseif ($list) { Pass "session list loaded over the network" }
else { Bad "session list never logged" }
$err = Crashed
if ($err) { Show "crash" $err; Bad "crash in the crash buffer" } else { Pass "no crash" }
$shot = Shot "1-list"
if ($shot) { Say "  screenshot: $shot" }

# ---------------------------------------------------------------- phase 2: in-app self-check
Say ""
Say "phase 2: in-app self-check (headless via --ez selfcheck true)"
Launch "--ez selfcheck true"
$check = WaitLog "SELFCHECK 3." 60
if (-not $check) { $check = WaitLog "SELFCHECK 1." 10 }
$all = AppLogs | Select-String -Pattern "SELFCHECK" -SimpleMatch
$all | Out-File (Join-Path $out "selfcheck.txt") -Encoding UTF8
Show "self-check" $all
if ($all -and ($all | Select-String -Pattern "HTTP 通了" -SimpleMatch) -and ($all | Select-String -Pattern "WebSocket 通了" -SimpleMatch) -and ($all | Select-String -Pattern "会话流通了" -SimpleMatch)) {
  Pass "self-check: HTTP + WebSocket + session stream all green on device"
} else {
  Bad "self-check incomplete -- see selfcheck.txt"
}
$shot = Shot "2-selfcheck"
if ($shot) { Say "  screenshot: $shot" }

# ---------------------------------------------------------------- phase 3: insets / theme / rotation / background
if (-not $Quick) {
  Say ""
  Say "phase 3: insets, dark mode, rotation, background-foreground"

  Say "  dark mode: on"
  Sh "cmd uimode night yes" | Out-Null
  Launch ""
  $darkOn = WaitLog "dark=true" 25
  $insetsOn = WaitLog "insets bars=" 25
  Show "dark" $darkOn
  Show "insets" $insetsOn
  if ($darkOn) { Pass "dark mode is reported as active" } else { Bad "no dark=true after switching the system to dark" }
  if ($insetsOn) { Pass "window insets measured (gesture bar / keyboard handling has real numbers)" } else { Bad "no insets log" }
  $shot = Shot "3-dark"
  if ($shot) { Say "  screenshot: $shot" }

  Say "  dark mode: off"
  Sh "cmd uimode night no" | Out-Null
  Launch ""
  $darkOff = WaitLog "dark=false" 25
  if ($darkOff) { Pass "light mode is reported as active" } else { Bad "no dark=false after switching back" }

  Say "  rotation: landscape"
  Sh "settings put system accelerometer_rotation 0" | Out-Null
  Sh "settings put system user_rotation 1" | Out-Null
  Start-Sleep -Seconds 2
  $rot = WaitLog "insets bars=" 20
  $shot = Shot "4-landscape"
  if ($shot) { Say "  screenshot: $shot" }
  if ($rot) { Pass "landscape redraw produced insets (no crash on rotation)" } else { Say "  (no new insets line -- check the screenshot)" }
  Sh "settings put system user_rotation 0" | Out-Null
  Sh "settings put system accelerometer_rotation 1" | Out-Null
  Start-Sleep -Seconds 2

  Say "  background/foreground: home then resume"
  Sh "input keyevent 3" | Out-Null
  Start-Sleep -Seconds 3
  Sh "logcat -c"
  Sh "am start -n app.dsh.remote/.MainActivity" | Out-Null
  $back = WaitLog "sessions n=" 30
  Show "resume" $back
  if ($back) { Pass "reconnected after background-foreground" } else { Bad "no session list after resume" }
  $err = Crashed
  if ($err) { Show "crash" $err; Bad "crash after the sweep" }
}

# ---------------------------------------------------------------- phase 4: did it actually draw?
Say ""
Say "phase 4: rendering proof"
$gfx = & $adb -s $Serial shell dumpsys gfxinfo app.dsh.remote 2>$null
$gfx | Out-File (Join-Path $out "gfxinfo.txt") -Encoding UTF8
$frames = $gfx | Select-String -Pattern "Total frames rendered" -SimpleMatch
Show "gfxinfo" $frames
if ($frames -and ($frames.ToString() -notmatch "Total frames rendered: 0")) {
  Pass "the window rendered frames"
} else {
  Bad "no frames rendered -- the app may be running headless or the UI never drew"
}

& $adb -s $Serial logcat -d -b crash | Out-File (Join-Path $out "crash.txt") -Encoding UTF8

Say ""
Say "artifacts: $out"
if ($fails -gt 0) { Say "RESULT: $fails check(s) failed"; exit 3 }
Say "RESULT: ALL GREEN"
exit 0