' Start the natapp tunnel fully detached: no console, no window, no inherited pipe.
' The token is read from tools\.natapp-token and handed to natapp-run.cmd as an argument,
' so this file never contains the secret. Run it again to restart (a second natapp would
' fail to bind its local ports, so kill the old one first - tools\natapp-setup.ps1 -Stop).
Option Explicit
Dim fso, sh, root, token, f, wmi, running
Set fso = CreateObject("Scripting.FileSystemObject")
Set sh  = CreateObject("WScript.Shell")
root = fso.GetParentFolderName(WScript.ScriptFullName)
' Idempotent: the logon task repeats every 5 minutes as a self-heal, and a second natapp
' would only fight the first one for its local ports. Already running -> do nothing.
Set wmi = GetObject("winmgmts:\\.\root\cimv2")
Set running = wmi.ExecQuery("SELECT ProcessId FROM Win32_Process WHERE Name='natapp.exe'")
If running.Count > 0 Then WScript.Quit 0
If Not fso.FileExists(root & "\.natapp-token") Then WScript.Quit 2
Set f = fso.OpenTextFile(root & "\.natapp-token", 1)
token = Trim(f.ReadAll)
f.Close
If Len(token) < 8 Then WScript.Quit 3
sh.Run """" & root & "\natapp-run.cmd"" " & token, 0, False