@echo off
chcp 65001 >nul
setlocal
set "APP_URL=http://localhost:8080"
curl.exe -s -f %APP_URL%/api/app/info 2>nul
if errorlevel 1 (
    echo Workflow Studio 가 실행 중이 아닙니다 ^(%APP_URL%^).
) else (
    echo.
    echo ↑ 실행 중 — pid, 가동 시간^(분^), 메모리^(MB^), 등록된 타이머 수
)
pause