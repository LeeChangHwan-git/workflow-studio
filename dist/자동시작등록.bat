@echo off
chcp 65001 >nul
setlocal
call "%~dp0_env.bat" || (pause & exit /b 1)

rem 윈도우 '시작프로그램' 폴더에 바로가기를 만들어, 로그인할 때 서버를 창 없이 자동 실행 (브라우저는 열지 않음)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0autostart.ps1" -Action register -Javaw "%JAVAW%" -Jar "%JAR%" -WorkDir "%WORKDIR%" -JavaOptions "%JOPTS%"
pause