@echo off
setlocal
echo Setting JAVA_HOME to the bundled JetBrains Runtime...
set "JAVA_HOME=%~dp0jdk\jbr25_win_64"
if not exist "%JAVA_HOME%\bin\java.exe" (
  echo Downloading required JetBrains Runtime JDK ^(initialSetup^)...
  call gradlew.bat initialSetup || goto :error
)
echo Building headless backend and installing it to %%APPDATA%%\.modStudioAI\minecraft\backend ...
call gradlew.bat installModStudioRuntime || goto :error
echo Build process finished.
goto :eof

:error
echo Build failed with exit code %errorlevel%.
exit /b %errorlevel%
