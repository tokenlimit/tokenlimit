# TokenLimit

> 企业大模型 Token 预算网关与 AI FinOps 平台。

TokenLimit 是企业大模型调用的 **Token 预算网关**（PRD V5.0）：通过 OpenAI 兼容的代理接口（`/v1/chat/completions`、`/v1/models`），让 Cursor、DeepSeek Harness、各类 AI Client 与业务应用零改造接入，实现**事前超额拦截、按 Team / User 分摊成本、统一托管大模型供应商密钥、异常计费检测**。

它解决企业在接入 DeepSeek、OpenAI、通义千问、智谱、月之暗面等大模型 API 时常见的治理问题：

- AI 账单爆炸，Token 消耗失控；
- 团队 / 个人调用无预算约束，成本无法分摊；
- 缺少事前拦截，只能事后看到高额账单；
- 客户端直连真实供应商，API Key 泄露难以追踪；
- 预估值与厂商真实用量不一致，计费偏差无从发现。

> 类似 Nacos 服务企业微服务治理，TokenLimit 服务企业大模型调用治理，定位为 **企业大模型 Token 治理中心**：Token 计量中心、Token 配额中心、Token 预算中心、Token 审计中心、Token 密钥托管中心。

---

## 一、核心能力

### 1. OpenAI Compatible Proxy

对客户端统一暴露 OpenAI 兼容接口，底层转发到真实模型供应商：

```text
GET  /v1/models
POST /v1/chat/completions   # 支持 stream = true，流式透传
POST /v1/embeddings         # 可选
```

- 客户端只需配置 TokenLimit 的 Base URL 与 API Key，即可切换任意受支持的大模型供应商；
- **流式响应边收边转**：首 Token 延迟新增目标 < 100ms，打字机效果无缝透传；
- OpenAI 兼容系供应商（DeepSeek / OpenAI / 通义 / 智谱 / Kimi 等）直接 HTTP 透传，仅替换认证 Header；非 OpenAI 兼容系（Anthropic 等）通过 Provider Adapter 转换协议。

### 2. 配额控制与事前拦截

采用**简单计数器模型**，调用真实大模型 **之前** 直接拦截，避免产生额外费用：

```text
调用前：读取 Redis used，used >= limit ？拦截 : 放行
调用后：used += actual_tokens（厂商返回的真实值）
```

支持的多级配额对象、类型与周期：

```text
配额对象：TEAM（团队预算池） / USER（个人额度）
配额类型：TOKEN（Token 数量） / COST（费用金额） / REQUEST_COUNT（请求次数）
配额周期：DAY / MONTH / TOTAL
```

User 级 `quota_mode`（默认 `PERSONAL_FIRST_THEN_TEAM`）：

```text
PERSONAL_ONLY              仅使用个人额度
TEAM_ONLY                  仅使用团队额度
PERSONAL_FIRST_THEN_TEAM   个人优先，不足时团队兜底
```

缓冲阈值：`soft_limit = limit × 告警阈值（默认 80%）` 触发告警，`hard_limit = limit` 硬拦截。本次超额允许完成，下次调用基于更新后的 used 拦截。

### 3. Token 预估与异常检测

统一使用 **jtokkit** 作为 token 预估基准：

```text
请求发出时：估算 prompt_tokens
流式中断时：估算已转发的 completion 内容
厂商未返回 usage 时：估算 prompt + completion
```

预估值与厂商真实值偏差超过阈值（默认 50%）时，标记 `anomaly_detected = 1` 并写入审计日志，实现**异常计费检测**。

| 场景 | usage_source | status | 配额统计依据 |
| :--- | :--- | :--- | :--- |
| 正常完成，厂商返回 usage | PROVIDER | SUCCESS | 厂商真实值 |
| 流式中断 | ESTIMATED | INTERRUPTED | jtokkit 预估值 |
| 厂商未返回 usage | ESTIMATED | SUCCESS | jtokkit 预估值 |
| 调用报错 | ESTIMATED | ERROR | jtokkit 预估值（如有） |
| 厂商返回异常 usage | PROVIDER | SUCCESS | 厂商真实值（标记异常） |

### 4. Provider Credential 托管

真实大模型供应商 API Key 由 TokenLimit **统一加密托管**：

- 客户端不直接持有真实供应商 API Key，只持有 TokenLimit API Key；
- Provider Credential 加密存储、不展示明文、不出现在日志中，修改必须审计，禁用实时生效；
- 密钥查找优先级：Team 专属 Credential → 全局（GLOBAL）Credential → `PROVIDER_NOT_FOUND`。

### 5. API Key 生命周期管理

```text
创建 → 启用 → 禁用 / 过期 / 删除
```

- API Key 创建时自动生成 access_key 与 secret，**secret 仅创建时显示一次**，库中只存 HMAC-SHA256 哈希（服务端 pepper 密钥参与，防离线碰撞）；
- 可配置允许模型与过期时间；禁用后实时失效；
- User / Team 被禁用时，其下所有 API Key 自动失效。

### 6. 用量统计与审计

- **用量统计**：支持按 Team / User / API Key / Model / Provider / Time / consumeFrom / usage_source / status 等维度统计 request_count、prompt/completion/total_tokens、estimated_total_tokens、cost、成功率、拦截数、中断数、异常数、延迟等指标；
- **成本归属**：API Key 是调用入口，User 是成本责任人，Team 是成本中心，所有调用成本最终归属到 Team；
- **审计日志**：记录 LOGIN、TEAM/USER/API_KEY/QUOTA/PROVIDER_CREDENTIAL 变更、QUOTA_BLOCK、USAGE_ANOMALY 等 15+ 类事件。

---

## 二、核心概念模型

```text
Team（成本中心 / 预算池）
└── User（成本责任人 / 登录账号）
    └── API Key（调用凭证）

Team Model Policy（Team 可用的模型列表 + 绑定的 Provider Credential）
Provider Credential（真实大模型 API Key，加密托管）
```

| 概念 | 说明 | 示例 |
|---|---|---|
| Team | 成本中心、预算池与管理边界（部门 / 项目组 / 应用 / 客户） | team-rd、team-cs、app-code-assistant |
| User | 登录账号与成本责任人（员工 / 机器人 / 服务账号） | zhangsan、bot-ci、service-harness |
| API Key | 调用 TokenLimit Proxy 的访问凭证，绑定 Team + User | tl_xxx（access_key + secret） |
| Provider Credential | 真实供应商 API Key，TokenLimit 统一加密托管 | deepseek-company-main |
| Team Model Policy | 定义 Team 允许使用的模型及对应的 Credential | deepseek-chat → deepseek-company-main |

---

## 三、角色与权限

第一版只保留三个角色：

```text
ADMIN       系统管理员
TEAM_ADMIN  Team 管理员
USER        普通用户
```

| 功能 | ADMIN | TEAM_ADMIN | USER |
|---|---:|---:|---:|
| 登录控制台 | ✅ | ✅ | ✅ |
| 管理所有 Team / 创建 Team | ✅ | ❌ | ❌ |
| 管理 Provider Credential / Team Model Policy | ✅ | ❌ | ❌ |
| 配置系统 Gateway URL / 全局参数 | ✅ | ❌ | ❌ |
| 管理本 Team User、分配个人额度、设置 quota_mode | ✅ | ✅ | ❌ |
| 查看 Team 成本 | ✅ | ✅ | ❌ |
| 创建自己的 API Key | ✅ | ✅ | ✅ |
| 查看个人额度 / 用量 | ✅ | ✅ | ✅ |
| 查看全局用量 | ✅ | ❌ | ❌ |

---

## 四、整体架构

```text
[ 数据面 Data Plane ]

Cursor / DeepSeek Harness / AI Client / 业务应用
        │  OpenAI Compatible API（Authorization: Bearer <api_key>）
        ▼
+-------------------------------------------------------+
|              TokenLimit Gateway (Spring Boot 3)        |
|   1. API Key 鉴权 → 解析 Team / User                   |
|   2. 校验 API Key 状态 / 过期时间                       |
|   3. 校验 Team Model Policy（模型是否允许）              |
|   4. jtokkit 预估 prompt_tokens                        |
|   5. 配额检查（读 Redis used）                          |
|   6. 查找 Provider Credential                          |
|   7. 转发请求到真实模型供应商（流式透传）                 |
|   8. 采集真实 Usage → 写入 MySQL usage_log              |
|   9. 更新 Redis used                                    |
+-------------------+-------------------+
        │                           │
        ▼                           ▼
+----------------+          +----------------+
| MySQL 8        |          | Redis 6+      |
| 持久化事实来源  |          | 实时配额缓存   |
+----------------+          +----------------+
        ▲
        │ HTTP（管控面 /api/v1/admin、/api/v1/my）
        │
+-------------------------------------------------------+
|                  TokenLimit Console                   |
|           Web 控制台（Vue 3 + Element Plus）            |
|   Dashboard / Team / Provider / User / API Key /      |
|   Quota / Usage / Audit / Quick Start / Settings       |
+-------------------------------------------------------+
```

### 核心模块

| 组件 | 职责 |
| --- | --- |
| `tokenlimit-java/tokenlimit-server` | 网关 + 服务端：API Key 鉴权、配额检查、请求转发、用量统计、审计、管理 API |
| `tokenlimit-java/tokenlimit-common` | 共享 DTO、枚举、错误码、工具类 |
| `tokenlimit-java/tokenlimit-client-java` | Java 客户端 SDK（check / report 协议） |
| `console` | 管理控制台前端（Vue 3 / TypeScript / Vite / Element Plus） |

### 技术选型

- **后端**: Java 21 / Spring Boot 3.x / MyBatis-Plus / Redis
- **Token 预估**: jtokkit
- **前端**: Vue 3 / TypeScript / Vite / Element Plus
- **存储**: MySQL 8（配置与用量持久化，事实来源）+ Redis（实时配额缓存）

---

## 五、快速开始

### 环境要求

```text
JDK 21
Maven 3.8+
Node.js 18+
MySQL 8.0+
Redis 6+
```

### 1. 初始化数据库

```bash
# 初始化数据库与表结构（tokenlimit 库，PRD V5.0）
mysql -uroot -p < deploy/mysql/init/init.sql
```

### 2. 启动服务端

```bash
cd tokenlimit-java
mvn spring-boot:run -pl tokenlimit-server
# 默认端口 8080，健康检查：GET /api/v1/health
```

### 3. 启动管理控制台

```bash
cd console
npm install
npm run dev
# 默认访问 http://localhost:5173
```

### 4. 一键部署（Docker Compose）

```bash
docker compose -f deploy/docker-compose.yml up -d
```

---

## 六、客户端接入

客户端只需把 Base URL 指向 TokenLimit 网关、使用 TokenLimit API Key，即可完成接入。

API Key 为**两段式凭证**：`accessKey`（公开标识 `tl_ak_xxx`）+ `secret`（机密 `sk_tl_xxx`，仅创建/重置时显示一次）。为兼容只支持单个 API Key 的客户端（如 Cursor），将两段用**冒号拼接**为一个字符串填入；网关按第一个冒号拆分后双向校验。

### 1. Cursor

```text
Model Provider: OpenAI
Base URL:       http://<tokenlimit-host>:8080/v1
API Key:        tl_ak_xxxxxxxx:sk_tl_xxxxxxxx...   # access_key 与 secret 用冒号拼接
```

### 2. DeepSeek Harness

```text
Base URL: http://<tokenlimit-host>:8080/v1
API Key:  tl_ak_xxxxxxxx:sk_tl_xxxxxxxx...   # 同上，两段式拼接
```

### 3. cURL

```bash
curl http://<tokenlimit-host>:8080/v1/chat/completions \
  -H "Authorization: Bearer tl_ak_xxxxxxxx:sk_tl_xxxxxxxx..." \
  -H "Content-Type: application/json" \
  -d '{
    "model": "deepseek-chat",
    "messages": [{"role": "user", "content": "Hello"}],
    "stream": true
  }'
```

### 4. Java Client SDK（check / report 协议）

```java
TokenLimitClient client = new TokenLimitClient(
        TokenLimitConfig.builder("http://127.0.0.1:8080")
                .apiKey("tl_ak_xxxxxxxx")       // accessKey
                .secret("sk_tl_xxxxxxxx...")    // secret，未配置则仅发送 Bearer <access_key>
                .build());

// 1. 调用大模型前：配额检查（只读 Redis，不预扣）
CheckResult result = client.check("deepseek-chat", 1000); // model + estimatedTokens
if (!result.isAllowed()) {
    throw new TokenLimitException(result.getReason());
}

// 2. 调用真实大模型 API
LlmResponse response = llmService.chat(prompt);

// 3. 上报真实消耗（写入 usage_log 并累加配额）
client.report(result.getTraceId(), "deepseek-chat", "DEEPSEEK",
        response.getPromptTokens(), response.getCompletionTokens(),
        response.getTotalTokens(), "SUCCESS", latencyMs);
```

> 说明：网关模式下客户端只需配置 API Key 即可，无需关心供应商真实密钥；check / report 协议为 SDK 接入方（非 OpenAI 兼容客户端）提供。

---

## 七、API 概览

### Proxy API（数据面，OpenAI 兼容）

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/v1/models` | 列出可用模型 |
| `POST` | `/v1/chat/completions` | Chat 补全（支持 stream） |
| `POST` | `/v1/embeddings` | Embedding（可选） |

鉴权方式：

```http
Authorization: Bearer <tokenlimit_api_key>
```

客户端不需要也不允许传入 Team / User 信息，服务端根据 API Key 自动解析 `API Key → Team + User`。

配额不足时返回：

```json
HTTP 429 Too Many Requests
{
  "error": {
    "message": "TokenLimit quota exceeded: team monthly budget is exhausted",
    "type": "tokenlimit_quota_exceeded",
    "code": "TEAM_QUOTA_EXCEEDED"
  }
}
```

### Client API（SDK 数据面）

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/v1/health` | 健康检查 |
| `POST` | `/api/v1/client/check` | 配额检查（只读 Redis used，不预扣，返回 traceId） |
| `POST` | `/api/v1/client/report` | 用量上报（写入 usage_log、更新 Redis used） |

### Admin API（管控面，`/api/v1/admin`）

| 模块 | 端点 |
|---|---|
| 认证 | `POST /auth/login`、`GET /auth/profile`、`POST /auth/change-password`、`POST /auth/logout` |
| 大盘 | `GET /dashboard/stats`、`GET /dashboard/trend?days=7`、`GET /dashboard/top-teams?topN=5` |
| Team | `GET/POST /teams`、`GET/PUT/DELETE /teams/{id}`、`PUT /teams/{id}/status` |
| User | `GET/POST /users`、`GET/PUT/DELETE /users/{id}`、`PUT /users/{id}/status`、`POST /users/{id}/reset-password` |
| API Key | `GET/POST /api-keys`、`GET/PUT/DELETE /api-keys/{id}`、`POST /api-keys/{id}/reset-secret`、`PUT /api-keys/{id}/status` |
| 配额规则 | `GET/POST /quota-rules`、`GET/PUT/DELETE /quota-rules/{id}`、`PUT /quota-rules/{id}/status` |
| Provider | `GET/POST /providers`、`GET/PUT/DELETE /providers/{id}`、`PUT /providers/{id}/status`、Provider Credential CRUD |
| 模型策略 | `GET/POST /model-policies`、`GET/PUT/DELETE /model-policies/{id}`、`PUT /model-policies/{id}/status` |
| 用量 | `GET /usages`、`GET /usages/{id}` |
| 审计 | `GET /audits`、`GET /audits/{id}` |
| 系统设置 | `GET/POST /settings` |
| 元数据 | `GET /meta/teams`、`GET /meta/api-keys`、`GET /meta/users`、`GET /meta/all` |

### My API（`/api/v1/my`，个人中心）

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/overview` | 我的概览 |
| `GET` | `/quota` | 我的额度 |
| `GET` | `/usage` | 我的用量（分页） |
| `GET` | `/transactions` | 我的流水（分页） |
| `GET` | `/bills` | 我的账单 |
| `GET` | `/api-keys` | 我的 API Key（分页） |

---

## 八、项目目录结构

```text
token-limit-project/
├── design/                            # 产品设计（PRD V5.0 / V5.0 Console 原型）
│   ├── TokenLimit 产品需求文档 PRD-V5.md
│   └── TokenLimit V5.0 Console.html
├── docs/                              # 项目文档
│   ├── architecture.md                # 架构设计
│   ├── api.md                         # API 文档
│   ├── quickstart.md                  # 快速开始
│   ├── quota-model.md                 # 配额模型
│   ├── reconciliation.md              # 对账模块设计（V4 遗留）
│   └── product-design.md              # 产品设计
├── tokenlimit-java/                  # Java Maven 工程（V5 核心实现）
│   ├── pom.xml
│   ├── tokenlimit-common/             # 公共模块（DTO/枚举/错误码）
│   ├── tokenlimit-server/             # 网关 + 后台服务
│   └── tokenlimit-client-java/        # Java 客户端 SDK
├── console/                           # Web 控制台前端（Vue 3）
│   ├── package.json
│   └── src/
│       ├── api/  components/  layouts/  router/  stores/  views/  utils/
├── python/                            # Python SDK（V4 遗留，V5 不维护）
├── deploy/                            # 部署
│   ├── docker-compose.yml
│   ├── docker/
│   ├── mysql/init/init.sql            # 数据库初始化脚本（PRD V5.0）
│   └── redis/
├── examples/                          # 示例工程
│   ├── java-demo/
│   ├── springboot-demo/
│   └── python-demo/
├── README.md
└── LICENSE
```

---

## 九、数据库设计

数据库 `tokenlimit`（MySQL 8，utf8mb4），核心表：

```text
tl_team                  团队（成本中心 / 预算池）
tl_user                  用户（3 角色、quota_mode、密码哈希）
tl_api_key               API Key（access_key + secret_hash + allowed_models + 过期时间）
tl_provider              大模型供应商（deepseek / openai / anthropic / ...）
tl_provider_credential   供应商密钥托管（加密存储、GLOBAL / TEAM 作用域）
tl_team_model_policy     Team 可用模型策略（模型 → Provider Credential 映射）
tl_quota_rule            配额规则（targetType / limitType / period / limitValue / status）
tl_usage_log             用量日志（预估值 + 真实值 + usage_source + 异常标记）
tl_audit_log             审计日志（操作事件与拦截日志）
```

配额模型（Redis 数据结构）：

```text
tokenlimit:quota:used:team:{team_code}:{limit_type}:{period}:{timeKey}
tokenlimit:quota:used:user:{user_code}:{limit_type}:{period}:{timeKey}
```

```text
示例：
tokenlimit:quota:used:team:team-rd:TOKEN:DAY:20260813
tokenlimit:quota:used:user:zhangsan:TOKEN:DAY:20260813
```

数据一致性：

```text
MySQL 是事实来源，持久化所有用量记录。
Redis 是实时缓存，用于高性能配额检查。
写入顺序：先写 MySQL，再更新 Redis。
配额检查：读 Redis；历史查询 / 报表：读 MySQL。
Redis 数据丢失：从 MySQL 重新聚合恢复。
```

---

## 十、第一版范围与 Roadmap

### V5.0（第一版）

```text
✅ OpenAI Compatible Proxy（/v1/chat/completions、/v1/models）
✅ API Key 鉴权与生命周期管理
✅ Team / User 配额控制（简单计数器模型，事前拦截）
✅ Provider Credential 托管 + Team Model Policy
✅ jtokkit Token 预估与异常检测
✅ 用量统计、成本归属、审计日志
✅ 管理控制台（Dashboard / Team / Provider / User / API Key / Quota / Usage / Audit / Quick Start / Settings）
```

明确不做：

```text
不做供应商账单自动同步
不做复杂对账引擎
不做智能模型路由
不做多集群部署
不做 SSO / MFA
不做 RPM / TPM 限流
不做 API Key 级独立配额
不做预估冻结结算模型
```

### Roadmap

```text
V1.1  基础告警（预算告警、异常偏差告警）、用量报表增强
V1.2  RPM / TPM 限流、API Key 级配额、模型白名单、审计日志增强
V2.0  供应商账单导入、账单对账、异常计费分析、企业 AI FinOps 报表
```

---

## 十一、项目状态

### 已完成（V5.0 全部落地）

- [x] 产品定位与核心概念设计（PRD V5.0）；
- [x] Java Maven 多模块工程（Java 21 / Spring Boot 3 / MyBatis-Plus / Redis / jtokkit），`mvn compile` 通过；
- [x] 简单计数器配额模型（check 只读 Redis 不预扣，report 累加真实值，check/report 上下文通过 traceId 关联）；
- [x] OpenAI Compatible Proxy（`/v1/chat/completions` / `/v1/models` / `/v1/embeddings`，流式透传）：
  - API Key 鉴权（INVALID_API_KEY / API_KEY_DISABLED / API_KEY_EXPIRED）；
  - Team Model Policy 模型策略校验（MODEL_NOT_ALLOWED，`/v1/models` 按 Team 返回可用模型）；
  - jtokkit 预估 → 配额 check → Provider 凭证解析 → 上游转发；
  - report 结算：厂商 usage 优先，缺失/中断按预估结算（usage_source + 异常检测）；
  - OpenAI 兼容错误响应（429 TEAM/USER_QUOTA_EXCEEDED、401/403 语义化 code 等）；
- [x] API Key 生命周期管理（ENABLED / DISABLED / EXPIRED / REVOKED）；
- [x] Provider Credential 托管与 Team Model Policy；
- [x] Token 预估（jtokkit）与异常检测（偏差 > 阈值标记 anomaly）；
- [x] 3 角色登录认证（ADMIN / TEAM_ADMIN / USER）；
- [x] 管理端 REST 接口（Team/User/ApiKey/QuotaRule/Provider/ModelPolicy/Usage/Audit/Dashboard/Settings/Meta/Auth）；
- [x] 个人中心（概览 / 额度 / 用量 / 流水 / 账单 / API Key）；
- [x] Console 前端（Vue 3 + Element Plus）；
- [x] `deploy/mysql/init/init.sql` 同步 V5 结构（tl_quota_rule 去 rule_code/priority 加 status；tl_usage_log 增加 estimated_* / usage_source / anomaly_* 列；设置项适配简单计数器模型）。

### 后续联调（需启动 MySQL / Redis / Server）

- [ ] 启动 MySQL / Redis 执行 `init.sql`，启动 `tokenlimit-server` 后进行前后端联调验证。

---

## 十二、License

```text
Apache License 2.0
```

详见 [LICENSE](./LICENSE)。
