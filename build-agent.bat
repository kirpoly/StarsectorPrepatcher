@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-agent.ps1"
exit /b %ERRORLEVEL%
