@echo off
chcp 65001 >nul
setlocal
call "%~dp0_env.bat" || (pause & exit /b 1)
title Workflow Studio 서버
cd /d "%WORKDIR%"
echo Workflow Studio 서버 시작 중... (%APP_URL%)
echo 종료하려면 이 창을 닫거나 Ctrl+C 를 누르세요.  (창 없이 켜 두려면 "백그라운드실행.bat")
echo.
"%JAVA%" %JOPTS% -jar "%JAR%"
pause