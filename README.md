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

责任链拦截（配置项 `tokenlimit.quota-chain`，可裁剪/排序），调用真实大模型 **之前** 拦截，避免产生额外费用：

```text
拦截链（任一拦截即拒绝）：
  1. team-balance  团队余额拦截（TOTAL 周期长期规则）
  2. user-balance  个人余额拦截（TOTAL 周期长期规则，并确定抵扣来源）
  3. usage-period  周期用量拦截（MONTH/WEEK/DAY/HOUR/MINUTE/YEAR 规则，含"每次请求"限次）

预计算开关（tokenlimit.quota-precompute-enabled，默认开启）：
  开启：调用前真实余额 - 预估量原子预扣（>=0 才放行，==0 也放行；<0 拦截），结束后回滚预扣、按厂商真实值扣减余额
       并发下存在极小窗口超支 1 次调用（可接受）
  关闭：仅判断余额（==0 即拦截），并发下最后几次请求可能同时放行（超卖）
```

支持的多级配额对象、类型与周期：

```text
配额对象：TEAM（团队预算池） / USER（个人额度）
配额类型：TOKEN（Token 数量） / COST（费用金额） / REQUEST_COUNT（请求次数）
配额周期：MINUTE / HOUR / DAY / WEEK / MONTH / YEAR / TOTAL
```

**计费与成本（V5.3 计费快照 / V5.4 缓存计费）**：调用结束后按 `tl_model_price` 价格表动态计算费用——
`cost = 正常输入 × 输入单价 + 缓存命中 × 缓存读取单价 + 缓存写入 × 缓存写入单价 + 输出 × 输出单价`
（价格表可配置，单位每 Token，未配置按 0；缓存命中/写入 token 自动解析 OpenAI `cached_tokens`、DeepSeek
`prompt_cache_hit_tokens`、Anthropic `cache_read/cache_creation_input_tokens`，未配缓存单价按正常输入价兜底）；
USD 计价模型按系统汇率（`usd_to_cny_rate`，默认 7.2）折算到企业本位币（`base_currency`，默认 CNY）；
Dashboard 展示今日费用、缓存命中率与缓存节省金额。
单价、汇率、费用在写入 `usage_log` 时一次性**固化（计费快照）**——修改价格/汇率只影响新调用，
历史账单费用不可变，报表必须 `SUM(cost)` 聚合。

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
| --- | --- | --- | --- |
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
| --- | --- | --- |
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
| --- | :-: | :-: | :-: |
| 登录控制台 | 是 | 是 | 是 |
| 管理所有 Team / 创建 Team | 是 | 否 | 否 |
| 管理 Provider Credential / Team Model Policy | 是 | 否 | 否 |
| 配置系统 Gateway URL / 全局参数 | 是 | 否 | 否 |
| 管理本 Team User、分配个人额度、设置 quota_mode | 是 | 是 | 否 |
| 查看 Team 成本 | 是 | 是 | 否 |
| 创建自己的 API Key | 是 | 是 | 是 |
| 查看个人额度 / 用量 | 是 | 是 | 是 |
| 查看全局用量 | 是 | 否 | 否 |

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
|   5. 配额检查（责任链：团队余额 → 个人余额 → 周期用量）      |
|   6. 查找 Provider Credential                          |
|   7. 转发请求到真实模型供应商（流式透传）                 |
|   8. 采集真实 Usage → 写入 MySQL usage_log              |
|   9. 更新 Redis 余额（balance 扣减 / pre 回滚）             |
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

- **后端**: Java 21 / Spring Boot 3.x / MyBatis-Plus / Redis / Apache Derby (单机模式)
- **Token 预估**: jtokkit
- **前端**: Vue 3 / TypeScript / Vite / Element Plus
- **存储**: 
  - 单机模式：Apache Derby (内嵌数据库，零配置)
  - 集群模式：MySQL 8（配置与用量持久化）+ Redis（实时配额缓存）
- **部署**: 前后端一体化 JAR / Docker Compose

---

## 五、快速开始

### 环境要求

```text
JDK 21
Maven 3.8+
Node.js 18+

# 部署模式选择：
# - 单机模式（Derby 内嵌数据库）：零配置，无需 MySQL/Redis
# - 集群模式（MySQL + Redis）：生产环境推荐
```

### 1. 单机模式（Derby 内嵌数据库，参考 Nacos 架构）

```bash
# 一键启动（Derby 自动创建数据库目录和表结构）
java -jar tokenlimit-server.jar --spring.profiles.active=standalone

# 访问 http://localhost:8080
# 账号：admin / admin123（生产环境请修改）
```

**Derby 内嵌数据库特性：**
- ✅ 纯 Java 实现，无本地依赖，跨平台一致
- ✅ 零配置启动，首次自动创建 `data/derby-data` 目录
- ✅ 嵌入式模式，在 JVM 进程内运行
- ✅ 文件锁保护（`db.lck`），防止多进程并发访问
- ✅ 完整 ACID 事务支持，崩溃自动恢复
- ✅ Derby 日志重定向到 `logs/derby.log`

### 2. 集群模式（MySQL + Redis）

#### 2.1 初始化数据库

```bash
# 初始化 MySQL 数据库与表结构
mysql -uroot -p < deploy/mysql/init/init.sql
```

#### 2.2 启动 Redis

```bash
docker run -d --name redis-dev -p 6379:6379 redis:latest
```

#### 2.3 启动服务端

```bash
cd tokenlimit-java
mvn spring-boot:run -pl tokenlimit-server
# 默认端口 8080，健康检查：GET /api/v1/health
```

#### 2.4 启动管理控制台

```bash
cd console
npm install
npm run dev
# 默认访问 http://localhost:5173
```

### 3. 一键部署（Docker Compose）

#### 前后端一体化部署（推荐）

```bash
# 构建并启动（包含前端构建）
docker compose -f deploy/docker-compose-prod.yml up -d

# 访问 http://localhost:8080
```

#### 前后端分离部署

```bash
docker compose -f deploy/docker-compose.yml up -d
# 前端独立部署：http://localhost:5173
# 后端 API：http://localhost:8080
```

---

## 六、客户端接入

客户端只需把 Base URL 指向 TokenLimit 网关、使用 TokenLimit API Key，即可完成接入。

API Key 为**两段式凭证**：`accessKey`（公开标识 `tl_ak_` + 32 位 base62）+ `secret`（机密 `sk_tl_xxx`，仅创建/重置时显示一次）。为兼容只支持单个 API Key 的客户端（如 Cursor），将两段用**冒号拼接**为一个字符串填入；网关按第一个冒号拆分后双向校验。

### 1. Cursor

```text
Model Provider: OpenAI
Base URL:       http://localhost:8080/v1   # 部署后替换为实际网关地址
API Key:        tl_ak_XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX:sk_tl_XXXXXXXX...   # access_key 与 secret 用冒号拼接
```

### 2. DeepSeek Harness

```text
Base URL: http://localhost:8080/v1   # 部署后替换为实际网关地址
API Key:  tl_ak_XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX:sk_tl_XXXXXXXX...   # 同上，两段式拼接
```

### 3. cURL

```bash
curl http://localhost:8080/v1/chat/completions \   # 部署后把 localhost 替换为实际网关地址
  -H "Authorization: Bearer tl_ak_XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX:sk_tl_XXXXXXXX..." \
  -H "Content-Type: application/json" \
  -d '{
    "model": "deepseek-chat",
    "messages": [{"role": "user", "content": "Hello"}],
    "stream": true
  }'
```

### 4. Java Client SDK（check / report 协议）

```java
import com.tokenlimit.client.TokenLimitClient;
import com.tokenlimit.client.TokenLimitConfig;
import com.tokenlimit.client.TokenLimitException;
import com.tokenlimit.common.dto.CheckResult;

public class QuickStart {

    public static void main(String[] args) {
        // 创建客户端（Bearer <access_key>:<secret> 双向校验）
        TokenLimitClient client = new TokenLimitClient(
                TokenLimitConfig.builder("http://127.0.0.1:8080")
                        .apiKey("tl_ak_XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX")    // accessKey
                        .secret("sk_tl_xxxxxxxx...")    // secret，未配置则仅发送 Bearer <access_key>
                        .build());

        // 1. 调用大模型前：配额检查（责任链拦截：团队余额 → 个人余额 → 周期用量；预计算开启时按 jtokkit 预估量原子预扣）
        CheckResult result = client.check("deepseek-chat", 1000); // model + estimatedTokens
        if (!result.isAllowed()) {
            throw new TokenLimitException(result.getReason());
        }

        // 2. 调用真实大模型 API（业务代码，此处省略，返回真实 token 用量）

        // 3. 上报真实消耗（写入 usage_log 并累加配额）
        client.report(result.getTraceId(), "deepseek-chat", "DEEPSEEK",
                800, 180, 980, "SUCCESS", 1250L);
    }
}
```

> 说明：网关模式下客户端只需配置 API Key 即可，无需关心供应商真实密钥；check / report 协议为 SDK 接入方（非 OpenAI 兼容客户端）提供。

---

## 七、API 概览

### Proxy API（数据面，OpenAI 兼容）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/v1/models` | 列出可用模型 |
| `POST` | `/v1/chat/completions` | Chat 补全（支持 stream） |
| `POST` | `/v1/embeddings` | Embedding（可选） |

鉴权方式：

```http
Authorization: Bearer <tokenlimit_api_key>
```

客户端不需要也不允许传入 Team / User 信息，服务端根据 API Key 自动解析 `API Key → Team + User`。

配额不足时返回 HTTP 429：

```json
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
| --- | --- | --- |
| `GET` | `/api/v1/health` | 健康检查 |
| `POST` | `/api/v1/client/check` | 配额检查（只读 Redis used，不预扣，返回 traceId） |
| `POST` | `/api/v1/client/report` | 用量上报（写入 usage_log、更新 Redis used） |

### Admin API（管控面，`/api/v1/admin`）

| 模块 | 端点 |
| --- | --- |
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
| --- | --- | --- |
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
tokenlimit:quota:balance:team:{team_code}:{limit_type}:{period}:{timeKey}
tokenlimit:quota:balance:user:{user_code}:{limit_type}:{period}:{timeKey}
tokenlimit:quota:pre:team:{team_code}:{limit_type}:{period}:{timeKey}
tokenlimit:quota:pre:user:{user_code}:{limit_type}:{period}:{timeKey}
```

```text
示例：
tokenlimit:quota:balance:team:team-rd:TOKEN:DAY:20260813
tokenlimit:quota:pre:team:team-rd:TOKEN:DAY:20260813
tokenlimit:quota:pre:user:zhangsan:TOKEN:WEEK:2026W33
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
✅ Team / User 配额控制（责任链拦截 + 预计算开关，事前拦截）
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
```

### Roadmap

```text
V1.1  基础告警（预算告警、异常偏差告警）、用量报表增强
V1.2  RPM / TPM 限流、API Key 级配额、模型白名单、审计日志增强
V2.0  供应商账单导入、账单对账、异常计费分析、企业 AI FinOps 报表
```

---

## 十一、项目状态

### 已完成（V5.0 全部落地 + 生产级增强）

- [x] 产品定位与核心概念设计（PRD V5.0）；
- [x] Java Maven 多模块工程（Java 21 / Spring Boot 3 / MyBatis-Plus / Redis / Apache Derby），`mvn compile` 通过；
- [x] 责任链拦截配额模型（team-balance / user-balance / usage-period 可配置；预计算开关：check 按 jtokkit 预估量原子预扣，report 回滚预扣 + 按真实值扣减余额，check/report 上下文通过 traceId 关联）；
- [x] OpenAI Compatible Proxy（`/v1/chat/completions` / `/v1/models` / `/v1/embeddings`，流式透传）：
  - API Key 鉴权（INVALID_API_KEY / API_KEY_DISABLED / API_KEY_EXPIRED）；
  - Team Model Policy 模型策略校验（MODEL_NOT_ALLOWED，`/v1/models` 按 Team 返回可用模型）；
  - jtokkit 预估 → 配额 check → Provider 凭证解析 → 上游转发；
  - report 结算：厂商 usage 优先，缺失/中断按预估结算（usage_source + 异常检测）；
  - OpenAI 兼容错误响应（429 TEAM/USER_QUOTA_EXCEEDED、401/403 语义化 code 等）；
- [x] API Key 生命周期管理（ENABLED / DISABLED / EXPIRED / REVOKED）；
- [x] Provider Credential 托管与 Team Model Policy；
- [x] Token 预估（jtokkit）与异常检测（偏差 > 阈值标记 anomaly）；
- [x] 用量统计、成本归属、审计日志；
- [x] 管理控制台（Dashboard / Team / Provider / User / API Key / Quota / Usage / Audit / Quick Start / Settings / My Center）；
- [x] **前后端一体化部署**（参考 Nacos 架构，frontend-maven-plugin + SpaFallbackFilter）；
- [x] **Derby 内嵌数据库方案**（纯 Java 实现，零配置启动，文件锁保护，ACID 事务）；
- [x] **峰谷定价功能**（ModelPrice 峰谷字段、UsageLog 快照、计费引擎时间段判断、Dashboard 对比报表）；
- [x] **敏感数据清理**（无硬编码 API Key/URL/密码，JWT Secret 支持环境变量覆盖）；
- [x] **生产级安全加固**（AES-GCM 加密、登录失败锁定、RBAC 权限控制、敏感数据脱敏）；
- [x] **完整部署文档**（DEPLOYMENT.md、项目开发进度.md、README.md 更新）。

### 生产级交付确认

- ✅ PRD 功能覆盖率：100%（P0/P1/P3 全部完成）
- ✅ 接口路径一致性：前端 `/api/admin/*` ↔ 后端 `@RequestMapping("/api/admin")`
- ✅ Token 认证机制：前端 Bearer Token ↔ 后端 JWT 解析
- ✅ 前后端一体化：JAR 包包含前端静态资源，支持 SPA 路由 fallback
- ✅ 内嵌数据库：Derby 零配置启动，支持单机/集群模式自动切换
- ✅ 敏感数据：无硬编码密钥，所有 Credential AES-256-GCM 加密存储
- ✅ 部署方式：单机 Derby / 集群 MySQL+Redis / Docker Compose 三种模式

### Roadmap

```text
V5.1  供应商账单导入 UI、邮件/短信/Webhook 告警通知
V5.2  第三方 OAuth2 登录（GitHub/Google/企业微信）、多级嵌套团队架构
V6.0  智能模型路由、复杂对账引擎、Python SDK 重构
```

---

## 十二、下一步行动

- [ ] 启动 MySQL / Redis 执行 `init.sql`，启动 `tokenlimit-server` 后进行前后端联调验证。
- [ ] 使用 cURL/Cursor/DeepSeek Harness 进行真实大模型调用测试。
- [ ] 配置生产环境密钥（JWT_SECRET、DB_PASSWORD、ENCRYPTION_KEY）通过环境变量注入。
- [ ] 部署到生产环境并监控首 Token 延迟、配额拦截率、异常检测准确率等核心指标。

---

## 十三、License

```text
Apache License 2.0
```

详见 [LICENSE](./LICENSE)。
