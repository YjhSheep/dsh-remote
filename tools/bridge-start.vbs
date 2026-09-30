' Starts the LAN bridge with NO console window and does not wait for it.
' Run by the "DSH LAN Bridge" scheduled task (at logon + every 5 minutes), so the
' phone's gateway to this PC's DSH keeps running on its own and there is no window
' left for anyone to close by accident.
Option Explicit
Dim fso, sh, cmd
Set fso = CreateObject("Scripting.FileSystemObject")
Set sh = CreateObject("WScript.Shell")
cmd = fso.GetParentFolderName(WScript.ScriptFullName) & "\dsh-lan-bridge.cmd"
If Not fso.FileExists(cmd) Then WScript.Quit 1
' 0 = hidden window, False = fire and forget (the bridge outlives this launcher).
sh.Run "cmd.exe /c """ & cmd & """", 0, False