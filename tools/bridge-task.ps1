# Keeps the phone's gateway to this PC's DSH alive, with no console window to close.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\bridge-task.ps1
#       register (or repair) the "DSH LAN Bridge" task and start the bridge now
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\bridge-task.ps1 -Off
#       disable the task and stop the running bridge
#
# bridge-on.cmd / bridge-off.cmd are thin double-clickable wrappers around the two.
[CmdletBinding()]
param([switch]$Off, [int]$Port = 3080, [string]$TaskName = 'DSH LAN Bridge')

$ErrorActionPreference = 'Stop'
$vbs = Join-Path $PSScriptRoot 'bridge-start.vbs'
$log = Join-Path $PSScriptRoot 'bridge.log'

function Stop-Bridge {
  Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty OwningProcess -Unique |
    ForEach-Object { Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue }
}

if ($Off) {
  Disable-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue | Out-Null
  Stop-Bridge
  "bridge: task '$TaskName' disabled, bridge on port $Port stopped."
  "        run bridge-on.cmd to bring it back."
  return
}

# Unquoted path with spaces would break the task action, so quote it explicitly.
$action = New-ScheduledTaskAction -Execute 'wscript.exe' -Argument ('"' + $vbs + '"')
$atLogon = New-ScheduledTaskTrigger -AtLogOn
# The repeating trigger is the self-heal: if the bridge ever dies, it is back within
# 5 minutes. Re-running dsh-lan-bridge.cjs while the port is taken is a no-op.
# -RepetitionDuration is what makes it repeat for years: PowerShell would otherwise
# default the repetition window to 10 minutes and the self-heal would quietly expire.
$every5 = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) `
  -RepetitionInterval (New-TimeSpan -Minutes 5) -RepetitionDuration (New-TimeSpan -Days 3650)
$settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -StartWhenAvailable `
  -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries

Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $atLogon, $every5 `
  -Settings $settings -Force | Out-Null
Start-ScheduledTask -TaskName $TaskName
Start-Sleep -Milliseconds 1500

"bridge: task '$TaskName' registered (at logon + every 5 minutes) and started."
"        it lives in the background: no window, nothing to close."
if (Test-Path $log) { ''; 'last lines of bridge.log:'; Get-Content $log -Tail 6 }