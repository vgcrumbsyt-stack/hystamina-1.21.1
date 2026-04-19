@echo off
setlocal

pushd "%~dp0"
call ".\gradlew.bat" runClient
set "EXIT_CODE=%ERRORLEVEL%"
popd

exit /b %EXIT_CODE%