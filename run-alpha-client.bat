@echo off
setlocal

pushd "%~dp0"
call ".\gradlew.bat" runClient -PtestClientName=Alpha -PtestClientGameDir=run\alpha
set "EXIT_CODE=%ERRORLEVEL%"
popd

exit /b %EXIT_CODE%