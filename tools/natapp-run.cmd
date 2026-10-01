@echo off
rem Launched by natapp-start.vbs (and by the logon task). Token arrives as %1 so it never
rem appears in this file. stdout/stderr are appended to tools\natapp.log / tools\natapp.err.log
rem because the client prints the public url only with -log=stdout.
"%~dp0natapp\natapp.exe" -authtoken=%1 -log=stdout >> "%~dp0natapp.log" 2>> "%~dp0natapp.err.log"