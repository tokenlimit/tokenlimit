# TokenLimit 运维脚本说明

本目录包含 TokenLimit 服务的启动和停止脚本，支持 Linux/Mac 和 Windows 平台。

## 📁 脚本列表

| 脚本文件 | 平台 | 功能 |
|---------|------|------|
| `startup.sh` | Linux/Mac | 启动服务 |
| `shutdown.sh` | Linux/Mac | 停止服务 |
| `startup.bat` | Windows | 启动服务 |
| `shutdown.bat` | Windows | 停止服务 |

## 🚀 快速开始

### 单机模式（零依赖）

**Linux/Mac:**
```bash
./startup.sh
```

**Windows:**
```cmd
startup.bat
```

### 生产模式（MySQL + Redis）

**Linux/Mac:**
```bash
export TL_DB_MODE=mysql
export TL_DB_HOST=localhost
export TL_DB_PORT=3306
export TL_DB_NAME=tokenlimit
export TL_DB_USER=root
export TL_DB_PASSWORD=your_password

export TL_REDIS_MODE=external
export TL_REDIS_HOST=localhost
export TL_REDIS_PORT=6379

./startup.sh
```

**Windows:**
```cmd
set TL_DB_MODE=mysql
set TL_DB_HOST=localhost
set TL_DB_PORT=3306
set TL_DB_NAME=tokenlimit
set TL_DB_USER=root
set TL_DB_PASSWORD=your_password

set TL_REDIS_MODE=external
set TL_REDIS_HOST=localhost
set TL_REDIS_PORT=6379

startup.bat
```

## ⚙️ 环境变量配置

### 数据库配置

| 变量名 | 默认值 | 说明 |
|--------|--------|------|
| `TL_DB_MODE` | `standalone` | 数据库模式：`standalone`(内置 Derby) / `mysql`(外置 MySQL) |
| `TL_DB_HOST` | `localhost` | MySQL 主机地址 |
| `TL_DB_PORT` | `3306` | MySQL 端口 |
| `TL_DB_NAME` | `tokenlimit` | MySQL 数据库名 |
| `TL_DB_USER` | `root` | MySQL 用户名 |
| `TL_DB_PASSWORD` | - | MySQL 密码 |

### Redis 配置

| 变量名 | 默认值 | 说明 |
|--------|--------|------|
| `TL_REDIS_MODE` | `embedded` | Redis 模式：`embedded`(内嵌 Redis) / `external`(外置 Redis) |
| `TL_REDIS_HOST` | `localhost` | Redis 主机地址 |
| `TL_REDIS_PORT` | `6379` | Redis 端口 |
| `TL_REDIS_PASSWORD` | - | Redis 密码 |

### 应用配置

| 变量名 | 默认值 | 说明 |
|--------|--------|------|
| `JAVA_OPTS` | `-Xms512m -Xmx1g -XX:+UseG1GC` | JVM 参数 |
| `SERVER_PORT` | `8080` | 服务端口 |

## 📊 运行状态检查

### 查看 PID 文件

```bash
cat ../tokenlimit-server.pid
```

### 查看日志

```bash
tail -f ../logs/tokenlimit.log
```

### 健康检查

```bash
curl http://localhost:8080/health
```

## 🛑 停止服务

**Linux/Mac:**
```bash
./shutdown.sh
```

**Windows:**
```cmd
shutdown.bat
```

脚本会优雅关闭服务（发送 SIGTERM 信号），等待 30 秒后如果仍未停止则强制终止。

## 🔧 故障排查

### 启动失败

1. **检查 Java 版本**
   ```bash
   java -version
   # 需要 JDK 17+
   ```

2. **检查 JAR 文件**
   ```bash
   ls -la ../tokenlimit-server/target/tokenlimit-server-*.jar
   # 确保 JAR 文件存在
   ```

3. **查看日志**
   ```bash
   cat ../logs/tokenlimit.log
   ```

### 端口冲突

如果 8080 端口被占用，可以修改端口：

```bash
export SERVER_PORT=8081
./startup.sh
```

### 内存不足

调整 JVM 参数：

```bash
export JAVA_OPTS="-Xms256m -Xmx512m"
./startup.sh
```

## 📝 注意事项

1. **首次启动**：单机模式下会自动初始化 Derby 数据库和内嵌 Redis，可能需要 10-30 秒。
2. **数据持久化**：Derby 数据保存在 `../data/derby-data` 目录，请勿随意删除。
3. **生产环境**：建议使用 Docker Compose 或 K8s 部署，参考 `/deploy/docker-compose.yml`。
4. **权限问题**：确保脚本有执行权限 `chmod +x *.sh`。

## 📞 技术支持

- 文档：https://docs.tokenlimit.com
- 邮箱：support@tokenlimit.com
