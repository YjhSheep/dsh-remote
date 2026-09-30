@echo off
rem Stops the phone's gateway to this PC's DSH and turns off its auto-start.
rem Bring it back any time with bridge-on.cmd.
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0bridge-task.ps1" -Off
if errorlevel 1 pause