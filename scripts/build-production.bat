@echo off
rem Production claudeproxy build: the dashboard is embedded into the jar.
setlocal
set "PROJECT_ROOT=%~dp0.."
cd /d "%PROJECT_ROOT%"

call "%PROJECT_ROOT%\gradlew.bat" clean buildDashboard bootJar --console=plain
if errorlevel 1 goto :error

echo.
echo [build-production] Done: build\libs\claudeproxy-0.0.1-SNAPSHOT.jar
echo [build-production] Run:    java -jar build\libs\claudeproxy-0.0.1-SNAPSHOT.jar
exit /b 0

:error
echo [build-production] Build failed.
exit /b 1
