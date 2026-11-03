@echo off
setlocal
cd /d "%~dp0"
if not exist out\egoncode.jar (
  powershell -NoProfile -ExecutionPolicy Bypass -File build.ps1
  if errorlevel 1 exit /b 1
)
java -jar out\egoncode.jar %*
