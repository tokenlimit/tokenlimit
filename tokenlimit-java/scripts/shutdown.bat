@echo off
REM ###############################################################################
REM TokenLimit 停止脚本 (Windows)
REM ###############################################################################

setlocal enabledelayedexpansion

set SCRIPT_DIR=%~dp0
set PROJECT_HOME=%SCRIPT_DIR:~0,-1%
set PID_FILE=%PROJECT_HOME%\tokenlimit-server.pid

echo.
echo [INFO] TokenLimit 停止脚本 (Windows)
echo.

REM 检查 PID 文件
if not exist "%PID_FILE%" (
    echo [WARN] 未找到 PID 文件，尝试通过进程名查找...
    
    for /f "tokens=2" %%i in ('tasklist /FI "IMAGENAME eq java.exe" ^| findstr "tokenlimit-server"') do (
        set PID=%%i
        goto :found
    )
    
    echo [INFO] TokenLimit 未运行
    pause
    exit /b 0
    
    :found
    echo [INFO] 找到 TokenLimit 进程 ^(PID: !PID!^)
) else (
    set /p PID=<"%PID_FILE%"
)

REM 检查进程是否存在
tasklist /FI "PID eq %PID%" 2>nul | find "%PID%" >nul
if errorlevel 1 (
    echo [WARN] TokenLimit 进程 ^(PID: %PID%^) 不存在
    del /f "%PID_FILE%" 2>nul
    pause
    exit /b 0
)

echo [INFO] 正在停止 TokenLimit ^(PID: %PID%^)...

REM 使用 taskkill 优雅关闭
taskkill /PID %PID% /T /F >nul 2>&1

if not errorlevel 1 (
    echo [INFO] TokenLimit 已成功停止
    del /f "%PID_FILE%"
) else (
    echo [ERROR] 停止失败，请手动检查进程
)

pause
