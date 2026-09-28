@echo off
rem Build boot jar (with dashboard when Node.js is available) and docker image claudeproxy:local.
setlocal
cd /d "%~dp0.."

call gradlew.bat test || goto :error
call gradlew.bat bootJar || goto :error

if not exist "app\build\libs\claudeproxy-0.0.1-SNAPSHOT.jar" goto :error

docker build -t claudeproxy:local . || goto :error

echo Done: docker image claudeproxy:local
goto :eof

:error
echo Docker build failed 1>&2
exit /b 1
