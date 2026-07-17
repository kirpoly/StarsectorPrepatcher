@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0uninstall-agent.ps1" %*
set EXITCODE=%ERRORLEVEL%
echo.
if not "%EXITCODE%"=="0" echo Uninstall failed with exit code %EXITCODE%.
pause
exit /b %EXITCODE%
