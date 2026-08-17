#!/bin/bash

###############################################################################
# TokenLimit 启动脚本 (Linux/Mac)
# 支持单机模式（内置 Derby + 内嵌 Redis）和生产模式（MySQL + 外置 Redis）
###############################################################################

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_HOME="$(dirname "$SCRIPT_DIR")"
SERVER_JAR="$PROJECT_HOME/tokenlimit-server/target/tokenlimit-server-5.5.0.jar"
PID_FILE="$PROJECT_HOME/tokenlimit-server.pid"
LOG_DIR="$PROJECT_HOME/logs"
LOG_FILE="$LOG_DIR/tokenlimit.log"

# 默认配置
export TL_DB_MODE="${TL_DB_MODE:-standalone}"
export TL_REDIS_MODE="${TL_REDIS_MODE:-embedded}"
export JAVA_OPTS="${JAVA_OPTS:--Xms512m -Xmx1g -XX:+UseG1GC}"
export SERVER_PORT="${SERVER_PORT:-8080}"

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

echo_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

echo_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 检查 Java 环境
check_java() {
    if ! command -v java &> /dev/null; then
        echo_error "Java 未安装，请先安装 JDK 17+"
        exit 1
    fi
    
    JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
    if [ "$JAVA_VERSION" -lt 17 ]; then
        echo_error "需要 JDK 17+，当前版本: $JAVA_VERSION"
        exit 1
    fi
    
    echo_info "Java 版本检查通过: $JAVA_VERSION"
}

# 检查 JAR 文件
check_jar() {
    if [ ! -f "$SERVER_JAR" ]; then
        echo_error "未找到服务器 JAR 文件: $SERVER_JAR"
        echo_warn "请先执行 mvn clean package -DskipTests 构建项目"
        exit 1
    fi
    echo_info "JAR 文件检查通过: $SERVER_JAR"
}

# 创建日志目录
setup_logs() {
    mkdir -p "$LOG_DIR"
    echo_info "日志目录: $LOG_DIR"
}

# 显示配置信息
show_config() {
    echo ""
    echo_info "========== TokenLimit 启动配置 =========="
    echo_info "数据库模式：$TL_DB_MODE"
    echo_info "Redis 模式：$TL_REDIS_MODE"
    echo_info "服务端口：$SERVER_PORT"
    echo_info "Java 选项：$JAVA_OPTS"
    
    if [ "$TL_DB_MODE" = "mysql" ]; then
        echo_info "MySQL 主机：${TL_DB_HOST:-localhost}:${TL_DB_PORT:-3306}"
        echo_info "MySQL 数据库：${TL_DB_NAME:-tokenlimit}"
    else
        echo_info "Derby 数据目录：$PROJECT_HOME/data/derby-data"
    fi
    
    if [ "$TL_REDIS_MODE" = "external" ]; then
        echo_info "Redis 主机：${TL_REDIS_HOST:-localhost}:${TL_REDIS_PORT:-6379}"
    else
        echo_info "内嵌 Redis 端口：6379"
    fi
    echo_info "========================================="
    echo ""
}

# 检查是否已运行
check_running() {
    if [ -f "$PID_FILE" ]; then
        PID=$(cat "$PID_FILE")
        if ps -p "$PID" > /dev/null 2>&1; then
            echo_warn "TokenLimit 已在运行 (PID: $PID)"
            echo_warn "请先执行 ./shutdown.sh 停止服务"
            exit 1
        else
            echo_warn "发现无效的 PID 文件，将删除并重新启动"
            rm -f "$PID_FILE"
        fi
    fi
}

# 启动服务
start_server() {
    echo_info "正在启动 TokenLimit..."
    
    cd "$PROJECT_HOME"
    
    nohup java $JAVA_OPTS \
        -Dserver.port=$SERVER_PORT \
        -Dspring.profiles.active=$TL_DB_MODE,$TL_REDIS_MODE \
        -jar "$SERVER_JAR" \
        > "$LOG_FILE" 2>&1 &
    
    PID=$!
    echo $PID > "$PID_FILE"
    
    echo_info "服务已启动 (PID: $PID)"
    echo_info "日志文件：$LOG_FILE"
    
    # 等待服务启动
    echo_info "等待服务启动..."
    for i in {1..30}; do
        if curl -s http://localhost:$SERVER_PORT/health > /dev/null 2>&1; then
            echo_info ""
            echo_info "✅ TokenLimit 启动成功!"
            echo_info ""
            echo_info "访问地址:"
            echo_info "  - 健康检查：http://localhost:$SERVER_PORT/health"
            echo_info "  - API 文档：http://localhost:$SERVER_PORT/swagger-ui.html"
            echo_info "  - 管理控制台：http://localhost:$SERVER_PORT/console/"
            echo_info ""
            return 0
        fi
        sleep 1
    done
    
    echo_warn "服务已启动但健康检查未通过，请查看日志：$LOG_FILE"
}

# 主函数
main() {
    echo ""
    echo_info "🚀 TokenLimit 启动脚本"
    echo ""
    
    check_java
    check_jar
    setup_logs
    show_config
    check_running
    start_server
}

main "$@"
