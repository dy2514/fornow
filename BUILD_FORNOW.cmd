@echo off
cd /d "%~dp0"
call gradlew.bat :fornow:assembleDebug :fornow-core:test :fornow:testDebugUnitTest :fornow:lintDebug
set "APP_EXIT=%ERRORLEVEL%"
pause
exit /b %APP_EXIT%
