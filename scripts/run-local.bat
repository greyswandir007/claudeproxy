@echo off
rem Локальный запуск claudeproxy: сборка дашборда (если есть Node.js) и bootRun.
setlocal
set "PROJECT_ROOT=%~dp0.."
cd /d "%PROJECT_ROOT%"

where npm >nul 2>nul
if %errorlevel%==0 (
    echo [run-local] Сборка дашборда...
    call "%PROJECT_ROOT%\gradlew.bat" buildDashboard --console=plain
    if errorlevel 1 goto :error
) else (
    echo [run-local] npm не найден — дашборд не пересобирается ^(если web/dist существует, он будет роздан^).
)

echo [run-local] Запуск бэкенда: http://127.0.0.1:8080
call "%PROJECT_ROOT%\gradlew.bat" bootRun --console=plain
exit /b %errorlevel%

:error
echo [run-local] Ошибка сборки дашборда.
exit /b 1
