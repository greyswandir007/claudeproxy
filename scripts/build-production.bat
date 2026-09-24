@echo off
rem Production-сборка claudeproxy: дашборд встраивается в jar.
setlocal
set "PROJECT_ROOT=%~dp0.."
cd /d "%PROJECT_ROOT%"

call "%PROJECT_ROOT%\gradlew.bat" clean buildDashboard bootJar --console=plain
if errorlevel 1 goto :error

echo.
echo [build-production] Готово: build\libs\claudeproxy-0.0.1-SNAPSHOT.jar
echo [build-production] Запуск:   java -jar build\libs\claudeproxy-0.0.1-SNAPSHOT.jar
exit /b 0

:error
echo [build-production] Сборка не удалась.
exit /b 1
