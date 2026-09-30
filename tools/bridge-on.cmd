@echo off
rem Phone cannot reach this PC any more? Double-click this file.
rem It registers the hidden self-healing launcher (at logon + every 5 minutes) and
rem starts the bridge right now. Running it again is harmless.
rem Full manual: ..\操作手册.md
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0bridge-task.ps1"
if errorlevel 1 pause