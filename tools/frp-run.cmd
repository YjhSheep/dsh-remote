@echo off
rem Launched by frp-start.vbs (and by the logon task). No secret in this file: the token
rem lives in tools\frp\frpc.toml, which is not tracked by git.
rem stdout/stderr are appended to tools\frpc.log / tools\frpc.err.log.
"%~dp0frp\frpc.exe" -c "%~dp0frp\frpc.toml" >> "%~dp0frpc.log" 2>> "%~dp0frpc.err.log"