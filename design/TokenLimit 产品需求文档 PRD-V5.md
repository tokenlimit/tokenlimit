# TokenLimit 产品需求文档 PRD

> TokenLimit：企业大模型 Token 预算网关与 AI FinOps 平台。

---

## 1. 文档信息

| 项目 | 内容 |
|---|---|
| 产品名称 | TokenLimit |
| 文档版本 | V5.0 |
| 文档状态 | 可开发 |
| 核心模型 | Team → User → API Key |
| 第一版目标 | 支持 Cursor / DeepSeek Harness 快速接入，实现 Token 预算控制 |
| 长期定位 | 企业 AI FinOps 与 Token 治理平台 |
| 仓库地址 | https://github.com/tokenlimit/tokenlimit |
| 许可证 | Apache License 2.0 |

---

## 2. 产品定位

### 2.1 一句话定位

```text
TokenLimit 是企业大模型调用的 Token 预算网关。
```

## 2.2 预算与配额体系 (V6.0 重构版)

### 核心设计理念
- **Team Admin (管理者)**：只关注**月度总预算**分配，不干预具体执行细节。
- **End User (使用者)**：拥有**自主控制权**，可在月度配额内，自行设置 API Key 的日限额、小时限额、单次请求限额。
- **去年度化**：废除"年度预算"，采用**滚动月度预算**机制，适应快速变化的业务需求。

### 三级管控模型

| 层级 | 控制主体 | 控制粒度 | 控制目标 | 配置方式 |
|------|----------|----------|----------|----------|
| **L1: Team 级** | Team Admin | 月度总预算 | 防止团队整体超支 | Admin 控制台分配 |
| **L2: User 级** | Team Admin | 用户月度配额 | 公平分配团队资源 | Admin 控制台分配 |
| **L3: Key 级** | End User | 日/小时/单次限额 | 防范异常调用、细粒度风控 | User 自助风控中心 |

### 拦截逻辑链
```text
请求进入 
  ↓
L1: Key 级策略检查 (是否冻结？是否超单次限额？是否超小时限额？是否超日限额？) 
  ↓ (通过)
L2: User 月度配额检查 (剩余月度额度是否充足？) 
  ↓ (通过)
L3: Team 月度预算检查 (团队剩余预算是否充足？) 
  ↓ (通过)
放行请求 → 扣减预占额度 → 执行上游调用 → 结算真实用量
```

### 核心字段定义
- **Team 月度预算**：`team_monthly_budget` (Team 级，Admin 设置)
- **User 月度配额**：`user_monthly_quota` (User 级，Admin 分配)
- **Key 单次限额**：`key_max_tokens_per_request` (Key 级，User 自设)
- **Key 小时限额**：`key_hourly_limit` (Key 级，User 自设)
- **Key 日限额**：`key_daily_limit` (Key 级，User 自设)

```text
防止 AI 账单爆炸。
按 Team / User 分摊成本。
事前拦截超额调用。
统一管理 API Key 和大模型供应商密钥。
支持 Cursor / DeepSeek Harness 等客户端零改造接入。
异常检测：对比预估值与厂商真实值，发现计费偏差。
```

### 2.3 目标用户

| 角色 | 痛点 | TokenLimit 提供的价值 |
|---|---|---|
| 研发团队负责人 | 不知道团队 AI 消耗了多少 Token | Team 用量看板和成本统计 |
| 企业 AI 平台负责人 | 多个应用/客户端调用大模型，无法统一治理 | 统一网关和配额控制 |
| 工程师 | 担心个人使用 Cursor / DeepSeek 超额 | 个人额度查看和 API Key 管理 |
| 财务 / 管理者 | AI 账单不可控，无法分摊 | 按 Team / User 查看成本 |

---

## 3. 核心概念模型

### 3.1 模型结构

```text
Team（成本中心 / 预算池）
└── User（成本责任人 / 登录账号）
    └── API Key（调用凭证）
```

### 3.2 Team

Team 是成本中心、预算池和管理边界。

Team 可以表示：

```text
部门（研发部、客服部）
项目组（AI 平台组、数据组）
应用（智能客服、代码助手）
客户（客户 A、客户 B）
```

Team 的职责：

```text
持有团队预算配额。
管理 Team 下的 User。
管理 Team 可用的模型列表（Team Model Policy）。
绑定 Provider Credential（真实大模型 API Key）。
归集 Team 下所有 User / API Key 的成本。
```

### 3.3 User

User 是登录账号，也是成本责任人。

User 可以表示：

```text
员工（zhangsan、lisi）
机器人（bot-ci）
服务账号（service-harness）
```

User 的职责：

```text
登录控制台查看个人数据。
创建和管理自己的 API Key。
拥有个人配额。
承担个人成本，成本归属到 Team。
```

### 3.4 API Key

API Key 是调用 TokenLimit Proxy 的访问凭证。

API Key 的职责：

```text
标识调用方身份（绑定 Team + User）。
用于 Cursor / DeepSeek Harness / SDK 接入。
可设置允许模型、过期时间。
可独立禁用、删除。
```

#### 3.4.1 凭证格式（两段式）

API Key 由 **accessKey + secret** 两段组成：

| 字段 | 格式 | 说明 |
|---|---|---|
| accessKey | `tl_ak_` + 32 位 base62 | 公开标识，全局唯一（≈190 bit 熵，对齐 GitHub / OpenAI 大厂策略），用于定位 Key（控制台列表可见） |
| secret | `sk_tl_xxxxxxxx...`（48 位随机串） | 机密部分，明文仅创建 / 重置时返回一次，库中只存 HMAC-SHA256 哈希（服务端 pepper 参与，防离线碰撞） |

客户端调用 TokenLimit Proxy 时，将两段用冒号拼接为**单个字符串**填入（兼容 Cursor 等只支持单个 API Key 的客户端）：

```text
Authorization: Bearer <access_key>:<secret>
例：Bearer tl_ak_X7f2K9qLm4N8vR3sT6wY1aB5cD7eG0hJ:sk_tl_4f2b8a6c...
```

网关解析后按 accessKey 查库定位、校验 secret 哈希，双向校验通过才放行。

#### 3.4.2 客户端接入示例（Cursor）

```text
Model Provider: OpenAI
Base URL:       http://<tokenlimit-host>:8080/v1
API Key:        tl_ak_XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX:sk_tl_XXXXXXXX...   # access_key 与 secret 用冒号拼接
```

### 3.5 Provider Credential

Provider Credential 是真实大模型供应商的 API Key，由 TokenLimit 统一托管。

```text
客户端不直接持有真实大模型 API Key。
客户端只持有 TokenLimit API Key。
TokenLimit 根据 Team Model Policy 选择 Provider Credential 并转发请求。
```

### 3.6 Team Model Policy

Team Model Policy 定义某个 Team 允许使用哪些模型，以及使用哪个 Provider Credential。

```text
Team: team-rd
  → deepseek-chat → provider: deepseek-company-main
  → gpt-4o-mini   → provider: openai-team-rd
  → claude-opus   → 禁止
```

---

## 4. 角色与权限

### 4.1 角色定义

第一版只保留三个角色：

```text
ADMIN       系统管理员
TEAM_ADMIN  Team 管理员
USER        普通用户
```

### 4.2 ADMIN

```text
管理所有 Team。
管理 Provider Credential（真实大模型 API Key）。
管理 Team Model Policy。
配置系统 Gateway URL。
查看全局用量和成本。
创建 TEAM_ADMIN 账号。
配置系统参数（告警阈值、预估安全系数等）。
```

### 4.3 TEAM_ADMIN

```text
管理本 Team。
创建 / 禁用本 Team 下的 User。
重置 User 密码。
查看本 Team 下的 API Key。
分配 User 个人额度。
设置 User quota_mode。
查看 Team 用量和成本。
```

### 4.4 USER

```text
登录控制台。
查看个人额度、用量、流水。
创建 / 禁用 / 删除自己的 API Key。
```

### 4.5 权限矩阵

| 功能 | ADMIN | TEAM_ADMIN | USER |
|---|---:|---:|---:|
| 登录控制台 | ✅ | ✅ | ✅ |
| 管理所有 Team | ✅ | ❌ | ❌ |
| 创建 Team | ✅ | ❌ | ❌ |
| 管理 Provider Credential | ✅ | ❌ | ❌ |
| 管理 Team Model Policy | ✅ | ❌ | ❌ |
| 配置系统 Gateway URL | ✅ | ❌ | ❌ |
| 管理本 Team User | ✅ | ✅ | ❌ |
| 创建 User | ✅ | ✅ | ❌ |
| 分配 User 个人额度 | ✅ | ✅ | ❌ |
| 查看 Team 成本 | ✅ | ✅ | ❌ |
| 创建自己的 API Key | ✅ | ✅ | ✅ |
| 查看个人额度 | ✅ | ✅ | ✅ |
| 查看个人用量 | ✅ | ✅ | ✅ |
| 查看全局用量 | ✅ | ❌ | ❌ |

---

## 5. 第一版功能范围

### 5.1 第一版目标

```text
让 Cursor / DeepSeek Harness 等客户端通过 TokenLimit Proxy 接入。
实现 Team / User 配额控制。
实现事前超额拦截。
实现用量统计和成本归属。
实现 API Key 统一管理。
实现 Provider Credential 托管。
集成 jtokkit 做 token 预估基准。
实现预估值与真实值对比，检测异常。
```

### 5.2 第一版明确不做

```text
不做供应商账单自动同步。
不做复杂对账引擎。
不做智能模型路由。
不做多集群部署。
不做 SSO / MFA。
不做复杂审批流。
不做 Python SDK。
不做 RPM / TPM 限流。
不做 API Key 级独立配额。
```

---

## 6. OpenAI Compatible Proxy

### 6.1 核心架构

```text
Cursor / DeepSeek Harness / AI Client
        |
        | OpenAI Compatible API
        v
TokenLimit Gateway
        |
        +-- API Key 鉴权
        +-- 解析 Team / User
        +-- 检查 Team Model Policy
        +-- jtokkit 预估 prompt_tokens
        +-- 配额检查（读 Redis used）
        +-- 查找 Provider Credential
        +-- 转发请求到真实模型供应商
        +-- 流式透传响应
        +-- 采集真实 Usage
        +-- 写入 MySQL usage_log
        +-- 更新 Redis used
        |
        v
DeepSeek / OpenAI / Anthropic / Qwen
```

### 6.2 支持接口

第一版必须实现：

```text
GET  /v1/models
POST /v1/chat/completions
```

第一版可选实现：

```text
POST /v1/embeddings
```

### 6.3 鉴权方式

客户端使用 TokenLimit API Key：

```http
Authorization: Bearer <tokenlimit_api_key>
```

客户端不需要也不允许传入 Team / User 信息。

服务端根据 API Key 自动解析：

```text
API Key → Team + User
```

### 6.4 请求处理流程

```text
1. 验证 API Key。
2. 解析 Team / User。
3. 校验 API Key 状态和过期时间。
4. 校验 Team Model Policy，检查模型是否允许。
5. 使用 jtokkit 预估 prompt_tokens，写入 estimated_prompt_tokens。
6. 读取 Redis used，检查 Team 配额。
7. 读取 Redis used，检查 User 个人配额。
8. 根据 quota_mode 判断是否放行。
9. 配额充足，查找 Provider Credential。
10. 转发请求到真实模型供应商。
11. 流式透传响应给客户端。
12. 响应结束后采集真实 Usage。
13. 对比预估值与真实值，检测异常。
14. 写入 MySQL usage_log（持久化）。
15. 更新 Redis used（实时配额）。
16. 返回响应给客户端。
```

### 6.5 配额不足返回

HTTP Status：

```text
429 Too Many Requests
```

返回格式兼容 OpenAI：

```json
{
  "error": {
    "message": "TokenLimit quota exceeded: team monthly budget is exhausted",
    "type": "tokenlimit_quota_exceeded",
    "code": "TEAM_QUOTA_EXCEEDED"
  }
}
```

### 6.6 流式响应处理

```text
TokenLimit 必须支持 stream = true。
不得等待完整响应后再返回给客户端。
必须边接收真实模型的 Chunk，边流式透传给客户端。
首 Token 延迟新增目标 < 100ms。
流式转发过程中，累计已转发的 content 内容。
流正常结束后，从最后一个 chunk 读取 usage。
流中断后，用已转发的 content 估算 completion_tokens。
```

### 6.7 错误码

```text
INVALID_API_KEY
API_KEY_DISABLED
API_KEY_EXPIRED
TEAM_QUOTA_EXCEEDED
USER_QUOTA_EXCEEDED
MODEL_NOT_ALLOWED
PROVIDER_NOT_FOUND
PROVIDER_ERROR
INTERNAL_ERROR
```

---


### 6.8 Provider 协议适配策略 (V5.1 补充)

TokenLimit 对客户端统一暴露 OpenAI 兼容接口 (`/v1/chat/completions`)。
针对不同的底层供应商，采用以下适配策略：

1. **OpenAI 兼容系 (MVP 阶段核心支持)**：
   - 包含：DeepSeek, OpenAI, 阿里通义, 智谱, 月之暗面等。
   - 策略：直接 HTTP 透传。仅动态替换认证 Header (Authorization: Bearer <provider_key>)。
   - 优势：开发成本极低，流式响应无缝透传。

2. **非 OpenAI 兼容系 (V1.2 阶段支持)**：
   - 包含：Anthropic (Claude) 等。
   - 策略：引入 Provider Adapter 层。
   - 处理：将 OpenAI 请求体转换为 Anthropic Messages 格式；动态注入 `x-api-key` 和 `anthropic-version` Header；将 Anthropic 的 SSE 流式事件转换为 OpenAI 格式。
   

## 7. 配额控制

### 7.1 配额模型

V5.2 采用**责任链拦截 + 预计算开关**（拦截策略可配置，丰富用户个性化配置）：

```text
调用大模型前，按配置链顺序拦截（任一拦截即拒绝）：
  1. team-balance  团队余额拦截（TOTAL 周期长期规则）
  2. user-balance  个人余额拦截（TOTAL 周期长期规则，并确定抵扣来源）
  3. usage-period  周期用量拦截（MONTH/WEEK/DAY/HOUR/MINUTE/YEAR 规则，含"每次请求" REQUEST_COUNT 限次）

预计算开关（tokenlimit.quota-precompute-enabled，默认开启）：
  开启（精准前置）：真实余额 - 预扣值 >= 0 才放行（==0 也放行，调用尚未发生；<0 拦截），按 jtokkit 预估量原子预扣
    余额变更发生在调用大模型结束（写 usage_log）时：回滚预扣，再按厂商真实值扣减余额
    并发下存在极小窗口超支 1 次调用（团队调用可能并发透支 Team 额度，可接受）
  关闭（宽松）：仅判断余额不预扣
    并发下最后几次请求可能同时放行（超卖）
```

预扣值 = jtokkit 预估总 token（REQUEST_COUNT 规则为 1）；
等大模型 API 返回真实 token 后，回滚预扣、进行真实扣减。
预扣残留（check 后未 report）随周期 key TTL 自动清理。
预扣值与真实余额分开不同的 Redis key 缓存 Long 值，均用原子操作增减，无需 Lua 脚本。

### 7.2 配额对象

第一版支持：

```text
TEAM
USER
```

### 7.3 配额类型

第一版支持：

```text
TOKEN          Token 数量限制
COST           费用金额限制
REQUEST_COUNT  请求次数限制
```

### 7.4 配额周期

第一版支持：

```text
DAY
MONTH
TOTAL
```

### 7.5 User quota_mode

```text
PERSONAL_ONLY              仅使用个人额度
TEAM_ONLY                  仅使用团队额度
PERSONAL_FIRST_THEN_TEAM   个人优先，不足时团队兜底
```

默认值：

```text
PERSONAL_FIRST_THEN_TEAM
```

### 7.6 扣减规则

```text
1. Team 配额不足，直接拒绝。
2. User 个人额度足够：
   - Team used 增加。
   - User used 增加。
   - consumeFrom = PERSONAL。
3. User 个人额度不足：
   - 如果 quota_mode 允许团队兜底：
       Team used 增加。
       consumeFrom = TEAM。
   - 如果不允许：
       拒绝。
```

### 7.7 本次超额处理

```text
预计算开启（默认）：调用前真实余额 - 预扣值 <= 0 直接拦截，不给本次超额空间。
预计算关闭（宽松）：本次调用导致余额扣为负也允许完成，下次拦截。
超额幅度 = 一次调用的 token 消耗（并发下可能超支 1 次调用），通常在可控范围内。
```

### 7.8 缓冲阈值

```text
soft_limit = limit × 告警阈值（默认 80%）
hard_limit = limit
```

当 `used >= soft_limit` 时，触发告警。
当 `used >= hard_limit` 时，硬拦截。

### 7.9 Redis 数据结构

```text
tokenlimit:quota:balance:team:{team_code}:{limit_type}:{period}:{timeKey}
tokenlimit:quota:balance:user:{user_code}:{limit_type}:{period}:{timeKey}
tokenlimit:quota:pre:team:{team_code}:{limit_type}:{period}:{timeKey}
tokenlimit:quota:pre:user:{user_code}:{limit_type}:{period}:{timeKey}
```

`balance` 存真实余额 = 配额上限 - 真实用量（真实用量来自 MySQL usage_log 聚合，首次访问时计算写入缓存，report 阶段原子扣减保持实时，周期 TTL 滚动重建）；`pre` 存进行中请求的预扣总量（本次请求预估量凭空写入，原子 INCRBY/DECRBY 控制）。两个 key 均缓存 Long 值，key 天然包含 teamCode / userCode。

示例：

```text
tokenlimit:quota:balance:team:team-rd:TOKEN:DAY:20260813
tokenlimit:quota:pre:team:team-rd:TOKEN:DAY:20260813
tokenlimit:quota:pre:user:user-001:TOKEN:WEEK:2026W33
tokenlimit:quota:pre:team:team-rd:REQUEST_COUNT:HOUR:2026081614
```

### 7.10 数据持久化

```text
MySQL 是事实来源，持久化所有用量记录。
Redis 是实时缓存，用于高性能配额检查。

写入顺序：先写 MySQL，再更新 Redis。
配额检查：读 Redis。
历史查询 / 报表 / 对账：读 MySQL。
Redis 数据丢失：从 MySQL 重新聚合恢复。
```

---

## 8. Token 预估与异常检测

### 8.1 预估工具

统一使用 **jtokkit** 作为 token 预估基准。

```text
不追求精确，追求"接近真实值"。
核心价值：异常检测 + 中断兜底 + 统一基准。
```

### 8.2 预估时机

```text
请求发出时：使用 jtokkit 估算 prompt_tokens。
流式中断时：使用 jtokkit 估算已转发的 completion 内容。
厂商未返回 usage 时：使用 jtokkit 估算 prompt + completion。
```

### 8.3 异常检测

```text
对比 jtokkit 预估值与厂商返回的真实值。
偏差超过阈值（默认 50%）时，标记 anomaly_detected = 1。
写入 audit_log，触发告警。
```

### 8.4 异常场景处理

| 场景 | usage_source | status | 配额统计依据 |
| :--- | :--- | :--- | :--- |
| 正常完成，厂商返回 usage | PROVIDER | SUCCESS | 厂商真实值 |
| 流式中断 | ESTIMATED | INTERRUPTED | jtokkit 预估值 |
| 厂商未返回 usage | ESTIMATED | SUCCESS | jtokkit 预估值 |
| 调用报错 | ESTIMATED | ERROR | jtokkit 预估值（如有） |
| 厂商返回异常 usage | PROVIDER | SUCCESS | 厂商真实值（标记异常） |

---

## 9. Provider Credential 管理

### 9.1 设计原则

```text
真实大模型 API Key 由 TokenLimit 统一托管。
客户端不直接持有真实供应商 API Key。
Provider Credential 与 Team Model Policy 分离。
Provider Credential 加密存储，不展示明文。
```

### 9.2 Provider

```text
deepseek
openai
anthropic
aliyun-bailian
zhipu
moonshot
custom
```

### 9.3 Provider Credential

```text
credential_code
credential_name
provider_code
base_url
api_key_encrypted
scope_type（GLOBAL / TEAM）
team_code（scope_type 为 TEAM 时必填）
status
```

### 9.4 Team Model Policy

```text
team_code
model_code
provider_credential_code
status
```

### 9.5 密钥查找优先级

```text
1. 查找该 Team 专属的 Provider Credential。
2. 若没有，查找全局（GLOBAL）Provider Credential。
3. 若都没有，返回 PROVIDER_NOT_FOUND。
```

### 9.6 安全要求

```text
真实 API Key 不能明文存储。
真实 API Key 不能返回给前端。
真实 API Key 不能出现在日志中。
真实 API Key 不能暴露给客户端。
Provider Credential 修改必须审计。
Provider Credential 禁用必须实时生效。
```

### 9.7 内置 Provider 模板（Provider Templates）

为了降低配置门槛并避免 Base URL 拼写错误，系统内置主流大模型厂商的 OpenAI 兼容协议地址。

1. **内置列表**：系统初始化时预置 OpenAI、DeepSeek、阿里云百炼（通义）、月之暗面（Kimi）、零一万物（Yi）、百川智能、MiniMax、硅基流动、OpenRouter、智谱 AI、火山方舟（豆包）、Google Gemini、Mistral、Groq、阶跃星辰、Jina、Ollama 等厂商的 Base URL。内置数据写入 `tl_provider` 表（`is_builtin=1`），同时以代码枚举 `LlmProvider` 作为兜底。
2. **交互逻辑**：ADMIN 创建 Provider Credential 时，供应商下拉展示内置模板，选中后系统自动填充 Base URL（可微调）。
3. **自定义支持**：保留“自定义（Custom）”选项，允许用户手动输入私有化部署模型或小众厂商的 Base URL。
4. **不兼容厂商隔离**：对于原生不兼容 OpenAI 协议的厂商（如 Anthropic、文心一言、讯飞星火），不放入 MVP 阶段的直接透传模板列表（`openai_compatible=0`），避免直接透传导致 400 错误；如需支持需开发协议转换 Adapter。
5. **特殊端点**：智谱 AI 路径为 `/api/paas/v4`（非 `/v1`）；火山方舟无统一模型名，需用户在控制台创建推理接入点并在 Base URL 后拼接 Endpoint ID（如 `/api/v3/ep-xxx`），系统仅预设前缀。

---

## 10. API Key 管理

### 10.1 API Key 字段

```text
key_id
key_name
access_key
secret_hash
team_code
user_code
allowed_models
status
expire_at
last_used_at
created_at
```

### 10.2 创建规则

```text
User 可以创建自己的 API Key。
TEAM_ADMIN 可以创建本 Team 下 User 的 API Key。
API Key 创建时自动生成 access_key 和 secret。
secret 仅创建时显示一次。
数据库只存储 secret_hash。
```

### 10.3 生命周期

```text
创建 → 启用 → 禁用 / 过期 / 删除。
禁用后实时失效。
User 禁用后其所有 API Key 失效。
Team 禁用后其下所有 API Key 失效。
```

---

## 11. 控制台设计

### 11.1 ADMIN 页面

```text
Dashboard           全局用量和成本概览
Team 管理           创建 / 编辑 / 禁用 Team
Provider 管理       配置 Provider Credential 和模型映射
User 管理           查看所有 User
API Key 管理        查看所有 API Key
Quota 管理          配置 Team / User 配额规则
Usage 用量          全局用量统计
Audit 审计          操作日志和拦截日志
Quick Start         快速接入指引
Settings            系统设置
```

### 11.2 TEAM_ADMIN 页面

```text
Team Dashboard      本 Team 用量和成本概览
User 管理           管理本 Team 下 User
API Key 管理        查看本 Team 下 API Key
Quota 管理          配置本 Team 配额规则
Usage 用量          本 Team 用量统计
Audit 审计          本 Team 操作日志
Quick Start         快速接入指引
```

### 11.3 USER 页面

```text
我的概览            个人用量和额度概览
我的额度            个人配额详情
我的用量            个人用量统计
我的流水            个人调用流水
我的 API Key        管理自己的 API Key
Quick Start         快速接入指引
```

### 11.4 Quick Start 快速接入页

Quick Start 页面是一个**配置消费页面**，不是配置生产页面。

页面结构：

```text
步骤 1：选择 API Key（下拉选择已有 Key，提供"去创建"跳转链接）
步骤 2：获取 Gateway URL（系统自动展示 ADMIN 配置的地址，提供复制按钮）
步骤 3：客户端配置示例（Tab 切换：Cursor / DeepSeek Harness / cURL）
```

设计原则：

```text
不在 Quick Start 页面创建 API Key。
不在 Quick Start 页面编辑 Gateway URL。
Gateway URL 由 ADMIN 在 Settings 中配置，Quick Start 只读展示。
若 Gateway URL 未配置，提示联系 ADMIN。
```

### 11.5 Settings 系统设置

ADMIN 可配置：

```text
Gateway Public URL        对外网关地址（Quick Start 页面读取此配置）
预算告警阈值              默认 80%
异常偏差阈值              默认 50%
异常失败策略              fail-close / fail-open
```

---

## 12. 用量统计

### 12.1 统计维度

```text
Team
User
API Key
Model
Provider
Time
consumeFrom
usage_source
status
```

### 12.2 核心指标

```text
request_count
prompt_tokens
completion_tokens
total_tokens
estimated_total_tokens
cost
success_count
failed_count
blocked_count
interrupted_count
anomaly_count
latency
```

### 12.3 成本归属

```text
API Key 是调用入口。
User 是成本责任人。
Team 是成本中心。
所有调用成本最终归属到 Team。
```

### 12.4 计费快照（V5.3 / 缓存计费 V5.4）

```text
计费公式：cost = 正常输入 × input_price_per_token + 缓存命中 × cache_read_price_per_token
               + 缓存写入 × cache_write_price_per_token + 输出 × output_price_per_token
        正常输入 = prompt_tokens - cached_tokens - cache_write_tokens（缓存 token 不超过输入总量）
缓存计费（V5.4）：cached_tokens 兼容解析三厂商 Usage 字段——
        OpenAI prompt_tokens_details.cached_tokens / DeepSeek prompt_cache_hit_tokens /
        Anthropic cache_read_input_tokens；cache_write_tokens 解析 Anthropic cache_creation_input_tokens；
        未配置缓存单价时按正常输入价计费（等价无折扣）。
多币种：按 tl_setting 全局汇率（usd_to_cny_rate 等）折算到企业本位币（base_currency），默认 CNY。
固化学：调用结束写入 usage_log 时，单价、汇率、费用、缓存用量一次性固化（计费快照），
       修改价格/汇率只影响新调用，历史账单费用不变。
兜底：模型未配置价格 → 按 0 费用处理；汇率缺失 → 按 1:1 兜底并记录日志。
```

---

## 13. 审计日志

### 13.1 审计事件

```text
LOGIN_SUCCESS
LOGIN_FAILED
CREATE_TEAM
UPDATE_TEAM
DISABLE_TEAM
CREATE_USER
DISABLE_USER
RESET_PASSWORD
CREATE_API_KEY
DISABLE_API_KEY
DELETE_API_KEY
UPDATE_USER_QUOTA
UPDATE_TEAM_QUOTA
UPDATE_PROVIDER_CREDENTIAL
QUOTA_BLOCK
USAGE_ANOMALY
```

### 13.2 审计字段

```text
team_code
user_code
api_key_id
operator
event_type
target_type
target_code
detail
status
created_at
```

---

## 14. 数据库设计

### 14.1 核心表

```text
tl_team
tl_user
tl_api_key
tl_provider
tl_provider_credential
tl_team_model_policy
tl_quota_rule
tl_usage_log
tl_audit_log
```

### 14.2 tl_team

```sql
CREATE TABLE tl_team (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    team_code VARCHAR(64) NOT NULL,
    team_name VARCHAR(128) NOT NULL,
    team_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ENABLED',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_team_code (team_code)
);
```

### 14.3 tl_user

```sql
CREATE TABLE tl_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    team_code VARCHAR(64) NOT NULL,
    user_code VARCHAR(64) NOT NULL,
    user_name VARCHAR(128) NOT NULL,
    user_type VARCHAR(32) NOT NULL,
    role VARCHAR(32) NOT NULL DEFAULT 'USER',
    login_enabled TINYINT NOT NULL DEFAULT 1,
    username VARCHAR(64) NULL,
    password_hash VARCHAR(128) NULL,
    quota_mode VARCHAR(32) NOT NULL DEFAULT 'PERSONAL_FIRST_THEN_TEAM',
    status VARCHAR(32) NOT NULL DEFAULT 'ENABLED',
    last_login_at DATETIME NULL,
    password_changed_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_code (user_code),
    UNIQUE KEY uk_username (username),
    KEY idx_team (team_code)
);
```

### 14.4 tl_api_key

```sql
CREATE TABLE tl_api_key (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    team_code VARCHAR(64) NOT NULL,
    user_code VARCHAR(64) NOT NULL,
    key_id VARCHAR(64) NOT NULL,
    key_name VARCHAR(128) NOT NULL,
    access_key VARCHAR(64) NOT NULL,
    secret_hash VARCHAR(128) NOT NULL,
    allowed_models TEXT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ENABLED',
    expire_at DATETIME NULL,
    last_used_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_access_key (access_key),
    UNIQUE KEY uk_key_id (key_id),
    KEY idx_user (user_code),
    KEY idx_team (team_code)
);
```

### 14.5 tl_provider

```sql
CREATE TABLE tl_provider (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    provider_code VARCHAR(64) NOT NULL,
    provider_name VARCHAR(128) NOT NULL,
    base_url VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ENABLED',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_provider_code (provider_code)
);
```

### 14.6 tl_provider_credential

```sql
CREATE TABLE tl_provider_credential (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    credential_code VARCHAR(64) NOT NULL,
    credential_name VARCHAR(128) NOT NULL,
    provider_code VARCHAR(64) NOT NULL,
    scope_type VARCHAR(32) NOT NULL,
    team_code VARCHAR(64) NULL,
    api_key_encrypted VARCHAR(1024) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ENABLED',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_credential_code (credential_code),
    KEY idx_scope (scope_type, team_code)
);
```

### 14.7 tl_team_model_policy

```sql
CREATE TABLE tl_team_model_policy (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    team_code VARCHAR(64) NOT NULL,
    model_code VARCHAR(64) NOT NULL,
    provider_credential_code VARCHAR(64) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ENABLED',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_team_model (team_code, model_code)
);
```

### 14.8 tl_quota_rule

```sql
CREATE TABLE tl_quota_rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    target_type VARCHAR(32) NOT NULL,
    target_code VARCHAR(64) NOT NULL,
    model VARCHAR(64) NULL,
    limit_type VARCHAR(32) NOT NULL,
    limit_value BIGINT NOT NULL,
    period VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ENABLED',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_target (target_type, target_code)
);
```

### 14.9 tl_usage_log

```sql
CREATE TABLE tl_usage_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trace_id VARCHAR(64) NOT NULL,

    -- 归属信息
    team_code VARCHAR(64) NOT NULL,
    user_code VARCHAR(64) NOT NULL,
    api_key_id VARCHAR(64) NOT NULL,

    -- 模型信息
    model VARCHAR(64) NOT NULL,
    provider VARCHAR(64) NULL,

    -- 预估值（jtokkit 估算）
    estimated_prompt_tokens BIGINT NULL,
    estimated_completion_tokens BIGINT NULL,
    estimated_total_tokens BIGINT NULL,

    -- 真实值（厂商返回）
    prompt_tokens BIGINT NULL,
    completion_tokens BIGINT NULL,
    total_tokens BIGINT NULL,

    -- 计费快照（Billing Snapshot，V5.3）：写入后费用/单价/汇率永久固化，
    -- 后续修改价格/汇率只影响新调用；报表必须 SUM(cost)，不得用当前价格动态重算历史数据
    currency VARCHAR(16) NOT NULL DEFAULT 'CNY',
    input_price_snapshot DECIMAL(18,10) NOT NULL DEFAULT 0,
    output_price_snapshot DECIMAL(18,10) NOT NULL DEFAULT 0,
    exchange_rate_snapshot DECIMAL(18,6) NOT NULL DEFAULT 1,
    base_currency VARCHAR(16) NOT NULL DEFAULT 'CNY',
    cost_original DECIMAL(18,6) NOT NULL DEFAULT 0,
    cost DECIMAL(18,6) NOT NULL DEFAULT 0,

    -- 缓存计费（V5.4）：缓存用量与缓存单价快照（同样不可变，报表据此计算命中率与节省金额）
    cached_tokens BIGINT NOT NULL DEFAULT 0,
    cache_write_tokens BIGINT NOT NULL DEFAULT 0,
    cache_read_price_snapshot DECIMAL(18,10) NULL,
    cache_write_price_snapshot DECIMAL(18,10) NULL,

    -- 峰谷定价快照（V5.5）：调用时的峰谷价格系数，用于审计和成本分析
    price_multiplier_snapshot DECIMAL(5,2) DEFAULT 1.0 COMMENT '调用时的峰谷价格系数 (如 0.5 表示低谷 5 折)',

    -- 扣减来源
    consume_from VARCHAR(32) NULL,

    -- 用量来源
    usage_source VARCHAR(32) NOT NULL DEFAULT 'PROVIDER',

    -- 调用状态
    status VARCHAR(32) NOT NULL DEFAULT 'SUCCESS',

    -- 异常标记
    anomaly_detected TINYINT NOT NULL DEFAULT 0,
    anomaly_detail VARCHAR(512) NULL,

    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    KEY idx_trace_id (trace_id),
    KEY idx_api_key (api_key_id, created_at),
    KEY idx_user (user_code, created_at),
    KEY idx_team (team_code, created_at),
    KEY idx_status (status, created_at),
    KEY idx_anomaly (anomaly_detected, created_at),
    KEY idx_created_at (created_at)
);
```

### 14.10 tl_audit_log

```sql
CREATE TABLE tl_audit_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    team_code VARCHAR(64) NULL,
    user_code VARCHAR(64) NULL,
    api_key_id VARCHAR(64) NULL,
    operator VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    target_type VARCHAR(64) NULL,
    target_code VARCHAR(64) NULL,
    detail TEXT NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_event (event_type),
    KEY idx_team (team_code, created_at)
);
```

### 14.11 tl_model_price（V5.3 计费基准表）

```sql
CREATE TABLE tl_model_price (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    provider VARCHAR(64) NOT NULL,
    model VARCHAR(128) NOT NULL,

    -- 价格单位：每 1 个 Token 的单价（避免计算时频繁除以 1000000）
    input_price_per_token DECIMAL(18,10) NOT NULL DEFAULT 0,
    output_price_per_token DECIMAL(18,10) NOT NULL DEFAULT 0,
    cache_read_price_per_token DECIMAL(18,10) NULL,
    cache_write_price_per_token DECIMAL(18,10) NULL,

    -- 峰谷定价策略（V5.5）：支持分时段动态定价，引导企业将非实时任务调度到低谷时段
    pricing_type VARCHAR(32) NOT NULL DEFAULT 'FLAT' COMMENT '定价类型：FLAT(固定定价), PEAK_OFF_PEAK(峰谷定价)',
    peak_multiplier DECIMAL(5,2) NOT NULL DEFAULT 1.00 COMMENT '高峰时段价格系数 (如 1.0)',
    off_peak_multiplier DECIMAL(5,2) NOT NULL DEFAULT 0.50 COMMENT '低谷时段价格系数 (如 0.5)',
    off_peak_start TIME NOT NULL DEFAULT '22:00:00' COMMENT '低谷开始时间 (如 22:00)',
    off_peak_end TIME NOT NULL DEFAULT '08:00:00' COMMENT '低谷结束时间 (如 次日 08:00)',

    currency VARCHAR(16) NOT NULL DEFAULT 'CNY',
    status VARCHAR(32) NOT NULL DEFAULT 'ENABLED',
    effective_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_provider_model (provider, model)
);
```

> 价格表只是随时可变的"字典"，只负责给**新的请求**提供计算依据；历史费用以 usage_log 计费快照为准。
> 未配置价格的模型按 0 费用处理（允许调用，控制台应告警提示无法统计成本）。
> 缓存单价（cache_read/write_price_per_token）为可选项，厂商参考折扣：OpenAI 缓存读取为输入价 5 折、
> Anthropic 缓存读取 1 折 / 缓存写入为输入价 1.25 倍、DeepSeek 缓存读取约 1 折；
> 未配置时缓存 token 按正常输入价计费。缓存单价同样固化为 usage_log 快照，改价不影响历史账单。
> 
> **峰谷定价规则（V5.5）**：
> - `pricing_type = 'FLAT'`：固定定价，所有时段统一价格（兼容旧版本）。
> - `pricing_type = 'PEAK_OFF_PEAK'`：峰谷定价，根据请求时间动态应用价格系数。
> - 低谷时段判断逻辑：支持跨天场景（如 22:00 - 次日 08:00）。
> - 最终单价计算公式：`最终单价 = 基础单价 × 缓存折扣 × 峰谷系数`。
> - 峰谷系数在调用发生时计算，并固化到 `usage_log.price_multiplier_snapshot` 字段。
> - 修改峰谷规则（时段/系数）只影响新调用，历史账单保持不变。

---

## 15. 第一版验收标准

### 15.1 Cursor 接入验收

```text
Cursor 配置 TokenLimit Base URL 和 API Key 后，可以正常调用模型。
TokenLimit 能记录 API Key、User、Team。
TokenLimit 能记录 model、tokens、cost。
Team 预算耗尽时，Cursor 调用被拒绝。
API Key 禁用后，Cursor 调用失败。
流式响应正常透传，打字机效果流畅。
```

### 15.2 DeepSeek Harness 接入验收

```text
DeepSeek Harness 配置 TokenLimit Base URL 后，可以正常调用 DeepSeek。
TokenLimit 能将 deepseek-chat 转发到 DeepSeek。
TokenLimit 能记录 DeepSeek 返回的真实 usage。
流式调用正常工作。
配额不足时返回 429。
```

### 15.3 配额验收

```text
Team 配额不足时，所有调用拒绝。
User 个人配额不足时，根据 quota_mode 判断。
PERSONAL_ONLY 模式下，个人额度耗尽必须拒绝。
TEAM_ONLY 模式下，直接使用 Team 配额。
PERSONAL_FIRST_THEN_TEAM 模式下，个人优先，不足时团队兜底。
consumeFrom 必须正确记录 PERSONAL / TEAM。
本次超额允许完成，下次调用被拦截。
```

### 15.4 用量记录验收

```text
正常调用：usage_log 同时记录预估值和真实值，usage_source = PROVIDER。
流式中断：usage_log 记录预估值，usage_source = ESTIMATED，status = INTERRUPTED。
厂商未返回 usage：usage_log 记录预估值，usage_source = ESTIMATED。
异常偏差：anomaly_detected = 1，anomaly_detail 记录偏差详情。
所有 usage_log 持久化到 MySQL。
Redis used 与 MySQL 聚合值一致。
```

### 15.5 峰谷定价验收（V5.5）

```text
固定定价模式 (FLAT)：
  - pricing_type = 'FLAT' 时，所有时段价格系数均为 1.0。
  - price_multiplier_snapshot 始终为 1.0。

峰谷定价模式 (PEAK_OFF_PEAK)：
  - pricing_type = 'PEAK_OFF_PEAK' 时，根据请求时间动态计算价格系数。
  - 低谷时段（如 22:00-08:00）内调用，price_multiplier_snapshot = off_peak_multiplier（如 0.5）。
  - 高峰时段调用，price_multiplier_snapshot = peak_multiplier（如 1.0）。
  - 跨天低谷时段判断正确（22:00-次日 08:00 场景）。

缓存 + 峰谷叠加：
  - 命中缓存且处于低谷时段：最终单价 = 缓存单价 × 峰谷系数。
  - 例如：缓存输入价 ¥1.25/M，峰谷系数 0.5 → 最终 ¥0.625/M。

历史不可变性：
  - 修改峰谷规则（时段/系数）后，历史 usage_log 的 price_multiplier_snapshot 不变。
  - 报表统计历史成本时，SUM(cost) 结果不受价格规则变更影响。

控制台配置：
  - ADMIN 可在模型价格管理页面切换定价模式（固定/峰谷）。
  - 峰谷模式下可配置：低谷时段（起止时间）、低谷系数、高峰系数。
  - 保存时有提示："修改峰谷规则将只影响新的调用，历史账单不会变更"。

成本优化建议（FinOps 增值功能）：
  - Dashboard 展示峰谷成本对比：高峰期消耗 vs 低谷期消耗。
  - 智能调度提示：检测到高峰期批量任务，建议调度至低谷时段执行。
  - 用户额度页面显示当前时段价格系数（如"当前低谷 5 折，额度消耗速度减半"）。
```

### 15.6 Provider Credential 验收

```text
ADMIN 可以添加 Provider Credential。
真实 API Key 加密存储，不展示明文。
Team Model Policy 可以绑定 Provider Credential。
客户端请求时，TokenLimit 自动选择正确的 Provider Credential。
Provider Credential 禁用后，相关请求返回 PROVIDER_NOT_FOUND。
```

---

## 16. 第一版开发优先级

### P0：必须完成

```text
OpenAI Compatible Proxy /v1/chat/completions
API Key 鉴权
Team / User 配额检查（读 Redis used）
Provider Credential 托管
请求转发与流式透传
jtokkit 集成（预估 prompt_tokens）
用量记录（MySQL 持久化 + Redis 更新）
预估值与真实值对比
基础 Console 登录
Team / User / API Key 管理
Provider 管理
Cursor 接入验证
DeepSeek Harness 接入验证
```

### P1：强烈建议完成

```text
GET /v1/models
Quick Start 页面
个人额度页面
Team Dashboard
基础 Audit Log
Settings 系统设置
异常偏差告警
```

### P2：可延后

```text
Embeddings 接口
Plugin Status API
Client quota/check API
Client usage/report API
RPM / TPM 限流
供应商账单导入
对账功能
```

### P3：峰谷定价（V5.5）

```text
tl_model_price 表增加峰谷字段（pricing_type, peak_multiplier, off_peak_multiplier, off_peak_start, off_peak_end）。
tl_usage_log 表增加 price_multiplier_snapshot 字段。
计费引擎支持时间段判断逻辑（跨天场景处理）。
最终单价计算公式：最终单价 = 基础单价 × 缓存折扣 × 峰谷系数。
控制台模型价格管理页面支持峰谷配置 UI。
Dashboard 峰谷成本对比报表。
智能调度建议功能（检测高峰期批量任务）。
```

---

## 17. 里程碑

### Week 1：核心网关可运行

```text
Spring Boot Server 启动。
MySQL / Redis 初始化。
jtokkit 依赖引入。
API Key 鉴权。
/v1/chat/completions 可转发 DeepSeek / OpenAI。
流式透传可正常工作。
```

### Week 2：配额与统计闭环

```text
Team / User 配额规则。
Redis used 计数器。
Provider Credential 管理。
Team Model Policy。
usage_log 写入（预估值 + 真实值）。
Redis used 更新。
基础 Console。
```

### Week 3：Cursor / DeepSeek Harness 验收

```text
Cursor 自定义模型接入。
DeepSeek Harness Base URL 接入。
流式调用验收。
超额拦截验收。
禁用 API Key 验收。
中断场景验收。
异常偏差检测验收。
```

---

## 18. 后续版本规划

### V1.1

```text
Plugin Status API。
Cursor 插件状态展示。
DeepSeek Harness 插件状态展示。
基础告警（预算告警、异常偏差告警）。
用量报表增强。
```

### V1.2

```text
RPM / TPM 限流。
API Key 级配额。
模型白名单。
Team 成本看板增强。
审计日志增强。
```

### V2.0

```text
供应商账单导入。
账单对账。
异常计费分析。
多供应商成本分析。
企业 AI FinOps 报表。
```

---

## 19. 最终产品规则摘要

```text
1. TokenLimit 核心模型为 Team → User → API Key。
2. Team 是成本中心和预算池。
3. User 是登录账号和个人成本责任人。
4. API Key 是调用 TokenLimit Proxy 的访问凭证。
5. 一个 User 可以创建多个 API Key。
6. 调用统计首先记录 API Key，再归属 User 和 Team。
7. 成本最终落到 User 和 Team。
8. 真实大模型 API Key 由 TokenLimit Provider Credential 统一托管。
9. 客户端不直接持有真实供应商 API Key。
10. Team Model Policy 决定 Team 能使用哪些模型。
11. 第一版优先通过 OpenAI Compatible Proxy 接入 Cursor / DeepSeek Harness。
12. 配额默认采用责任链拦截 + 预计算开关：调用前按 jtokkit 预估量原子预扣（真实余额 - 预扣值 >= 0 放行，==0 也放行；<0 拦截），调用后回滚预扣、按厂商真实值扣减余额；关闭预计算则仅判断余额（余额 0 即拦截）。
13. 预计算开启时余额不足即拦截（严格，并发下可能超支 1 次调用）；关闭时本次超额允许完成，下次拦截。
14. 统一使用 jtokkit 做 token 预估基准。
15. usage_log 同时记录预估值和真实值。
16. 正常情况以厂商真实 usage 为准。
17. 中断 / 异常情况下使用 jtokkit 预估值。
18. 预估值与真实值偏差过大时，触发异常告警。
19. MySQL 持久化所有用量记录，Redis 用于实时配额检查。
20. 写入顺序：先写 MySQL，再更新 Redis。
21. 第一版目标是立即防止 Cursor / DeepSeek Harness 调用导致账单爆炸。
22. 峰谷定价（V5.5）：支持分时段动态定价，引导企业将非实时任务调度到低谷时段。
23. 最终单价计算公式：最终单价 = 基础单价 × 缓存折扣 × 峰谷系数。
24. 峰谷系数在调用发生时固化到 usage_log，历史账单不可变。
25. 修改峰谷规则只影响新调用，不影响历史记录。
```