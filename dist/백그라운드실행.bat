@echo off
chcp 65001 >nul
setlocal
call "%~dp0_env.bat" || (pause & exit /b 1)

rem 콘솔 창 없이(javaw) 백그라운드로 실행. 이미 실행 중이면 브라우저만 열고 끝남.
cd /d "%WORKDIR%"
start "" "%JAVAW%" %JOPTS% -jar "%JAR%"

echo Workflow Studio 를 백그라운드로 시작했습니다 → %APP_URL%
echo  - 창이 없으므로 끌 때는 "서버종료.bat" 을 실행하세요.
echo  - 로그: %WORKDIR%\logs\workflow-studio.log
timeout /t 4 >nul