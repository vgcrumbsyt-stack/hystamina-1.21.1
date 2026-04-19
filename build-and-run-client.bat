@echo off
setlocal

pushd "%~dp0"
call ".\gradlew.bat" build
if errorlevel 1 (
	set "EXIT_CODE=%ERRORLEVEL%"
	popd
	exit /b %EXIT_CODE%
)

call ".\gradlew.bat" runClient
set "EXIT_CODE=%ERRORLEVEL%"
popd

exit /b %EXIT_CODE%