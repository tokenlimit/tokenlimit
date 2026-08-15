# TokenLimit

> 让每一次大模型调用都可计量、可限制、可审计、可对账。

TokenLimit 是一个面向企业内部大模型应用的 **Token 使用统计、配额限制、预算控制、审计与账单对账中心**（PRD V2.0）。

它解决企业在接入 OpenAI、Anthropic、阿里云百炼、通义千问、文心一言等大模型 API 时常见的治理问题：

- Token 消耗失控，账单爆炸；
- 应用或员工调用无预算约束；
- 部门、团队、个人成本无法分摊；
- 缺少事前拦截，只能事后看到高额账单；
- API Key 管理混乱，密钥泄露难以追踪；
- 供应商账单与自身调用记录不一致，难以审计和对账。

类似 Nacos 服务企业微服务治理，TokenLimit 服务企业大模型调用治理，定位为 **企业大模型 Token 治理中心**：Token 计量中心、Token 配额中心、Token 预算中心、Token 审计中心、Token 对账中心。

---

## 一、核心能力

### 1. Token 使用统计

记录每一次大模型调用的详细信息（调用时间、调用方、所属团队、用户、模型、Prompt/Completion/Total Tokens、耗时、状态），支持按以下维度统计：

```text
Namespace / Team / User / Model / Date
```

### 2. 配额限制与事前拦截

多层级配额控制，调用真实大模型 API **之前**直接拦截，避免产生额外费用：

```text
Team 级：部门预算 / 团队预算 / 应用限额 / 成本中心
User 级：员工限额 / 终端客户限额 / 机器人账号限额
```

支持的限制类型：

```text
Token 数量限制
费用金额限制
请求次数限制
RPM / TPM（每分钟请求数 / Token 数）
日限额 / 月限额 / 总预算
```

### 3. 预算控制

典型流程：

```text
业务应用发起大模型调用
        ↓
TokenLimit 检查配额（Bearer <access_key>:<secret> 双向校验）
        ↓
配额不足 → 直接拒绝（不产生费用）
配额充足 → 放行调用大模型
        ↓
记录真实 Token 消耗 → 修正配额并生成审计日志
```

### 4. 多维度治理模型

```text
Namespace
└── Team
    └── User
        └── API Key（强绑定 Namespace / Team / User）
```

| 概念 | 说明 | 示例 |
|---|---|---|
| Namespace | 环境或租户隔离，**仅做隔离不做配额**，不参与 Token 扣减 | prod、dev、test、tenant-001 |
| Team | 核心成本边界与预算池；Team 配额不足时该团队下所有调用被拒绝 | team-rd、team-cs、app-code-assistant |
| User | 最终使用者，可配置登录账号与角色 | 员工、终端客户、机器人账号 |
| API Key | 访问凭证，强绑定 namespace/team/user，Secret 仅创建/重置时返回一次 | tl_&lt;ns&gt;_ak_xxx（AccessKey + Secret） |

4 种角色：

```text
SUPER_ADMIN      系统管理员：管理 Namespace、Team、全局策略
NAMESPACE_ADMIN  Namespace 管理员：管理 Namespace 下的 Team 与成本
TEAM_ADMIN       Team 管理员：管理 Team 下 User、预算、配额和成本
USER             普通用户：查看个人额度、用量、账单，管理自己的 API Key
```

### 5. API Key 双向校验

客户端调用时携带 `Authorization: Bearer <access_key>:<secret>`：

- AccessKey 必须存在且状态为 `ACTIVE`；
- Secret 哈希必须与库中 `secret_hash` 一致（库中仅存 SHA-256 哈希，明文不落库）；
- 到期（`expire_at` 已过）自动将状态置为 `EXPIRED` 并拒绝调用；
- 上报（report）时校验 key 必须与预占（check）时的 key 一致，防止跨 key 上报。

### 6. 审计日志

记录关键操作与调用事件：

```text
登录成功/失败、API Key 创建/重置/停用/删除
配额规则变更、Token 超限拦截（QUOTA_BLOCK）
用户配额调整、异常调用行为
```

---

## 二、整体架构

```text
[ 管控面 Control Plane ]

+-------------------+       +-------------------------+
| TokenLimit Console| <---> |    TokenLimit Server    |
| Web 控制台         | HTTP  | 核心服务（Spring Boot 3）|
+-------------------+       +-----------+-------------+
                                        |
                           +------------+-------------+
                           | MySQL 持久化 | Redis 实时计算 |
                           +--------------------------+
                                        ^
[ 数据面 Data Plane ]                    |
+-------------------+       +-------------------------+
| 业务应用 Java      | <---> | TokenLimit Java Client  |
+-------------------+       +-------------------------+
+-------------------+       +-------------------------+
| 业务应用 Python    | <---> | TokenLimit Python Client|
+-------------------+       +-------------------------+
```

### 核心模块

| 组件 | 职责 |
| --- | --- |
| `tokenlimit-java/tokenlimit-server` | 服务端：配额管理、双向校验、用量统计、审计、管理 API |
| `tokenlimit-java/tokenlimit-common` | 共享 DTO、枚举、错误码、工具类 |
| `tokenlimit-java/tokenlimit-client-java` | Java 客户端 SDK |
| `python/tokenlimit` | Python 客户端 SDK |
| `console` | 管理控制台前端（Vue 3） |

### 技术选型

- **后端**: Java 21 / Spring Boot 3.x / MyBatis-Plus / Redis（令牌桶 + 预扣回补 Lua）
- **前端**: Vue 3 / TypeScript / Vite / Element Plus
- **Python SDK**: requests / cachetools
- **存储**: MySQL 8（配置与用量持久化）+ Redis（运行时配额状态）

---

## 三、快速开始

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
# 初始化数据库与表结构（tokenlimit 库，PRD V2.0）
mysql -uroot -p < deploy/mysql/init/init.sql
```

### 2. 启动服务端

```bash
cd java
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

## 四、客户端使用

### Java Client

```java
TokenLimitClient client = new TokenLimitClient(
        TokenLimitConfig.builder("http://127.0.0.1:8080")
                .apiKey("tl_prod_ak_zhangsan_demo") // 管理端创建的 access key
                .secret("your-secret-here")         // 创建/重置 API Key 时返回的 secret
                .build());

// 1. 调用大模型前：配额检查（Bearer <access_key>:<secret>）
CheckResult result = client.check("gpt-4o", 1000); // model + estimatedTokens
if (!result.isAllowed()) {
    throw new TokenLimitException(result.getReason());
}

// 2. 调用真实大模型 API
LlmResponse response = llmService.chat(prompt);

// 3. 上报真实消耗（修正配额并落库）
client.report(result.getTraceId(), "gpt-4o", "OPENAI",
        response.getPromptTokens(), response.getCompletionTokens(),
        response.getTotalTokens(), "SUCCESS", latencyMs);
```

### Python Client

```python
from tokenlimit import TokenLimitClient

client = TokenLimitClient(
    base_url="http://127.0.0.1:8080",
    api_key="tl_prod_ak_zhangsan_demo",   # access key
    api_secret="your-secret-here",        # secret，配置后发送 Bearer <access_key>:<secret>
)

# 消耗 token 配额
result = client.consume("prod", tokens=1000)
# 查询配额状态
status = client.query("prod")
```

环境变量：`TOKENLIMIT_API_KEY` / `TOKENLIMIT_API_SECRET` / `TOKENLIMIT_BASE_URL`。

> 说明：Python SDK 当前提供 V1 风格接口（consume/query），V2 check/report 协议对齐见 Roadmap。

### 鉴权协议

所有客户端接口通过 HTTP 头鉴权：

```text
Authorization: Bearer <access_key>:<secret>
```

- 未配置 secret 的旧客户端仍可发送 `Bearer <access_key>`（服务端按 secret 缺失拒绝）。
- Secret 仅创建 / 重置时返回一次，库中仅存 SHA-256 哈希。

---

## 五、API 概览

### 客户端 API（数据面）

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/v1/health` | 健康检查 |
| `POST` | `/api/v1/client/quota/check` | 配额检查（请求体 `{model, estimatedTokens}`，预扣减） |
| `POST` | `/api/v1/client/usage/report` | 用量上报（修正配额、写用量与审计日志） |

### 控制台 API（管控面，`/api/v1/admin`）

| 模块 | 端点 |
|---|---|
| 认证 | `POST /auth/login`、`GET /auth/profile`、`POST /auth/change-password`、`POST /auth/logout` |
| 大盘 | `GET /dashboard/stats`、`GET /dashboard/trend?days=7`、`GET /dashboard/top-teams?topN=5` |
| 命名空间 | `GET/POST /namespaces`、`GET/PUT/DELETE /namespaces/{id}`、`PUT /namespaces/{id}/status` |
| 团队 | `GET/POST /teams`、`GET/PUT/DELETE /teams/{id}`、`PUT /teams/{id}/status` |
| 用户 | `GET/POST /users`、`GET/PUT/DELETE /users/{id}`、`PUT /users/{id}/status`、`POST /users/{id}/reset-password` |
| API Key | `GET/POST /api-keys`、`GET/PUT/DELETE /api-keys/{id}`、`POST /api-keys/{id}/reset-secret`、`PUT /api-keys/{id}/status` |
| 配额规则 | `GET/POST /quota-rules`、`GET/PUT/DELETE /quota-rules/{id}`、`PUT /quota-rules/{id}/status` |
| 用量 | `GET /usages`、`GET /usages/{id}` |
| 审计 | `GET /audits`、`GET /audits/{id}` |
| 系统设置 | `GET/POST /settings` |
| 元数据 | `GET /meta/namespaces`、`GET /meta/teams`、`GET /meta/api-keys`、`GET /meta/users`、`GET /meta/all` |
| 对账任务 | `GET/POST /reconciles`、`GET/DELETE /reconciles/{id}`、`POST /reconciles/{id}/execute`、`GET /reconciles/{id}/items`、`PUT /reconciles/items/{id}/status`、`GET /reconciles/stats` |
| 供应商账单 | `GET/POST /vendor-bills`、`POST /vendor-bills/batch`、`GET/PUT/DELETE /vendor-bills/{id}` |
| 模型价格 | `GET/POST /model-prices`、`GET/PUT/DELETE /model-prices/{id}`、`PUT /model-prices/{id}/status` |

### 我的中心（`/api/v1/my`）

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/overview` | 我的概览 |
| `GET` | `/quota` | 我的额度 |
| `GET` | `/usage` | 我的用量（分页） |
| `GET` | `/transactions` | 我的流水（分页） |
| `GET` | `/bills` | 我的账单 |
| `GET` | `/api-keys` | 我的 API Key（分页） |

---

## 六、项目目录结构

```text
token-limit-project/
├── design/                            # 产品设计（PRD V2.0 / V3 原型）
│   ├── tokenlimit-prd-v2.md
│   └── tokenlimit-prd-v3.html
├── docs/                              # 项目文档
│   ├── architecture.md                # 架构设计
│   ├── api.md                         # API 文档
│   ├── quickstart.md                  # 快速开始
│   ├── quota-model.md                 # 配额模型
│   ├── reconciliation.md              # 对账模块设计
│   └── product-design.md              # 产品设计
├── tokenlimit-java/                  # Java Maven 工程
│   ├── pom.xml
│   ├── tokenlimit-common/             # 公共模块（DTO/枚举/错误码）
│   ├── tokenlimit-server/             # 后台服务
│   └── tokenlimit-client-java/        # Java 客户端 SDK
├── console/                           # Web 控制台前端（Vue 3）
│   ├── package.json
│   └── src/
│       ├── api/  components/  layouts/  router/  stores/  views/  utils/
├── python/                            # Python SDK
│   ├── pyproject.toml
│   ├── tokenlimit/                    # client / config / transport / local_cache ...
│   └── tests/
├── deploy/                            # 部署
│   ├── docker-compose.yml
│   ├── docker/
│   ├── mysql/init/init.sql            # 数据库初始化脚本（PRD V2.0）
│   └── redis/
├── examples/                          # 示例工程
│   ├── java-demo/
│   ├── springboot-demo/
│   └── python-demo/
├── README.md
└── LICENSE
```

---

## 七、数据库设计

数据库 `tokenlimit`（MySQL 8，utf8mb4），核心表：

```text
tl_namespace   命名空间（环境/租户隔离，不做配额）
tl_team        团队（成本中心、预算池，TEAM 配额层级）
tl_user        用户（4 角色、quota_mode、密码哈希）
tl_api_key     API Key（access_key + secret_hash + 状态 + 过期时间）
tl_quota_rule  配额规则（targetType/limitType/period/priority）
tl_usage_log      用量日志（每次调用的 token 明细）
tl_audit_log      审计日志（13 种审计事件）
tl_setting        系统设置
tl_model_price    模型价格（供应商/模型单价，成本核算基准）
tl_vendor_bill    供应商账单（账单导入，对账比对基准）
tl_reconcile_task 对账任务（任务状态与差异汇总）
tl_reconcile_item 对账明细（tokens/成本差异与差异率、争议状态）
```

配额模型：

```text
quota_mode（用户级抵扣顺序）：
  PERSONAL_ONLY              仅个人额度
  TEAM_ONLY                  仅团队额度
  PERSONAL_FIRST_THEN_TEAM   个人优先，超出后团队
```

---

## 八、项目状态

### 已完成

- [x] 产品定位与核心概念设计（PRD V2.0）；
- [x] Java Maven 多模块工程（Java 21 / Spring Boot 3 / MyBatis-Plus / Redis）；
- [x] MySQL 8 核心表结构（namespace/team/api_key/user/quota_rule/usage_log/audit_log/setting）；
- [x] Redis Lua 配额扣减引擎（原子性 / 周期桶 / 预扣回补）；
- [x] check / report 接口闭环（`Bearer <access_key>:<secret>` 双向校验）；
- [x] **API Key secret 双向校验**：secret 哈希比对、到期自动置为 EXPIRED、report 跨 key 校验；
- [x] 4 角色登录认证（SUPER_ADMIN / NAMESPACE_ADMIN / TEAM_ADMIN / USER，首次登录强制改密，登录失败锁定）；
- [x] 后端管理端 REST 接口（Namespace/Team/ApiKey/User/QuotaRule/Usage/Audit/Dashboard/Settings/Meta/Auth）；
- [x] 个人中心六页面（概览/额度/用量/账单/流水/API Key）；
- [x] 审计日志（13 种审计事件）；
- [x] Console 前端（Vue 3 + Element Plus，全 PRD 菜单页面 + 4 角色动态菜单）；
- [x] Java Client SDK 与示例（支持 secret 双向校验）；
- [x] Python Client SDK（支持 secret 字段，本地配额缓存降级）；
- [x] **对账模块**：模型价格管理、供应商账单导入、对账任务执行（与 `tl_usage_log` 聚合比对，计算 tokens/成本差异与差异率）、差异明细查询、争议状态流转、统计卡片。

### 进行中

- [ ] 前后端联调验证（需启动 MySQL / Redis / Server）；
- [ ] 对账能力增强：批量导入（Excel/CSV）、按团队维度对账、差异率阈值可配置。

### 规划中（Roadmap）

- [ ] Go Client / Node.js Client；
- [ ] OpenAI Compatible Proxy；
- [ ] 异步上报 / LangChain 集成示例。

---

## 九、典型调用流程

```text
业务应用（Java/Python Client）
        │  POST /api/v1/client/quota/check
        │  Authorization: Bearer <access_key>:<secret>
        ▼
TokenLimit Server
        │  1. 双向校验（access_key + secret_hash + 状态 + 到期）
        │  2. 检查 Team / User 配额（Redis 预扣减）
        ▼
配额不足 → HTTP 429 QUOTA_BLOCK（不调用大模型）
配额充足 → 返回 traceId
        ▼
业务应用调用真实大模型 API
        │  POST /api/v1/client/usage/report
        ▼
TokenLimit Server 修正配额、写入 tl_usage_log 与审计日志
```

---

## 十、License

```text
Apache License 2.0
```

详见 [LICENSE](./LICENSE)。
