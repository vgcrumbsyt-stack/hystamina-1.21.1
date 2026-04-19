@echo off
setlocal

pushd "%~dp0"
call ".\gradlew.bat" build
if errorlevel 1 (
	set "EXIT_CODE=%ERRORLEVEL%"
	popd
	exit /b %EXIT_CODE%
)

start "Alpha" cmd /c "call \"%~dp0run-alpha-client.bat\""
start "Omega" cmd /c "call \"%~dp0run-omega-client.bat\""

popd
exit /b 0