@echo off
cd /d %~dp0
echo [ 현재 설정 ]
findstr "app.storage.path" application.properties
echo.
echo 저장 경로를 변경합니다.
echo (엔터만 누르면 취소)
echo.
set /p NEW_PATH="새 저장 경로 입력 (예: D:\MyData 또는 ./data): "
if "%NEW_PATH%"=="" (
    echo 취소되었습니다.
    pause
    exit /b
)
powershell -Command "(Get-Content application.properties) -replace 'app.storage.path=.*', 'app.storage.path=%NEW_PATH%' | Set-Content application.properties"
echo.
echo 저장 경로가 변경되었습니다: %NEW_PATH%
echo 앱을 재시작하면 적용됩니다.
pause
