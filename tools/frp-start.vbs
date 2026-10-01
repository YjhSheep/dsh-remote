' Start the frp client fully detached: no console, no window, no inherited pipe.
' The token lives in tools\frp\frpc.toml (created by tools\frp-setup.ps1), so this file
' never contains the secret. Idempotent on purpose: the logon task repeats every 5
' minutes as a self-heal, and a second frpc would fight the first one for the tunnel.
Option Explicit
Dim fso, sh, root, wmi, running
Set fso = CreateObject("Scripting.FileSystemObject")
Set sh  = CreateObject("WScript.Shell")
root = fso.GetParentFolderName(WScript.ScriptFullName)
Set wmi = GetObject("winmgmts:\\.\root\cimv2")
Set running = wmi.ExecQuery("SELECT ProcessId FROM Win32_Process WHERE Name='frpc.exe'")
If running.Count > 0 Then WScript.Quit 0
If Not fso.FileExists(root & "\frp\frpc.exe") Then WScript.Quit 2
If Not fso.FileExists(root & "\frp\frpc.toml") Then WScript.Quit 3
sh.Run """" & root & "\frp-run.cmd""", 0, False