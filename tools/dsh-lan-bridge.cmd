@echo off
rem Durable launcher for the LAN bridge: run this (or its Startup shortcut) when
rem you want the phone to reach this PC's DSH. It is independent of the DSH app
rem and of any agent session, so it survives both until you stop it yourself.
rem Stop it with:  taskkill /FI "WINDOWTITLE eq dsh-lan-bridge*"
setlocal
set "NODE=C:\Program Files\nodejs\node.exe"
if not exist "%NODE%" set "NODE=node"
set "SCRIPT=%~dp0dsh-lan-bridge.cjs"
set "LOG=%~dp0bridge.log"
rem Log only what node reports: it timestamps its own "listening" line and stays quiet
rem when the port is already served (which is the normal case for the 5-minute probe).
"%NODE%" "%SCRIPT%" %* >>"%LOG%" 2>&1
