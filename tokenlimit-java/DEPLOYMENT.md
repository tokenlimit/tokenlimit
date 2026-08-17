# TokenLimit 部署指南

本文档详细介绍 TokenLimit 系统的多种部署方式，包括前后端一体化部署、前后端分离部署，以及开发环境下的内嵌数据库/Redis 方案。

## 目录

- [1. 前后端一体化部署 (推荐生产环境)](#1-前后端一体化部署-推荐生产环境)
- [2. 前后端分离部署](#2-前后端分离部署)
- [3. 开发环境：内嵌数据库与 Redis](#3-开发环境内嵌数据库与-redis)
- [4. 配置文件说明](#4-配置文件说明)
- [5. 常见问题](#5-常见问题)

---

## 1. 前后端一体化部署 (推荐生产环境)

参考 Nacos 架构，前端构建产物直接嵌入后端 JAR 包中，实现单文件部署，简化运维流程。

### 1.1 构建步骤

确保本地已安装 Maven 和 Node.js (用于构建前端)。

```bash
cd tokenlimit-java

# 执行全量构建（自动安装前端依赖、构建前端、打包后端）
mvn clean package -DskipTests
```

**构建过程说明：**
1. Maven 插件 `frontend-maven-plugin` 会自动进入 `console` 目录。
2. 自动安装指定版本的 Node.js 和 npm。
3. 执行 `npm install` 和 `npm run build`。
4. 将生成的 `dist` 静态资源复制到后端资源的 `static` 目录。
5. 最终生成包含前后端代码的 Fat JAR。

> **提示**: 如果本地已构建过前端且无需重新构建，可添加参数 `-Dskip.frontend=true` 跳过前端构建步骤，加快打包速度。

### 1.2 运行服务

```bash
java -jar tokenlimit-server/target/tokenlimit-server.jar
```

或者指定配置文件：

```bash
java -jar tokenlimit-server/target/tokenlimit-server.jar --spring.profiles.active=prod
```

### 1.3 访问验证

启动成功后，直接访问后端端口即可：
- 首页: `http://localhost:8080`
- API 接口: `http://localhost:8080/api/...`

**原理说明：**
- 后端配置了静态资源映射，优先查找 classpath 下的 `static` 目录。
- 配置了 `SpaFallbackFilter`，解决 Vue Router History 模式刷新 404 问题，所有未知路径均转发至 `index.html`。

---

## 2. 前后端分离部署

适用于需要独立扩容前端、使用 CDN 加速或已有统一网关的场景。

### 2.1 后端部署

仅构建后端代码，跳过前端构建：

```bash
cd tokenlimit-java
mvn clean package -DskipTests -Dskip.frontend=true
```

运行后端服务：

```bash
java -jar tokenlimit-server/target/tokenlimit-server.jar
```

此时后端仅提供 API 服务，访问根路径 `/` 将不会返回前端页面（除非手动放置静态文件）。

### 2.2 前端部署

#### 方式 A: Nginx 托管静态文件

1. **构建前端**
   ```bash
   cd console
   npm install
   npm run build
   ```
   构建产物位于 `console/dist` 目录。

2. **配置 Nginx**
   
   将 `dist` 目录下的文件上传至服务器，并配置 Nginx：

   ```nginx
   server {
       listen 80;
       server_name your-domain.com;

       location / {
           root /usr/share/nginx/html/dist; # 指向 dist 目录
           index index.html;
           try_files $uri $uri/ /index.html; # 关键配置：支持 History 模式
       }

       # 代理 API 请求到后端
       location /api/ {
           proxy_pass http://backend-host:8080/api/;
           proxy_set_header Host $host;
           proxy_set_header X-Real-IP $remote_addr;
       }
   }
   ```

#### 方式 B: Docker 容器化部署

使用 Nginx 官方镜像快速部署前端：

```dockerfile
# Dockerfile example
FROM nginx:alpine
COPY dist/ /usr/share/nginx/html/
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
```

---

## 3. 开发环境：内嵌数据库与 Redis

为了降低本地开发和演示环境的搭建成本，系统支持内嵌数据库和 Redis（需特定配置或 Profile）。

### 3.1 内嵌数据库 (H2 / MySQL Testcontainers)

#### 方案 A: 使用 H2 内存数据库 (最简模式)

适用于纯功能验证，数据重启后丢失。

在 `application-dev.yml` 中配置：

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:testdb;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE
    driver-class-name: org.h2.Driver
    username: sa
    password: 
  h2:
    console:
      enabled: true
      path: /h2-console
  sql:
    init:
      mode: always # 自动初始化表结构
```

> **注意**: 由于本项目主要基于 MySQL 语法开发，使用 H2 时需开启 `MODE=MySQL`，但复杂 SQL 仍可能存在兼容性问题。建议仅用于单元测试。

#### 方案 B: 使用 Testcontainers (推荐本地开发)

自动在 Docker 中启动临时的 MySQL 和 Redis 容器，测试结束后自动销毁。

在 `pom.xml` 中确保引入依赖：
```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>mysql</artifactId>
    <scope>test</scope>
</dependency>
```

在测试类中使用：
```java
@SpringBootTest
@Testcontainers
public class LocalDevTest {
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("tokenlimit")
        .withUsername("root")
        .withPassword("root");

    @DynamicPropertySource
    static void configureTestProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }
}
```

### 3.2 内嵌 Redis (Local Redis Mock)

Spring Boot 没有原生的内嵌 Redis，但在开发环境中可以使用以下两种方式：

#### 方式 A: 使用 Embedded Redis 库 (仅限 Java 测试/本地)

引入依赖：
```xml
<dependency>
    <groupId>it.ozimov</groupId>
    <artifactId>embedded-redis</artifactId>
    <version>0.7.3</version>
    <scope>test</scope>
</dependency>
```

配置类：
```java
@Bean
@Profile("dev")
public RedisServer embeddedRedisServer() throws IOException {
    RedisServer redisServer = RedisServer.builder().port(6379).setting("maxmemory 128M").build();
    redisServer.start();
    return redisServer;
}
```

#### 方式 B: 使用 Docker Compose (推荐)

在项目根目录创建 `docker-compose.dev.yml`：

```yaml
version: '3'
services:
  mysql:
    image: mysql:8.0
    environment:
      MYSQL_ROOT_PASSWORD: root
      MYSQL_DATABASE: tokenlimit
    ports:
      - "3306:3306"
    volumes:
      - ./deploy/mysql/init:/docker-entrypoint-initdb.d
      
  redis:
    image: redis:alpine
    ports:
      - "6379:6379"

  app:
    build: .
    ports:
      - "8080:8080"
    depends_on:
      - mysql
      - redis
    environment:
      - SPRING_PROFILES_ACTIVE=dev
```

启动命令：
```bash
docker-compose -f docker-compose.dev.yml up
```

---

## 4. 配置文件说明

系统支持多环境配置，通过 `spring.profiles.active` 切换。

| 配置文件 | 适用场景 | 数据库 | Redis | 前端 |
| :--- | :--- | :--- | :--- | :--- |
| `application-dev.yml` | 本地开发 | 本地 MySQL/Docker | 本地 Redis/Docker | 分离或内嵌均可 |
| `application-prod.yml` | 生产环境 | 远程高可用 MySQL | 远程集群 Redis | **强制内嵌** |
| `application-test.yml` | 集成测试 | Testcontainers | Embedded Redis | 内嵌 |

**关键配置项示例 (`application-prod.yml`):**

```yaml
server:
  port: 8080

spring:
  resources:
    static-locations: classpath:/META-INF/resources/,classpath:/resources/,classpath:/static/,classpath:/public/
  mvc:
    throw-exception-if-no-handler-found: false
    
# 关闭 Swagger 等开发工具
springdoc:
  api-docs:
    enabled: false
```

---

## 5. 常见问题

### Q1: 一体化部署后，访问首页出现 404？
**A:** 检查构建日志，确认 `console/dist` 文件是否成功复制到 `target/classes/static`。同时检查浏览器控制台是否有 JS 加载错误（可能是 base href 路径问题）。

### Q2: 前端页面刷新后 404？
**A:** 这是 SPA 应用的典型问题。
- **内嵌模式**: 确保后端配置了 `SpaFallbackFilter`。
- **Nginx 模式**: 确保配置了 `try_files $uri $uri/ /index.html;`。

### Q3: 如何跳过前端构建以加快 CI/CD 速度？
**A:** 在 Maven 命令中添加 `-Dskip.frontend=true`。前提是之前的构建产物已经存在于 `console/dist` 且无需更新。

### Q4: 生产环境如何修改 API Base URL 而不重新打包？
**A:** 
1. **内嵌模式**: 使用命令行参数覆盖 `java -jar app.jar --provider.dashscope.api-base-url=xxx`。
2. **配置中心**: 如果集成了 Nacos/Apollo，直接在配置中心修改并推送。
