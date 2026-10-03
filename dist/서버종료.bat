@echo off
chcp 65001 >nul
setlocal
set "APP_URL=http://localhost:8080"
curl.exe -s -f -X POST -H "X-Workflow-Studio-Shutdown: yes" %APP_URL%/api/app/shutdown >nul 2>nul
if errorlevel 1 (
    echo 실행 중인 Workflow Studio 가 없습니다 ^(%APP_URL%^).
) else (
    echo Workflow Studio 서버를 종료했습니다.
)
timeout /t 3 >nul