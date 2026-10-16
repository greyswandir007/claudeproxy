@echo off
rem Local claudeproxy build: assemble build\local\claudeproxy.jar with local
rem defaults baked in (server port 9090, sqlite path relative to the launch dir).
rem Run the jar from any directory - the database is created next to it, so
rem keeping it outside the repository keeps the project clean.
setlocal
set "PROJECT_ROOT=%~dp0.."
cd /d "%PROJECT_ROOT%"

where npm >nul 2>nul
if %errorlevel%==0 (
    echo [build-local] Building dashboard...
    call "%PROJECT_ROOT%\gradlew.bat" buildDashboard --console=plain
    if errorlevel 1 goto :error
) else (
    echo [build-local] npm not found - dashboard is not rebuilt ^(if web/dist exists, it will be served as is^).
)

call "%PROJECT_ROOT%\gradlew.bat" bootJar --console=plain
if errorlevel 1 goto :error

set "RUN_DIR=%PROJECT_ROOT%\build\local"
set "STAGING=%PROJECT_ROOT%\build\local\jar-staging"
if not exist "%RUN_DIR%" mkdir "%RUN_DIR%"
if exist "%STAGING%" rmdir /s /q "%STAGING%"
mkdir "%STAGING%\BOOT-INF\classes"

rem Baked-in local defaults: source application.yml + local document -> jar entry.
copy /b "%PROJECT_ROOT%\app\src\main\resources\application.yml" ^
      + "%PROJECT_ROOT%\scripts\local-defaults.yml" ^
      "%STAGING%\BOOT-INF\classes\application.yml" >nul
if errorlevel 1 goto :error

copy /y "%PROJECT_ROOT%\app\build\libs\claudeproxy-0.1.0.jar" "%RUN_DIR%\claudeproxy.jar" >nul
if errorlevel 1 goto :error

cd /d "%STAGING%"
jar uf "%RUN_DIR%\claudeproxy.jar" BOOT-INF/classes/application.yml
if errorlevel 1 (
    cd /d "%PROJECT_ROOT%"
    goto :error
)
cd /d "%PROJECT_ROOT%"
rmdir /s /q "%STAGING%"

echo.
echo [build-local] Done: build\local\claudeproxy.jar - server port 9090 is baked in
echo [build-local] Run from any directory: java -jar claudeproxy.jar
exit /b 0

:error
echo [build-local] Build failed.
exit /b 1
