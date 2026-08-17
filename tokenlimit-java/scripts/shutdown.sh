#!/bin/bash

###############################################################################
# TokenLimit 停止脚本 (Linux/Mac)
###############################################################################

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_HOME="$(dirname "$SCRIPT_DIR")"
PID_FILE="$PROJECT_HOME/tokenlimit-server.pid"

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

# 优雅关闭服务
stop_server() {
    if [ ! -f "$PID_FILE" ]; then
        echo_warn "未找到 PID 文件，尝试通过进程名查找..."
        
        PID=$(ps aux | grep "tokenlimit-server" | grep -v grep | awk '{print $2}')
        if [ -z "$PID" ]; then
            echo_info "TokenLimit 未运行"
            exit 0
        fi
        
        echo_info "找到 TokenLimit 进程 (PID: $PID)"
    else
        PID=$(cat "$PID_FILE")
    fi
    
    if ! ps -p "$PID" > /dev/null 2>&1; then
        echo_warn "TokenLimit 进程 (PID: $PID) 不存在"
        rm -f "$PID_FILE"
        exit 0
    fi
    
    echo_info "正在停止 TokenLimit (PID: $PID)..."
    
    # 发送 SIGTERM 信号，优雅关闭
    kill -15 "$PID"
    
    # 等待进程结束
    for i in {1..30}; do
        if ! ps -p "$PID" > /dev/null 2>&1; then
            echo_info "✅ TokenLimit 已成功停止"
            rm -f "$PID_FILE"
            return 0
        fi
        sleep 1
    done
    
    echo_warn "优雅关闭超时，强制终止进程..."
    kill -9 "$PID"
    rm -f "$PID_FILE"
    echo_info "✅ TokenLimit 已强制停止"
}

# 主函数
main() {
    echo ""
    echo_info "🛑 TokenLimit 停止脚本"
    echo ""
    
    stop_server
}

main "$@"
