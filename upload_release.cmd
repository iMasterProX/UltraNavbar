@echo off
setlocal
set script=%~dp0upload_release.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File "%script%" %*
