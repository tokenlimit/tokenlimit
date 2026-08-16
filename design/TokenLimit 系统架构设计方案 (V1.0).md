这是一份为你全面汇总、深度优化后的 **《TokenLimit 技术架构设计方案（最终完整版）》**。

这份文档融合了从底层并发模型、网络通信、存储一致性，到工程构建、集群扩容、安全合规的所有核心设计。你可以直接将其作为项目的**技术白皮书、架构评审材料或开发指导手册**。

---

# TokenLimit 技术架构设计方案

**文档版本**：V1.0  
**项目定位**：企业大模型 Token 预算网关与 AI FinOps 平台  
**核心架构原则**：极致低延迟、高并发无状态、数据强一致、极简私有化交付

---

## 1. 总体架构设计

### 1.1 逻辑架构图

```text
┌─────────────────────────────────────────────────────────────────────────┐
│                           客户端层 (Clients)                            │
│         Cursor / DeepSeek Harness / OpenAI SDK / 内部自研 Agent         │
└──────────────────────────────┬──────────────────────────────────────────┘
                               │ OpenAI Compatible API (HTTPS/SSE)
                               ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                        TokenLimit Gateway (网关层)                      │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐   │
│  │  鉴权拦截器  │→ │  配额检查器  │→ │  预估引擎   │→ │  路由转发器  │   │
│  └─────────────┘  └─────────────┘  └─────────────┘  └──────┬──────┘   │
│                                                              │          │
│  ┌───────────────────────────────────────────────────────────┴───────┐  │
│  │                   流式透传与结算层 (Stream & Settle)              │  │
│  └───────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────┬──────────────────────────────────────────┘
                               │ HTTP 转发 (连接池复用)
                               ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                        真实大模型供应商 (Providers)                     │
│             DeepSeek / OpenAI / 通义千问 / 智谱 / 硅基流动              │
└─────────────────────────────────────────────────────────────────────────┘

       ▲                                    ▲
       │ 读/写配额                           │ 持久化用量
┌──────┴───────┐                    ┌───────┴──────┐
│   Redis 7    │                    │   MySQL 8    │
│ (实时账本)    │                    │ (事实账本)    │
└──────────────┘                    └──────────────┘
```

### 1.2 部署架构演进
- **Phase 1 (MVP)**：Docker Compose 单机部署（App + MySQL + Redis），前后端一体化 Jar 包交付。
- **Phase 2 (生产集群)**：负载均衡（Nginx/SLB） + 多节点无状态网关 + 高可用 Redis/MySQL。
- **Phase 3 (云原生)**：Kubernetes Deployment + HPA 自动扩缩容 + 云厂商 RDS/Redis Cluster。

---

## 2. 核心技术栈选型

| 模块 | 技术选型 | 核心配置与说明 |
| :--- | :--- | :--- |
| **开发语言** | **Java 21 (LTS)** | 引入虚拟线程，解决高并发长连接阻塞问题。 |
| **核心框架** | **Spring Boot 3.2.x+** | 开启 `spring.threads.virtual.enabled=true`。 |
| **并发模型** | **虚拟线程 (Virtual Threads)** | 替代传统 Tomcat 线程池，轻松支撑万级并发流式请求。 |
| **HTTP 转发** | **Java 21 HttpClient / Apache HttpClient 5** | **必须配置连接池**。推荐 Java 原生 HttpClient（默认复用，轻量）。 |
| **实时配额** | **Redis 7.x** | 存储 `used` 计数器，高性能读写，支持 Lua 脚本。 |
| **持久化** | **MySQL 8.0** | 存储 `usage_log` 事实表、团队/用户/配额配置。 |
| **预估引擎** | **jtokkit** | 统一 Token 预估基准，用于异常检测与中断兜底。 |
| **前端框架** | **Vue 3 / React + Vite** | 现代单页应用（SPA），开发阶段独立运行。 |
| **构建集成** | **Maven + frontend-maven-plugin** | **前后端一体化打包核心**，自动将前端产物嵌入后端 Jar 包。 |

---

## 3. 工程结构与构建方案（前后端一体化）

为了平衡开发效率与私有化交付体验，采用 **“开发前后端分离，生产部署前后端一体”** 的工程架构。

### 3.1 目录结构 (Monorepo)
```text
tokenlimit/
├── tokenlimit-console/       # 前端项目 (Vue3/React + Vite)
│   ├── src/
│   ├── package.json
│   └── vite.config.js
├── tokenlimit-server/        # 后端项目 (Spring Boot)
│   ├── src/main/java/
│   ├── src/main/resources/
│   │   └── static/           # 前端打包后的产物最终存放地
│   └── pom.xml
└── docker-compose.yml
```

### 3.2 构建与打包流程
在后端 `pom.xml` 中引入 `frontend-maven-plugin`。执行 `mvn clean package` 时自动：
1. 执行 `npm install` 和 `npm run build`。
2. 将前端 `dist` 目录内容拷贝到后端 `src/main/resources/static`。
3. 打出包含前端页面的完整 Spring Boot Fat Jar。

### 3.3 运行时路由 Fallback
为解决前端 History 模式刷新 404 问题，Spring Boot 需配置全局转发：
- 将所有**非 `/api`、非 `/v1` 开头**且**找不到静态资源**的请求，内部转发到 `index.html`。

---

## 4. 核心网关链路设计

1. **接入与鉴权**：解析 `Authorization: Bearer <access_key>:<secret>`，获取 Team/User 信息。
2. **策略校验**：检查 Team Model Policy，判断模型是否允许。
3. **Token 预估**：使用 jtokkit 计算 `estimated_prompt_tokens`。
4. **配额拦截**：读 Redis `used`，若超限直接返回 429。
5. **路由与转发**：查找 Provider Credential，通过**连接池 HttpClient** 发起请求。
6. **流式透传**：收到 SSE Chunk **立刻 Flush 给客户端**，同时累计已转发内容。
7. **结算与持久化**：流结束/中断后，获取真实 Usage（或预估兜底），**先写 MySQL，再更新 Redis**。

### 4.1 API Key 凭证格式与鉴权流程

API Key 为**两段式凭证**：`accessKey`（格式 `tl_ak_xxxxxxxx`，公开标识，全局唯一）+ `secret`（格式 `sk_tl_xxxxxxxx...`，机密，明文仅创建/重置时返回一次，库中仅存 HMAC-SHA256 哈希，服务端 pepper 密钥参与防离线碰撞）。

为兼容 Cursor 等只支持单个 API Key 的客户端，客户端将两段用冒号拼接为单个字符串，网关按第一个冒号拆分后双向校验：

```text
客户端：Authorization: Bearer <access_key>:<secret>
       例：Bearer tl_ak_3f8a9c21:sk_tl_4f2b8a6c...

网关：1. 按 accessKey 查库定位 API Key（不存在 → 401 INVALID_API_KEY）
     2. 校验状态：ENABLED / 过期自动置 EXPIRED / 禁用
     3. 校验 secret 与 secretHash 是否匹配（不匹配 → 401 INVALID_API_KEY）
     4. 通过后注入 SecurityContext（principal=ApiKey，credentials=[accessKey, secret]），
        供后续配额 check / report 复用
```

> 设计要点：accessKey 作为公开标识可安全出现在日志与审计中；secret 只存哈希，泄露后调用重置密钥接口单独更换 secret 即可，无需重建整把 Key。

---

## 5. 关键技术实现方案

### 5.1 HTTP 连接池配置 (防延迟爆炸)
- **最大连接数**：流式请求是长连接，`MaxConnTotal` 和 `MaxConnPerRoute` 必须设置足够大（建议 2000+）。
- **空闲回收**：配置 `evictIdleConnections(30s)`，防止复用到死连接。

### 5.2 流式透传与内存保护 (防 OOM)
- **绝对禁止**将完整响应体读入内存。
- 必须使用 `InputStream` 逐块读取，配合 `OutputStream.flush()` 逐块写出。

### 5.3 异常与中断结算 (防数据丢失)
- **正常结束**：以厂商真实 `usage` 为准，`usage_source = PROVIDER`。
- **流式中断**：捕获 IO 异常，用 jtokkit 预估已转发内容，`usage_source = ESTIMATED`, `status = INTERRUPTED`。
- **异常检测**：对比预估值与真实值，偏差超阈值（如 50%）标记 `anomaly_detected = 1`。

---

## 6. 集群化与高可用改造

TokenLimit 网关层是**无状态（Stateless）** 的，单机性能不足时可水平扩容。扩容前需完成以下适配：

### 6.1 登录态与会话管理
- **改造**：废弃本地 HttpSession，改为 **JWT (推荐)** 或 **Spring Session Data Redis**。

### 6.2 本地缓存一致性
- **改造**：API Key/配置信息的本地缓存（Caffeine）需设置短 TTL（10~30s），或通过 **Redis Pub/Sub** 广播失效消息。

### 6.3 分布式限流与并发控制
- **改造**：废弃本地 Semaphore/Guava RateLimiter，改用 **Redis Lua 脚本**实现全局分布式限流。

### 6.4 异步结算与本地降级队列
- **改造**：若对数据可靠性要求极高，将本地内存队列替换为 **RabbitMQ/Kafka**；否则接受极端宕机下丢失少量未落盘数据的代价。

### 6.5 定时任务防重
- **改造**：引入 **ShedLock** (`@SchedulerLock`)，确保集群中同一时刻只有一个节点执行定时任务。

---

## 7. 安全与合规设计

### 7.1 供应商密钥安全
- **加密存储**：真实大模型 API Key 使用 AES-256 或 KMS 加密存入 MySQL。
- **内存解密**：仅在转发瞬间解密注入 Header，严禁打印日志。

### 7.2 数据隐私与“不留痕”模式
- **Full Mode**：记录完整 Prompt/Completion（用于审计）。
- **Metadata Only Mode（默认）**：**绝不将 Prompt 原文写入 MySQL 或日志**，仅记录 Team/User/Model/Tokens/Cost。

---

## 8. 可观测性设计

### 8.1 链路追踪 (TraceId)
- 生成全局唯一 `traceId`，贯穿网关日志、MySQL `usage_log` 及透传给厂商的 Header。

### 8.2 核心监控指标 (Prometheus)
- `tokenlimit_requests_total` (按 Team/Model 分组)
- `tokenlimit_tokens_consumed_total`
- `tokenlimit_blocked_requests_total`
- `tokenlimit_gateway_latency_seconds` (P99 < 50ms)
- `tokenlimit_active_streams` (监控连接池水位)

---

## 9. 高可用与容灾策略

### 9.1 Redis 故障降级
- `fail-close`：拒绝请求，保成本。
- `fail-open`：放行请求，本地暂存记录，保业务。

### 9.2 MySQL 故障降级
- 写入失败时暂存本地磁盘/内存队列，恢复后后台线程重放（Replay）。

### 9.3 网关自我保护 (防雪崩)
- 针对下游 Provider 配置全局并发限制或熔断器（Resilience4j），防止厂商响应慢拖垮网关。

---

## 10. 部署架构与 Nginx 配置要点

### 10.1 负载均衡配置 (SSE 流式必备)
```nginx
location /v1/ {
    proxy_pass http://tokenlimit_cluster;
    proxy_http_version 1.1;
    proxy_set_header Connection ""; # 清空 Connection，允许长连接
    proxy_buffering off;            # 关闭代理缓冲，确保流式数据实时透传！
    proxy_cache off;
    proxy_read_timeout 300s;        # 延长超时，防止长文本生成被掐断
    proxy_send_timeout 300s;
}
```

### 10.2 K8s 自动扩缩容 (HPA)
- 基于 CPU 使用率或自定义指标（活跃流式连接数）进行扩缩容。
- 配置优雅停机（PreDestroy），确保进行中的流式请求安全结束。

---

## 11. 开发落地 Checklist

- [ ] **Step 1: 基础环境**：JDK 21, Spring Boot 3.2+, 开启虚拟线程。
- [ ] **Step 2: 前后端工程**：配置 Maven 插件跑通一体化打包，配置路由 Fallback。
- [ ] **Step 3: 哑巴代理**：实现流式透传，验证打字机效果与内存稳定性。
- [ ] **Step 4: 治理逻辑**：加入鉴权、Redis 配额检查、jtokkit 预估与 MySQL 结算。
- [ ] **Step 5: 集群适配**：改用 JWT 登录，引入 ShedLock，配置分布式限流。
- [ ] **Step 6: 安全加固**：密钥 AES 加密，开启 Metadata Only 模式。

---

### 💡 架构师寄语

这份架构方案已经实现了从**底层代码并发**到**上层业务治理**，再到**工程构建与集群扩容**的全方位闭环。

**“把复杂留给自己（自动化构建与集群适配），把简单留给用户（单容器开箱即用）”**。拿着这份方案，你的 TokenLimit 项目已经具备了成为企业级 AI 基础设施的完整骨架。放手去写代码吧！