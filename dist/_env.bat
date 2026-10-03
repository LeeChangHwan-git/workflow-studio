@echo off
rem ── 공통 설정: 다른 bat 파일이 call 로 불러 씀 ──────────────────────
rem  JAR     : 실행할 jar (배포 폴더면 같은 폴더, 저장소면 ..\build\libs)
rem  WORKDIR : 실행 폴더 (data, logs, application.properties 가 생기는 곳)
rem  JAVA / JAVAW : Java 21 (JAVA_HOME → %USERPROFILE%\.jdks\*21* → PATH 순서로 찾음)
rem  JOPTS   : 항상 켜 두기용 JVM 옵션 (메모리 상한 384MB, 가벼운 GC/JIT)

set "HERE=%~dp0"
if exist "%HERE%workflow-studio-1.0.0.jar" (
    set "JAR=%HERE%workflow-studio-1.0.0.jar"
    set "WORKDIR=%HERE%"
) else (
    set "JAR=%HERE%..\build\libs\workflow-studio-1.0.0.jar"
    set "WORKDIR=%HERE%.."
)
if not exist "%JAR%" (
    echo [오류] jar 파일이 없습니다: %JAR%
    echo        저장소에서는 먼저 gradlew.bat bootJar 로 빌드하세요.
    exit /b 1
)

set "JAVA="
set "JAVAW="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javaw.exe" (
    set "JAVA=%JAVA_HOME%\bin\java.exe"
    set "JAVAW=%JAVA_HOME%\bin\javaw.exe"
)
if not defined JAVAW for /d %%D in ("%USERPROFILE%\.jdks\*21*") do if exist "%%D\bin\javaw.exe" (
    set "JAVA=%%D\bin\java.exe"
    set "JAVAW=%%D\bin\javaw.exe"
)
if not defined JAVAW for %%J in (javaw.exe) do if not "%%~$PATH:J"=="" (
    set "JAVAW=%%~$PATH:J"
    set "JAVA=%%~dp$PATH:Jjava.exe"
)
if not defined JAVAW (
    echo [오류] Java 21 을 찾을 수 없습니다. JDK 21 을 설치하거나 JAVA_HOME 을 설정하세요.
    exit /b 1
)

set "JOPTS=-Xms64m -Xmx384m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Djava.awt.headless=false"
set "APP_URL=http://localhost:8080"
exit /b 0