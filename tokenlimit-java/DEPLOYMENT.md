# TokenLimit 生产级部署指南

## 目录
- [一、架构概述](#一架构概述)
- [二、部署模式](#二部署模式)
  - [2.1 前后端一体化部署（推荐）](#21-前后端一体化部署推荐)
  - [2.2 前后端分离部署](#22-前后端分离部署)
- [三、数据库方案](#三数据库方案)
  - [3.1 内嵌 Derby 模式（单机/开发测试）](#31-内嵌-derby-模式单机开发测试)
  - [3.2 外部 MySQL 模式（生产集群）](#32-外部-mysql-模式生产集群)
- [四、Redis 方案](#四 redis 方案)
  - [4.1 外置 Redis 模式（开发测试）](#41-外置-redis-模式开发测试)
  - [4.2 外部 Redis 模式（生产环境）](#42-外部-redis-模式生产环境)
- [五、一键启动命令](#五一键启动命令)
- [六、生产环境配置清单](#六生产环境配置清单)

---

## 一、架构概述

TokenLimit 采用与 Nacos 相同的动态数据源架构设计，支持：
- **前后端一体化**：前端构建产物自动打包进 JAR，通过 SPA Fallback Filter 支持 Vue Router history 模式
- **动态数据源**：根据配置自动切换内嵌 Derby 或外部 MySQL
- **双认证体系**：JWT 管理端认证 + OpenAI Compatible API Key 认证

---

## 二、部署模式

### 2.1 前后端一体化部署（推荐）

**适用场景**：单机部署、开发测试、中小规模生产环境

**技术实现**：
- 使用 `frontend-maven-plugin` 在 Maven 打包时自动构建前端
- 前端产物 (`console/dist`) 被复制到 JAR 的 `classpath:/static` 目录
- `SpaFallbackFilter` 拦截无扩展名请求并转发至 `/index.html`
- `SecurityConfig` 放行静态资源访问

**构建命令**：
```bash
cd /workspace/tokenlimit-java
mvn clean package -DskipTests
```

**运行命令**：
```bash
java -jar tokenlimit-java/tokenlimit-server/target/tokenlimit-server.jar
```

**访问地址**：
- 前端控制台：http://localhost:8080
- 后端 API：http://localhost:8080/api/admin/**
- OpenAI 兼容网关：http://localhost:8080/v1/chat/completions

**跳过前端构建**（仅修改后端代码时）：
```bash
mvn clean package -DskipTests -Dskip.frontend=true
```

---

### 2.2 前后端分离部署

**适用场景**：大规模生产环境、CDN 加速、独立前端团队

**前端部署**：
```bash
cd /workspace/console
npm install
npm run build

# 将 dist 目录部署到 Nginx 或其他 Web 服务器
cp -r dist/* /var/www/html/
```

**Nginx 配置示例**：
```nginx
server {
    listen 80;
    server_name console.tokenlimit.com;
    root /var/www/html;
    index index.html;

    # SPA 路由支持
    location / {
        try_files $uri $uri/ /index.html;
    }

    # API 代理
    location /api/ {
        proxy_pass http://backend-server:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }

    # OpenAI 兼容网关代理
    location /v1/ {
        proxy_pass http://backend-server:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
```

**后端运行**：
```bash
java -jar tokenlimit-server.jar --spring.profiles.active=prod
```

---

## 三、数据库方案

### 3.1 内嵌 Derby 模式（单机/开发测试）

**参考 Nacos 架构**：采用 Apache Derby 嵌入式数据库，纯 Java 实现，零配置启动。

**技术选型理由**：
1. **纯 Java 实现**：无本地依赖，跨平台一致性保证
2. **企业级 SQL 兼容性**：ACID 事务支持，适合配置管理和审计场景
3. **零配置嵌入式**：JVM 进程内运行，无需单独数据库服务
4. **文件锁机制**：通过 `db.lck` 文件锁防止多进程并发访问

**启用方式**：
```bash
# 单机模式启动（默认使用内嵌 Derby）
java -jar tokenlimit-server.jar -m standalone
```

**数据存储位置**：
```
${user.home}/tokenlimit/data/derby-data/
├── db.lck          # 排他性文件锁
├── derby.log       # Derby 内部日志
└── seg0/           # 数据文件目录
```

**自动初始化流程**：
1. 启动时检测 `${user.home}/tokenlimit/data/derby-data` 目录
2. 如不存在则自动创建，并通过 JDBC 执行建表脚本
3. Derby 日志重定向到 `${user.home}/tokenlimit/logs/derby.log`

**注意事项**：
- ⚠️ **仅限单机模式**：集群环境下会导致数据孤岛
- ⚠️ **文件锁冲突**：共享存储场景下只有一个节点能启动
- ⚠️ **性能限制**：不适合高并发写入场景

---

### 3.2 外部 MySQL 模式（生产集群）

**适用场景**：生产环境、集群部署、高可用要求

**配置方式**（`application.yml`）：
```yaml
spring:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://mysql-host:3306/tokenlimit?useUnicode=true&characterEncoding=utf8mb4&useSSL=false&serverTimezone=Asia/Shanghai
    username: tokenlimit
    password: ${DB_PASSWORD:your_secure_password}
```

**初始化数据库**：
```bash
# 执行建表脚本
mysql -h mysql-host -u root -p < deploy/mysql/init/init.sql
```

**集群部署架构**：
```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  Node A     │     │  Node B     │     │  Node C     │
│ (Tomcat)    │     │ (Tomcat)    │     │ (Tomcat)    │
└──────┬──────┘     └──────┬──────┘     └──────┬──────┘
       │                   │                   │
       └───────────────────┼───────────────────┘
                           │
                  ┌────────▼────────┐
                  │   MySQL Cluster │
                  │  (主从/集群/MGR) │
                  └─────────────────┘
```

**生产建议**：
- ✅ 使用连接池（HikariCP，默认配置）
- ✅ 开启 SSL 加密传输
- ✅ 配置只读副本用于查询
- ✅ 定期备份和监控

---

## 四、Redis 方案

### 4.1 外置 Redis 模式（开发测试）

**说明**：Spring Boot 不直接支持内嵌 Redis，开发测试可使用以下方案：

**方案 A：Docker 快速启动**
```bash
docker run -d --name redis-dev \
  -p 6379:6379 \
  -v redis-data:/data \
  redis:latest
```

**方案 B：使用 H2 替代（仅限配额缓存外的功能）**
```yaml
# 临时禁用 Redis 相关功能
tokenlimit:
  quota-precompute-enabled: false
```

---

### 4.2 外部 Redis 模式（生产环境）

**配置方式**（`application.yml`）：
```yaml
spring:
  data:
    redis:
      host: redis-host
      port: 6379
      database: 0
      password: ${REDIS_PASSWORD:your_secure_password}
      timeout: 2000ms
      lettuce:
        pool:
          max-active: 16
          max-idle: 8
          min-idle: 0
```

**生产建议**：
- ✅ 使用 Redis Cluster 或 Sentinel 高可用架构
- ✅ 开启持久化（RDB+AOF）
- ✅ 配置合理的连接池参数
- ✅ 监控内存使用和命中率

---

## 五、一键启动命令

### 开发环境（内嵌 Derby + Docker Redis）
```bash
# 1. 启动 Redis
docker run -d --name redis-dev -p 6379:6379 redis:latest

# 2. 构建并启动（前后端一体 + 内嵌 Derby）
cd /workspace/tokenlimit-java
mvn clean package -DskipTests
java -jar tokenlimit-java/tokenlimit-server/target/tokenlimit-server.jar -m standalone

# 访问 http://localhost:8080
# 默认账号：admin / admin123
```

### 生产环境（MySQL + Redis Cluster）
```bash
# 1. 初始化数据库
mysql -h mysql-prod -u root -p < deploy/mysql/init/init.sql

# 2. 构建（跳过测试）
cd /workspace/tokenlimit-java
mvn clean package -DskipTests

# 3. 启动（指定配置文件）
java -jar tokenlimit-java/tokenlimit-server/target/tokenlimit-server.jar \
  --spring.datasource.url=jdbc:mysql://mysql-prod:3306/tokenlimit?useUnicode=true&characterEncoding=utf8mb4&useSSL=true&serverTimezone=Asia/Shanghai \
  --spring.datasource.username=tokenlimit \
  --spring.datasource.password=${DB_PASSWORD} \
  --spring.data.redis.host=redis-prod \
  --spring.data.redis.password=${REDIS_PASSWORD} \
  --tokenlimit.jwt.secret=${JWT_SECRET} \
  --tokenlimit.admin.password=${ADMIN_PASSWORD}

# 4. Nginx 反向代理（可选）
# 见 2.2 节 Nginx 配置示例
```

### Docker Compose 一键部署（生产简化版）
```yaml
# docker-compose.yml
version: '3.8'
services:
  mysql:
    image: mysql:8.0
    environment:
      MYSQL_ROOT_PASSWORD: root_password
      MYSQL_DATABASE: tokenlimit
    volumes:
      - mysql-data:/var/lib/mysql
      - ./deploy/mysql/init/init.sql:/docker-entrypoint-initdb.d/init.sql
    ports:
      - "3306:3306"

  redis:
    image: redis:latest
    command: redis-server --requirepass redis_password
    volumes:
      - redis-data:/data
    ports:
      - "6379:6379"

  tokenlimit:
    image: tokenlimit-server:latest
    depends_on:
      - mysql
      - redis
    environment:
      SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/tokenlimit?useUnicode=true&characterEncoding=utf8mb4&useSSL=false&serverTimezone=Asia/Shanghai
      SPRING_DATASOURCE_USERNAME: root
      SPRING_DATASOURCE_PASSWORD: root_password
      SPRING_DATA_REDIS_HOST: redis
      SPRING_DATA_REDIS_PASSWORD: redis_password
      TOKENLIMIT_JWT_SECRET: your_jwt_secret_key_here
      TOKENLIMIT_ADMIN_PASSWORD: your_admin_password
    ports:
      - "8080:8080"

volumes:
  mysql-data:
  redis-data:
```

**启动命令**：
```bash
docker-compose up -d
```

---

## 六、生产环境配置清单

### 必须修改的配置项

| 配置项 | 默认值 | 生产建议 | 安全等级 |
|--------|--------|----------|----------|
| `spring.datasource.password` | root | 强密码 + 环境变量 | 🔴 高危 |
| `spring.data.redis.password` | 无 | 强密码 + 环境变量 | 🔴 高危 |
| `tokenlimit.jwt.secret` | dev-only-secret | 32+ 字节随机字符串 | 🔴 高危 |
| `tokenlimit.admin.password` | admin123 | 强密码 + bcrypt 哈希 | 🔴 高危 |
| `tokenlimit.hash-pepper` | dev-only-pepper | 随机字符串 | 🟠 中危 |
| `server.port` | 8080 | 根据规划调整 | 🟢 低危 |

### 推荐启用的安全加固

```yaml
# application-prod.yml
server:
  ssl:
    enabled: true
    key-store: classpath:ssl/keystore.p12
    key-store-password: ${SSL_PASSWORD}

spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000

logging:
  level:
    com.tokenlimit: WARN
  pattern:
    file: "%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n"
  file:
    name: /var/log/tokenlimit/server.log
    max-size: 100MB
    max-history: 30
```

### 监控与健康检查

**健康检查端点**：
```bash
curl http://localhost:8080/health
```

**推荐监控指标**：
- JVM 内存和 GC
- 数据库连接池状态
- Redis 连接和命中率
- API 响应时间和错误率
- 配额拦截次数

---

## 附录：常见问题

### Q1: 如何切换 Derby 和 MySQL？
**A**: 通过启动参数控制：
- 单机模式（Derby）：`java -jar app.jar -m standalone`
- 集群模式（MySQL）：配置 `spring.datasource.url` 即可

### Q2: 前端页面刷新 404？
**A**: 确保 `SpaFallbackFilter` 已启用，Nginx 配置 `try_files $uri $uri/ /index.html`

### Q3: 如何升级数据库 schema？
**A**: 使用 Flyway 或手动执行迁移脚本（`deploy/migration/V*.sql`）

### Q4: 内嵌 Derby 性能如何优化？
**A**: Derby 仅适用于开发测试，生产环境请使用 MySQL

---

**文档版本**: V1.0  
**最后更新**: 2024 年  
**技术支持**: tokenlimit-team
