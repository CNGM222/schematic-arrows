@echo off
rem Build helper for Schematic Arrows.
rem Uses a local Gradle distribution when present (..\tools\gradle-9.9.2),
rem otherwise falls back to "gradle" from PATH.
setlocal
set "PROJECT=%~dp0."
if not defined JAVA_HOME if exist "C:\Program Files\Java\jdk-21" set "JAVA_HOME=C:\Program Files\Java\jdk-21"
if not defined GRADLE_USER_HOME if exist "%~dp0..\.gradle-user-home" set "GRADLE_USER_HOME=%~dp0..\.gradle-user-home"
set "LOCAL_GRADLE=%~dp0..\tools\gradle-9.9.2\bin\gradle.bat"
if exist "%LOCAL_GRADLE%" (
  set "GRADLE_BIN=%LOCAL_GRADLE%"
) else (
  set "GRADLE_BIN=gradle"
)
call "%GRADLE_BIN%" -p "%PROJECT%" --no-daemon --console=plain %*
exit /b %ERRORLEVEL%
