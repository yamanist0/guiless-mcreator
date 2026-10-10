@echo off
setlocal
echo Starting the ModStudio Minecraft backend from the managed runtime...
set "RUNTIME=%APPDATA%\.modStudioAI\minecraft\backend"
if not exist "%RUNTIME%\jdk\bin\modStudioMC.exe" (
  echo Managed runtime not found. Run build.bat first.
  exit /b 1
)
set "JAVA_HOME=%RUNTIME%\jdk"
setlocal enabledelayedexpansion
set "CP="
for %%f in ("%RUNTIME%\lib\*.jar") do set "CP=!CP!%%f;"
"%RUNTIME%\jdk\bin\modStudioMC.exe" --add-opens java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -cp "!CP!" net.mcreator.Launcher %*
