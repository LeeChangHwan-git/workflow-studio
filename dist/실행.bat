@echo off
chcp 65001 >nul
cd /d %~dp0
title Workflow Studio 서버
echo Workflow Studio 서버 시작 중... (http://localhost:8080)
echo 종료하려면 이 창을 닫거나 Ctrl+C 를 누르세요.
echo.
java -Djava.awt.headless=false -jar workflow-studio-1.0.0.jar
pause
