@echo off
rem Local claudeproxy launch: build the dashboard (if Node.js is available) and run bootRun.
setlocal
set "PROJECT_ROOT=%~dp0.."
cd /d "%PROJECT_ROOT%"

where npm >nul 2>nul
if %errorlevel%==0 (
    echo [run-local] Building dashboard...
    call "%PROJECT_ROOT%\gradlew.bat" buildDashboard --console=plain
    if errorlevel 1 goto :error
) else (
    echo [run-local] npm not found - dashboard is not rebuilt ^(if web/dist exists, it will be served as is^).
)

echo [run-local] Starting backend: http://127.0.0.1:8080
call "%PROJECT_ROOT%\gradlew.bat" bootRun --console=plain
exit /b %errorlevel%

:error
echo [run-local] Dashboard build failed.
exit /b 1
