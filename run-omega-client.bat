@echo off
setlocal

pushd "%~dp0"
call ".\gradlew.bat" runClient -PtestClientName=Omega -PtestClientGameDir=run\omega
set "EXIT_CODE=%ERRORLEVEL%"
popd

exit /b %EXIT_CODE%