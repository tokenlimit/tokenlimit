@echo off
REM ###############################################################################
REM TokenLimit 启动脚本 (Windows)
REM 支持单机模式（内置 Derby + 内嵌 Redis）和生产模式（MySQL + 外置 Redis）
REM ###############################################################################

setlocal enabledelayedexpansion

set SCRIPT_DIR=%~dp0
set PROJECT_HOME=%SCRIPT_DIR:~0,-1%
set SERVER_JAR=%PROJECT_HOME%\tokenlimit-server\target\tokenlimit-server-5.5.0.jar
set PID_FILE=%PROJECT_HOME%\tokenlimit-server.pid
set LOG_DIR=%PROJECT_HOME%\logs
set LOG_FILE=%LOG_DIR%\tokenlimit.log

REM 默认配置
if not defined TL_DB_MODE set TL_DB_MODE=standalone
if not defined TL_REDIS_MODE set TL_REDIS_MODE=embedded
if not defined JAVA_OPTS set JAVA_OPTS=-Xms512m -Xmx1g -XX:+UseG1GC
if not defined SERVER_PORT set SERVER_PORT=8080

echo.
echo [INFO] TokenLimit 启动脚本 (Windows)
echo.

REM 检查 Java 环境
where java >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Java 未安装，请先安装 JDK 17+
    pause
    exit /b 1
)

for /f "tokens=3" %%i in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    set JAVA_VERSION=%%i
)
set JAVA_VERSION=%JAVA_VERSION:"=%
for /f "delims=. tokens=1" %%i in ("%JAVA_VERSION%") do set JAVA_VERSION_MAJOR=%%i

if %JAVA_VERSION_MAJOR% lss 17 (
    echo [ERROR] 需要 JDK 17+，当前版本：%JAVA_VERSION%
    pause
    exit /b 1
)

echo [INFO] Java 版本检查通过：%JAVA_VERSION%

REM 检查 JAR 文件
if not exist "%SERVER_JAR%" (
    echo [ERROR] 未找到服务器 JAR 文件：%SERVER_JAR%
    echo [WARN] 请先执行 mvn clean package -DskipTests 构建项目
    pause
    exit /b 1
)

echo [INFO] JAR 文件检查通过：%SERVER_JAR%

REM 创建日志目录
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"
echo [INFO] 日志目录：%LOG_DIR%

REM 显示配置信息
echo.
echo [INFO] ========== TokenLimit 启动配置 ==========
echo [INFO] 数据库模式：%TL_DB_MODE%
echo [INFO] Redis 模式：%TL_REDIS_MODE%
echo [INFO] 服务端口：%SERVER_PORT%
echo [INFO] Java 选项：%JAVA_OPTS%

if "%TL_DB_MODE%"=="mysql" (
    echo [INFO] MySQL 主机：%TL_DB_HOST%:%TL_DB_PORT%
    echo [INFO] MySQL 数据库：%TL_DB_NAME%
) else (
    echo [INFO] Derby 数据目录：%PROJECT_HOME%\data\derby-data
)

if "%TL_REDIS_MODE%"=="external" (
    echo [INFO] Redis 主机：%TL_REDIS_HOST%:%TL_REDIS_PORT%
) else (
    echo [INFO] 内嵌 Redis 端口：6379
)
echo [INFO] =========================================
echo.

REM 检查是否已运行
if exist "%PID_FILE%" (
    set /p PID=<"%PID_FILE%"
    tasklist /FI "PID eq %PID%" 2>nul | find "%PID%" >nul
    if not errorlevel 1 (
        echo [WARN] TokenLimit 已在运行 ^(PID: %PID%^)
        echo [WARN] 请先执行 shutdown.bat 停止服务
        pause
        exit /b 1
    ) else (
        echo [WARN] 发现无效的 PID 文件，将删除并重新启动
        del /f "%PID_FILE%"
    )
)

REM 启动服务
echo [INFO] 正在启动 TokenLimit...

cd /d "%PROJECT_HOME%"

start /B java %JAVA_OPTS% ^
    -Dserver.port=%SERVER_PORT% ^
    -Dspring.profiles.active=%TL_DB_MODE%,%TL_REDIS_MODE% ^
    -jar "%SERVER_JAR%"

set PID=!errorlevel!
if !PID! equ 0 (
    for /f "tokens=2" %%i in ('tasklist /FI "IMAGENAME eq java.exe" /FO CSV ^| find /C "java.exe"') do set PID=%%i
    echo !PID! > "%PID_FILE%"
    
    echo [INFO] 服务已启动 ^(PID: !PID!^)
    echo [INFO] 日志文件：%LOG_FILE%
    
    echo.
    echo [INFO] 等待服务启动...
    timeout /t 10 /nobreak >nul
    
    echo [INFO] 
    echo [INFO] TokenLimit 启动成功!
    echo [INFO] 
    echo [INFO] 访问地址:
    echo [INFO]   - 健康检查：http://localhost:%SERVER_PORT%/health
    echo [INFO]   - API 文档：http://localhost:%SERVER_PORT%/swagger-ui.html
    echo [INFO]   - 管理控制台：http://localhost:%SERVER_PORT%/console/
    echo [INFO] 
) else (
    echo [ERROR] 启动失败，请查看日志：%LOG_FILE%
)

pause
