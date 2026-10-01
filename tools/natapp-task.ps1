# Keeps the natapp tunnel alive and starts it at logon, with no console window to close.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\natapp-task.ps1
#       register (or repair) the "DSH natapp Tunnel" task and start the tunnel now
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\natapp-task.ps1 -Off
#       disable the task and stop the tunnel
#
# The task just runs tools\natapp-start.vbs, which is idempotent (it exits if natapp.exe is
# already running), so the 5-minute repeat is a self-heal and never a second tunnel.
[CmdletBinding()]
param([switch]$Off, [string]$TaskName = 'DSH natapp Tunnel')

$ErrorActionPreference = 'Stop'
$vbs = Join-Path $PSScriptRoot 'natapp-start.vbs'
$log = Join-Path $PSScriptRoot 'natapp.log'

function Stop-Tunnel {
  Get-Process natapp -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
}

if ($Off) {
  Disable-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue | Out-Null
  Stop-Tunnel
  "natapp: task '$TaskName' disabled, tunnel stopped."
  "        run tools\natapp-start.vbs to bring it back."
  return
}

if (-not (Test-Path $vbs)) { throw "missing $vbs" }
if (-not (Test-Path (Join-Path $PSScriptRoot '.natapp-token'))) {
  "natapp: no token yet - register the task anyway, but nothing will start until"
  "        tools\.natapp-token holds your Authtoken (see tools\natapp-setup.ps1)."
}

# Unquoted path with spaces would break the task action, so quote it explicitly.
$action = New-ScheduledTaskAction -Execute 'wscript.exe' -Argument ('"' + $vbs + '"')
$atLogon = New-ScheduledTaskTrigger -AtLogOn
# Same self-heal shape as the bridge task: if the tunnel ever dies, it is back within
# 5 minutes. -RepetitionDuration is what makes it repeat for years (the default window
# would expire after 10 minutes).
$every5 = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) `
  -RepetitionInterval (New-TimeSpan -Minutes 5) -RepetitionDuration (New-TimeSpan -Days 3650)
$settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -StartWhenAvailable `
  -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries

Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $atLogon, $every5 `
  -Settings $settings -Force | Out-Null
Start-ScheduledTask -TaskName $TaskName
Start-Sleep -Milliseconds 1500

"natapp: task '$TaskName' registered (at logon + every 5 minutes) and started."
if (Test-Path $log) { ''; 'last lines of natapp.log:'; Get-Content $log -Tail 6 }