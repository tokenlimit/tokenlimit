# TokenLimit 部署指南

本文档详细说明 TokenLimit 系统的三种部署模式：**前后端一体化（推荐单机）**、**前后端分离（推荐生产）**，以及**零依赖内嵌组件（Derby + Embedded Redis）**方案。

---

## 一、核心架构设计说明

参考 Nacos 的单机部署架构，本系统支持以下底层存储方案切换：

### 1. 内嵌数据库方案 (Apache Derby)
*   **技术选型**：采用 **Apache Derby** (纯 Java 实现)，而非 H2。
*   **优势**：
    *   **零依赖**：无需安装 MySQL/PostgreSQL，JVM 启动即包含数据库引擎。
    *   **跨平台一致性**：无 native 库依赖，完美支持 Windows/Linux/Mac/Docker。
    *   **自动化生命周期**：启动时自动检测 `data/derby-data` 目录，不存在则自动建表 (`derby-schema.sql`)。
    *   **数据安全**：利用 Derby 的文件锁机制 (`db.lck`) 保证单进程独占，防止多实例数据损坏。
*   **适用场景**：单机开发、测试、演示、小规模生产环境。

### 2. 内嵌 Redis 方案
*   **技术选型**：单机模式下集成 **Embedded Redis** (`redis.embedded` 库)。
*   **机制**：
    *   检测到未配置外部 Redis 地址时，自动在 JVM 内部启动一个临时 Redis 实例（默认端口 6379）。
    *   数据存储在临时目录，重启后清除（适合缓存场景）；或配置持久化路径。
*   **适用场景**：单机全栈部署，无需单独安装 Redis 服务。

---

## 二、部署模式详解

### 模式 A：前后端一体化 + 内嵌组件 (All-in-One)
**特点**：一个 Jar 包，包含前端静态资源、内嵌 Derby 数据库、内嵌 Redis。**零外部依赖，开箱即用。**

#### 1. 构建
```bash
cd tokenlimit-java
# 构建时会自动编译前端并打包到 static 目录
mvn clean package -DskipTests
```

#### 2. 运行
```bash
java -jar tokenlimit-server/target/tokenlimit-server.jar
```

#### 3. 访问
*   前端页面：`http://localhost:8080`
*   后端 API：`http://localhost:8080/api/...`

#### 4. 数据存储位置
*   **Derby 数据**：`./data/derby-data` (相对于启动命令的执行目录)
*   **日志文件**：`./logs/`

---

### 模式 B：前后端一体化 + 外部中间件 (生产推荐)
**特点**：使用打包好的 Jar 包，但连接外部的 **MySQL** 和 **Redis**，保证数据高可用和持久化。

#### 1. 准备外部组件
*   安装 MySQL (5.7+) 并创建数据库 `tokenlimit`。
*   执行初始化 SQL：`deploy/mysql/init/init.sql`。
*   安装 Redis (6.0+)。

#### 2. 配置文件 (`application-prod.yml`)
```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/tokenlimit?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: root
    password: your_password
  redis:
    host: 127.0.0.1
    port: 6379
    password: your_redis_password

# 关闭内嵌组件开关
system:
  embedded:
    db-enabled: false  # 禁用 Derby
    redis-enabled: false # 禁用 Embedded Redis
```

#### 3. 运行
```bash
java -jar tokenlimit-server/target/tokenlimit-server.jar --spring.profiles.active=prod
```

---

### 模式 C：前后端分离部署
**特点**：前端独立部署在 Nginx，后端仅运行 Jar 包。适用于大规模集群、CDN 加速场景。

#### 1. 后端部署
同 **模式 B**，但需配置 CORS 允许前端域名访问（若端口不同）。
```yaml
server:
  port: 8080
# 确保 SecurityConfig 中放行了前端需要的静态资源接口（如有）
```

#### 2. 前端构建
```bash
cd console
npm install
npm run build
```
生成的静态文件位于 `console/dist/`。

#### 3. Nginx 配置示例
```nginx
server {
    listen 80;
    server_name tokenlimit.example.com;

    # 前端静态资源
    location / {
        root /usr/share/nginx/html/tokenlimit; # 放置 console/dist 的内容
        index index.html;
        try_files $uri $uri/ /index.html; # 支持 Vue Router History 模式
    }

    # 后端 API 反向代理
    location /api/ {
        proxy_pass http://127.0.0.1:8080/api/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
}
```

---

## 三、常见问题 (FAQ)

### Q1: 如何切换内嵌 Derby 和外部 MySQL？
系统通过 `system.embedded.db-enabled` 参数控制。
*   `true` (默认): 启动内嵌 Derby，忽略 `spring.datasource` 配置。
*   `false`: 读取 `spring.datasource` 配置连接外部 MySQL。

### Q2: 单机模式数据存在哪？会丢失吗？
*   **Derby**: 数据持久化在启动目录下的 `data/derby-data` 文件夹中。只要不删除该文件夹，数据永久保存。
*   **Embedded Redis**: 默认配置为临时存储（重启清空）。如需持久化，需在代码配置中指定 `persistenceDirectory`。

### Q3: 为什么集群模式不能用内嵌 Derby？
Derby 是基于本地文件锁 (`db.lck`) 的单进程数据库。
*   **数据孤岛**：多节点集群中，每个节点的 Derby 数据互不相通。
*   **锁冲突**：无法通过共享存储（NFS）让多个节点同时访问同一个 Derby 目录。
*   **建议**：集群模式请务必使用 **MySQL + Redis** 外部中间件方案。

### Q4: 前端页面刷新出现 404？
这是 Vue Router History 模式的典型问题。
*   **一体化部署**：后端已内置 `SpaFallbackFilter`，自动处理，无需配置。
*   **分离部署**：必须在 Nginx/Apache 中配置 `try_files $uri $uri/ /index.html;`，将所有未知路由重定向到 `index.html`。
