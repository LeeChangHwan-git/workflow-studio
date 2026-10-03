# Workflow Studio — 윈도우 로그인 시 자동 실행 등록/해제
# '시작프로그램' 폴더(shell:startup)에 바로가기를 만들거나 지운다. 관리자 권한 불필요, 이 사용자에게만 적용.
param(
    [Parameter(Mandatory = $true)][ValidateSet('register', 'unregister')] [string]$Action,
    [string]$Javaw,
    [string]$Jar,
    [string]$WorkDir,
    [string]$JavaOptions
)

$startup = [Environment]::GetFolderPath('Startup')
$link = Join-Path $startup 'Workflow Studio.lnk'

if ($Action -eq 'unregister') {
    if (Test-Path $link) {
        Remove-Item $link -Force
        Write-Host '자동 시작을 해제했습니다. (지금 실행 중인 서버는 그대로입니다 — 끄려면 서버종료.bat)'
    } else {
        Write-Host '자동 시작이 등록되어 있지 않습니다.'
    }
    return
}

$jarFull = (Resolve-Path $Jar).Path
$workFull = (Resolve-Path $WorkDir).Path
$shell = New-Object -ComObject WScript.Shell
$s = $shell.CreateShortcut($link)
$s.TargetPath = $Javaw
$s.Arguments = "$JavaOptions -jar `"$jarFull`" --app.open-browser=false"
$s.WorkingDirectory = $workFull
$s.WindowStyle = 7
$s.Description = 'Workflow Studio 서버 (로그인 시 창 없이 실행)'
$s.Save()

Write-Host '자동 시작을 등록했습니다. 다음 로그인부터 서버가 창 없이 자동으로 켜집니다 (브라우저는 열지 않음).'
Write-Host "  바로가기: $link"
Write-Host "  jar     : $jarFull"
Write-Host "  데이터  : $workFull\data"
Write-Host '해제하려면 자동시작해제.bat 을 실행하세요.'