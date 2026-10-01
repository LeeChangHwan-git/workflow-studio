@echo off
chcp 65001 >nul
echo Workflow Studio 서버 시작 중... (http://localhost:8080)
echo 종료하려면 이 창을 닫거나 Ctrl+C 를 누르세요.
gradlew.bat bootRun
pause
