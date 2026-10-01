# Keeps the frp client alive and starts it at logon, with no console window to close.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\frp-task.ps1
#       register (or repair) the "DSH frp Client" task and start the tunnel now
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\frp-task.ps1 -Off
#       disable the task and stop the tunnel
#
# Same self-heal shape as the natapp and bridge tasks: the action runs tools\frp-start.vbs,
# which exits immediately if frpc.exe is already running, so the 5-minute repeat can never
# produce a second client. This is the VPS/frp route; the natapp task can stay registered
# next to it (only one of them should be running at a time).
[CmdletBinding()]
param([switch]$Off, [string]$TaskName = 'DSH frp Client')

$ErrorActionPreference = 'Stop'
$vbs  = Join-Path $PSScriptRoot 'frp-start.vbs'
$log  = Join-Path $PSScriptRoot 'frpc.log'
$conf = Join-Path $PSScriptRoot 'frp\frpc.toml'

function Stop-Tunnel {
  Get-Process frpc -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
}

if ($Off) {
  Disable-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue | Out-Null
  Stop-Tunnel
  "frp: task '$TaskName' disabled, tunnel stopped."
  "     run tools\frp-start.vbs to bring it back."
  return
}

if (-not (Test-Path $vbs)) { throw "missing $vbs" }
if (-not (Test-Path $conf)) {
  "frp: no frpc.toml yet - registering the task anyway, but nothing will start until"
  "     tools\frp-setup.ps1 has written it (it needs the frps token from your VPS)."
}

# Unquoted path with spaces would break the task action, so quote it explicitly.
$action = New-ScheduledTaskAction -Execute 'wscript.exe' -Argument ('"' + $vbs + '"')
$atLogon = New-ScheduledTaskTrigger -AtLogOn
# -RepetitionDuration is what makes it repeat for years (the default window expires).
$every5 = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) `
  -RepetitionInterval (New-TimeSpan -Minutes 5) -RepetitionDuration (New-TimeSpan -Days 3650)
$settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -StartWhenAvailable `
  -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries

Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $atLogon, $every5 `
  -Settings $settings -Force | Out-Null
Start-ScheduledTask -TaskName $TaskName
Start-Sleep -Milliseconds 1500

"frp: task '$TaskName' registered (at logon + every 5 minutes) and started."
"frpc processes: " + (@(Get-Process frpc -ErrorAction SilentlyContinue).Count)
if (Test-Path $log) { ''; 'last lines of frpc.log:'; Get-Content $log -Tail 6 }