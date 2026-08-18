# TokenLimit 产品需求文档 PRD

> TokenLimit：企业大模型 Token 预算网关与 AI FinOps 平台。
> 
> **V6.0 核心变革**：废除年度预算，重构为"Team 管月度分配，User 管细粒度执行"的三级预算体系。

---

## 1. 文档信息

| 项目 | 内容 |
|---|---|
| 产品名称 | TokenLimit |
| 文档版本 | **V6.0 (预算体系重构版)** |
| 文档状态 | 可开发 |
| 核心模型 | Team → User → API Key |
| 预算机制 | **滚动月度预算**（废除年度预算） |
| 管控模式 | **三级拦截**：Team 月度 → User 月度 → Key 日/小时/单次 |
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
- **三级风控**：建立 Team→User→Key 三级拦截体系，新增小时级熔断和单次请求上限。

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
- **Key 状态**：`key_status` (ACTIVE/FROZEN/DISABLED，User 可自主冻结)

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
持有团队月度预算配额。
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
拥有个人月度配额。
承担个人成本，成本归属到 Team。
在月度配额内，自主设置 Key 的日/小时/单次限额。
```

### 3.4 API Key

API Key 是调用 TokenLimit Proxy 的访问凭证。

API Key 的职责：

```text
标识调用方身份（绑定 Team + User）。
用于 Cursor / DeepSeek Harness / SDK 接入。
可设置允许模型、过期时间。
可独立禁用、删除。
支持用户自定义日限额、小时限额、单次请求限额。
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
分配 User 个人月度配额。
设置 Team 月度总预算。
查看 Team 用量和成本。
```

### 4.4 USER

```text
登录控制台。
查看个人额度、用量、流水。
创建 / 禁用 / 删除自己的 API Key。
**自主设置 API Key 的日限额、小时限额、单次请求限额**。
**自主冻结/解冻 API Key**。
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
| 分配 User 月度配额 | ✅ | ✅ | ❌ |
| 设置 Team 月度预算 | ✅ | ✅ | ❌ |
| 创建自己的 API Key | ✅ | ✅ | ✅ |
| 设置 Key 日/小时/单次限额 | ❌ | ❌ | ✅ |
| 冻结/解冻自己的 Key | ❌ | ❌ | ✅ |
| 查看个人额度 | ✅ | ✅ | ✅ |
| 查看个人用量 | ✅ | ✅ | ✅ |
| 查看全局用量 | ✅ | ❌ | ❌ |

---

## 5. 第一版功能范围

### 5.1 第一版目标

```text
让 Cursor / DeepSeek Harness 等客户端通过 TokenLimit Proxy 接入。
实现 Team / User 月度配额控制。
实现 Key 级日/小时/单次限额控制。
实现事前超额拦截。
实现用量统计和成本归属。
实现 API Key 统一管理。
实现 Provider Credential 托管。
集成 jtokkit 做 token 预估基准。
实现预估值与真实值对比，检测异常。
```

### 5.2 第一版明确不做

```text
不做年度预算管理（全面转向滚动月度预算）。
不做供应商账单自动同步（改为支持系统数据导出，线下比对）。
不做复杂对账引擎（第一版先人工线下比对）。
不做智能模型路由。
不做多集群部署。
不做 SSO / MFA。
不做复杂审批流。
不做 Python SDK。
不做客户端主动上报接口（Client quota/check API、Client usage/report API）。
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
        +-- Key 级策略检查（日/小时/单次限额）
        +-- User 月度配额检查
        +-- Team 月度预算检查
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
5. 使用 jtokkit 预估 prompt_tokens。
6. 【新增】Key 级策略检查：单次限额、小时限额、日限额。
7. 读取 Redis used，检查 User 月度配额。
8. 读取 Redis used，检查 Team 月度预算。
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

错误码扩展（V6.0 新增）：

```text
KEY_FROZEN                 Key 已被用户冻结
KEY_SINGLE_REQUEST_LIMIT   超出单次请求限额
KEY_HOURLY_LIMIT           超出小时限额
KEY_DAILY_LIMIT            超出日限额
USER_MONTHLY_QUOTA         超出用户月度配额
TEAM_MONTHLY_BUDGET        超出团队月度预算
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
KEY_FROZEN
KEY_SINGLE_REQUEST_LIMIT
KEY_HOURLY_LIMIT
KEY_DAILY_LIMIT
USER_MONTHLY_QUOTA
TEAM_MONTHLY_BUDGET
MODEL_NOT_ALLOWED
PROVIDER_NOT_FOUND
PROVIDER_ERROR
INTERNAL_ERROR
```

---

## 7. 配额控制

### 7.1 配额模型

V6.0 采用**三级拦截 + 预计算开关**：

```text
调用大模型前，按顺序拦截（任一拦截即拒绝）：
  1. Key 级策略拦截（单次限额、小时限额、日限额、冻结状态）
  2. User 月度配额拦截
  3. Team 月度预算拦截

预计算开关（tokenlimit.quota-precompute-enabled，默认开启）：
  开启（精准前置）：真实余额 - 预扣值 >= 0 才放行（==0 也放行，调用尚未发生；<0 拦截），按 jtokkit 预估量原子预扣
    余额变更发生在调用大模型结束（写 usage_log）时：回滚预扣，再按厂商真实值扣减余额
    并发下存在极小窗口超支 1 次调用（团队调用可能并发透支 Team 额度，可接受）
  关闭（宽松）：仅判断余额不预扣
    并发下最后几次请求可能同时放行（超卖）
```

预扣值 = jtokkit 预估总 token；
等大模型 API 返回真实 token 后，回滚预扣、进行真实扣减。
预扣残留（check 后未 report）随周期 key TTL 自动清理。
预扣值与真实余额分开不同的 Redis key 缓存 Long 值，均用原子操作增减，无需 Lua 脚本。

### 7.2 配额对象

第一版支持：

```text
TEAM（月度预算）
USER（月度配额）
API KEY（日限额、小时限额、单次限额）
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
HOUR           （Key 级新增）
DAY            （Key 级）
MONTH          （Team/User 级）
TOTAL          （历史累计）
```

### 7.5 扣减规则

```text
1. Team 月度预算不足，直接拒绝。
2. User 月度配额不足，直接拒绝。
3. Key 级策略检查：
   - 单次请求 token 数 > key_max_tokens_per_request → 拒绝
   - 过去 1 小时消耗 > key_hourly_limit → 拒绝
   - 过去 24 小时消耗 > key_daily_limit → 拒绝
   - key_status = FROZEN → 拒绝
```

### 7.6 缓冲阈值

```text
soft_limit = limit × 告警阈值（默认 80%）
hard_limit = limit
```

当 `used >= soft_limit` 时，触发告警。
当 `used >= hard_limit` 时，硬拦截。

### 7.7 Redis 数据结构

```text
# Team 月度预算
tokenlimit:quota:balance:team:{team_code}:TOKEN:MONTH:{YYYYMM}
tokenlimit:quota:pre:team:{team_code}:TOKEN:MONTH:{YYYYMM}

# User 月度配额
tokenlimit:quota:balance:user:{user_code}:TOKEN:MONTH:{YYYYMM}
tokenlimit:quota:pre:user:{user_code}:TOKEN:MONTH:{YYYYMM}

# Key 日限额（新增）
tokenlimit:quota:balance:key:{key_id}:TOKEN:DAY:{YYYYMMDD}
tokenlimit:quota:pre:key:{key_id}:TOKEN:DAY:{YYYYMMDD}

# Key 小时限额（新增）
tokenlimit:quota:balance:key:{key_id}:TOKEN:HOUR:{YYYYMMDDHH}
tokenlimit:quota:pre:key:{key_id}:TOKEN:HOUR:{YYYYMMDDHH}

# Key 单次限额（内存检查，无需 Redis）
```

`balance` 存真实余额 = 配额上限 - 真实用量；`pre` 存进行中请求的预扣总量。

### 7.8 数据持久化

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
status（ACTIVE/DISABLED）
policy_status（NORMAL/FROZEN）  # 新增：用户自主冻结状态
max_tokens_per_request          # 新增：单次请求限额（User 自设）
hourly_limit                    # 新增：小时限额（User 自设）
daily_limit                     # 新增：日限额（User 自设）
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
初始限额为空（不限制），User 可随时设置。
```

### 10.3 生命周期

```text
创建 → 启用 → 禁用 / 过期 / 删除。
用户可自主冻结/解冻（不影响 ADMIN 的禁用状态）。
禁用后实时失效。
User 禁用后其所有 API Key 失效。
Team 禁用后其下所有 API Key 失效。
```

### 10.4 用户自助风控策略

```text
User 可在控制台"我的资产与风控"页面：
1. 查看每个 Key 的实时消耗（今日/本月/累计）。
2. 设置/修改 Key 的单次请求限额。
3. 设置/修改 Key 的小时限额。
4. 设置/修改 Key 的日限额。
5. 自主冻结/解冻 Key（紧急情况下快速止损）。
6. 查看限额触发记录和拦截日志。
```

---

## 11. 控制台设计

### 11.1 ADMIN 页面

```text
Dashboard           全局用量和成本概览
Team 管理           创建 / 编辑 / 禁用 Team，设置 Team 月度预算
Provider 管理       配置 Provider Credential 和模型映射
User 管理           查看所有 User
API Key 管理        查看所有 API Key
Quota 管理          配置 User 月度配额
Usage 用量          全局用量统计
Audit 审计          操作日志和拦截日志
Quick Start         快速接入指引
Settings            系统设置
```

### 11.2 TEAM_ADMIN 页面

```text
Team Dashboard      本 Team 月度预算大盘（剩余预算、消耗趋势）
User 管理           管理本 Team 下 User，分配月度配额
API Key 管理        查看本 Team 下 API Key
Usage 用量          本 Team 用量统计
Audit 审计          本 Team 操作日志
Quick Start         快速接入指引
```

### 11.3 USER 页面

```text
我的概览            个人月度配额、剩余额度、消耗趋势
我的 API Key        管理自己的 API Key（创建/禁用/删除）
【新增】我的资产与风控中心
  - 可视化展示每个 Key 的实时消耗（今日/小时/累计）
  - 设置 Key 的单次请求限额
  - 设置 Key 的小时限额
  - 设置 Key 的日限额
  - 自主冻结/解冻 Key
  - 查看限额触发记录和拦截日志
我的额度            个人配额详情
我的用量            个人用量统计
我的流水            个人调用流水
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

### 12.4 计费快照

```text
计费公式：cost = 正常输入 × input_price_per_token + 缓存命中 × cache_read_price_per_token
               + 缓存写入 × cache_write_price_per_token + 输出 × output_price_per_token
        正常输入 = prompt_tokens - cached_tokens - cache_write_tokens（缓存 token 不超过输入总量）
缓存计费：cached_tokens 兼容解析三厂商 Usage 字段——
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
UPDATE_TEAM_BUDGET
UPDATE_PROVIDER_CREDENTIAL
QUOTA_BLOCK
USAGE_ANOMALY
KEY_POLICY_UPDATE        # 新增：用户修改 Key 限额策略
KEY_FROZEN               # 新增：用户自主冻结 Key
KEY_UNFROZEN             # 新增：用户解冻 Key
```

### 13.2 审计字段

```text
team_code
user_code
event_type
event_time
ip_address
user_agent
request_body
response_body
result
```

---

## 14. 数据库设计

### 14.1 核心表结构

#### Team 表 (tl_team)

```sql
CREATE TABLE tl_team (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    team_code VARCHAR(64) UNIQUE NOT NULL,
    team_name VARCHAR(128) NOT NULL,
    monthly_budget BIGINT DEFAULT 0,        -- Team 月度预算（Token 数或金额）
    budget_currency VARCHAR(10) DEFAULT 'CNY',
    status TINYINT DEFAULT 1,               -- 1: ACTIVE, 0: DISABLED
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
```

#### User 表 (tl_user)

```sql
CREATE TABLE tl_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_code VARCHAR(64) UNIQUE NOT NULL,
    username VARCHAR(64) NOT NULL,
    email VARCHAR(128),
    team_code VARCHAR(64) NOT NULL,
    role TINYINT DEFAULT 3,                 -- 1: ADMIN, 2: TEAM_ADMIN, 3: USER
    monthly_quota BIGINT DEFAULT 0,         -- User 月度配额
    quota_mode TINYINT DEFAULT 3,           -- 1: PERSONAL_ONLY, 2: TEAM_ONLY, 3: PERSONAL_FIRST_THEN_TEAM
    status TINYINT DEFAULT 1,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
```

#### API Key 表 (tl_api_key)

```sql
CREATE TABLE tl_api_key (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    key_name VARCHAR(128),
    access_key VARCHAR(64) UNIQUE NOT NULL,
    secret_hash VARCHAR(128) NOT NULL,
    team_code VARCHAR(64) NOT NULL,
    user_code VARCHAR(64) NOT NULL,
    allowed_models TEXT,
    status TINYINT DEFAULT 1,               -- 1: ACTIVE, 0: DISABLED
    policy_status TINYINT DEFAULT 1,        -- 1: NORMAL, 0: FROZEN (用户自主冻结)
    max_tokens_per_request BIGINT DEFAULT NULL,  -- 单次请求限额（NULL=不限制）
    hourly_limit BIGINT DEFAULT NULL,            -- 小时限额（NULL=不限制）
    daily_limit BIGINT DEFAULT NULL,             -- 日限额（NULL=不限制）
    expire_at DATETIME,
    last_used_at DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
```

#### ApiKeyPolicy 表 (tl_api_key_policy) - 新增

```sql
CREATE TABLE tl_api_key_policy (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    key_id BIGINT NOT NULL,
    access_key VARCHAR(64) NOT NULL,
    max_tokens_per_request BIGINT DEFAULT NULL,
    hourly_limit BIGINT DEFAULT NULL,
    daily_limit BIGINT DEFAULT NULL,
    is_frozen TINYINT DEFAULT 0,
    frozen_at DATETIME,
    unfrozen_at DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_access_key (access_key),
    INDEX idx_key_id (key_id)
);
```

#### Usage Log 表 (tl_usage_log)

```sql
CREATE TABLE tl_usage_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id VARCHAR(64) UNIQUE NOT NULL,
    team_code VARCHAR(64) NOT NULL,
    user_code VARCHAR(64) NOT NULL,
    key_id BIGINT NOT NULL,
    model_code VARCHAR(64) NOT NULL,
    provider_code VARCHAR(64) NOT NULL,
    prompt_tokens BIGINT DEFAULT 0,
    completion_tokens BIGINT DEFAULT 0,
    total_tokens BIGINT DEFAULT 0,
    estimated_prompt_tokens BIGINT DEFAULT 0,
    estimated_completion_tokens BIGINT DEFAULT 0,
    estimated_total_tokens BIGINT DEFAULT 0,
    cost DECIMAL(20, 6) DEFAULT 0,
    currency VARCHAR(10) DEFAULT 'CNY',
    usage_source TINYINT DEFAULT 1,         -- 1: PROVIDER, 2: ESTIMATED
    status TINYINT DEFAULT 1,               -- 1: SUCCESS, 2: ERROR, 3: INTERRUPTED
    consume_from TINYINT DEFAULT 3,         -- 1: PERSONAL, 2: TEAM, 3: PERSONAL_FIRST_THEN_TEAM
    anomaly_detected TINYINT DEFAULT 0,
    latency_ms INT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_team_time (team_code, created_at),
    INDEX idx_user_time (user_code, created_at),
    INDEX idx_key_time (key_id, created_at)
);
```

### 14.2 数据库迁移脚本

```sql
-- V6.0 预算体系重构迁移脚本

-- 1. 移除年度相关字段
ALTER TABLE tl_team DROP COLUMN IF EXISTS annual_budget;
ALTER TABLE tl_user DROP COLUMN IF EXISTS annual_quota;

-- 2. 重命名月度字段（如果存在旧命名）
ALTER TABLE tl_team CHANGE COLUMN IF EXISTS monthly_budget monthly_budget BIGINT DEFAULT 0 COMMENT 'Team 月度预算';
ALTER TABLE tl_user CHANGE COLUMN IF EXISTS monthly_quota monthly_quota BIGINT DEFAULT 0 COMMENT 'User 月度配额';

-- 3. 新增 API Key 策略字段
ALTER TABLE tl_api_key 
ADD COLUMN policy_status TINYINT DEFAULT 1 COMMENT '1: NORMAL, 0: FROZEN' AFTER status,
ADD COLUMN max_tokens_per_request BIGINT DEFAULT NULL COMMENT '单次请求限额' AFTER policy_status,
ADD COLUMN hourly_limit BIGINT DEFAULT NULL COMMENT '小时限额' AFTER max_tokens_per_request,
ADD COLUMN daily_limit BIGINT DEFAULT NULL COMMENT '日限额' AFTER hourly_limit;

-- 4. 创建 ApiKeyPolicy 表（可选，用于记录策略变更历史）
CREATE TABLE IF NOT EXISTS tl_api_key_policy (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    key_id BIGINT NOT NULL,
    access_key VARCHAR(64) NOT NULL,
    max_tokens_per_request BIGINT DEFAULT NULL,
    hourly_limit BIGINT DEFAULT NULL,
    daily_limit BIGINT DEFAULT NULL,
    is_frozen TINYINT DEFAULT 0,
    frozen_at DATETIME,
    unfrozen_at DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_access_key (access_key),
    INDEX idx_key_id (key_id)
);

-- 5. 初始化现有 Key 的策略（如果有历史数据）
INSERT INTO tl_api_key_policy (key_id, access_key, is_frozen)
SELECT id, access_key, 0 FROM tl_api_key WHERE id NOT IN (SELECT key_id FROM tl_api_key_policy);
```

---

## 15. 后端接口设计

### 15.1 新增接口

#### UserPolicyController - 用户策略管理

```java
@RestController
@RequestMapping("/api/v1/user/policy")
public class UserPolicyController {
    
    /**
     * 获取当前用户的 API Key 策略列表
     */
    @GetMapping("/keys")
    public ResponseEntity<List<KeyPolicyVO>> getKeyPolicies() {}
    
    /**
     * 更新 Key 的单次请求限额
     */
    @PutMapping("/keys/{keyId}/single-request-limit")
    public ResponseEntity<Void> updateSingleRequestLimit(
        @PathVariable Long keyId,
        @RequestBody LimitUpdateRequest request) {}
    
    /**
     * 更新 Key 的小时限额
     */
    @PutMapping("/keys/{keyId}/hourly-limit")
    public ResponseEntity<Void> updateHourlyLimit(
        @PathVariable Long keyId,
        @RequestBody LimitUpdateRequest request) {}
    
    /**
     * 更新 Key 的日限额
     */
    @PutMapping("/keys/{keyId}/daily-limit")
    public ResponseEntity<Void> updateDailyLimit(
        @PathVariable Long keyId,
        @RequestBody LimitUpdateRequest request) {}
    
    /**
     * 冻结 Key
     */
    @PostMapping("/keys/{keyId}/freeze")
    public ResponseEntity<Void> freezeKey(@PathVariable Long keyId) {}
    
    /**
     * 解冻 Key
     */
    @PostMapping("/keys/{keyId}/unfreeze")
    public ResponseEntity<Void> unfreezeKey(@PathVariable Long keyId) {}
    
    /**
     * 获取 Key 的实时消耗统计
     */
    @GetMapping("/keys/{keyId}/usage-stats")
    public ResponseEntity<KeyUsageStatsVO> getKeyUsageStats(@PathVariable Long keyId) {}
}
```

### 15.2 修改接口

#### BudgetController - 预算设置（移除年度）

```java
@RestController
@RequestMapping("/api/v1/admin/budget")
public class BudgetController {
    
    /**
     * 设置 Team 月度预算（移除年度预算接口）
     */
    @PutMapping("/team/{teamCode}/monthly")
    public ResponseEntity<Void> setTeamMonthlyBudget(
        @PathVariable String teamCode,
        @RequestBody BudgetUpdateRequest request) {}
    
    /**
     * 设置 User 月度配额（移除年度配额接口）
     */
    @PutMapping("/user/{userCode}/monthly")
    public ResponseEntity<Void> setUserMonthlyQuota(
        @PathVariable String userCode,
        @RequestBody QuotaUpdateRequest request) {}
}
```

---

## 16. 前端页面设计

### 16.1 Admin 视图 - 月度预算大盘

```html
<!-- 简化后的 Admin 视图 -->
<div class="admin-dashboard">
  <h1>月度预算大盘</h1>
  
  <!-- Team 月度预算卡片 -->
  <div class="budget-card">
    <h3>Team 月度总预算</h3>
    <div class="budget-value">{{ teamMonthlyBudget }}</div>
    <div class="budget-used">已使用：{{ usedPercentage }}%</div>
    <button @click="editBudget">调整预算</button>
  </div>
  
  <!-- User 配额分配列表 -->
  <div class="user-quota-list">
    <h3>User 月度配额分配</h3>
    <table>
      <thead>
        <tr>
          <th>用户</th>
          <th>月度配额</th>
          <th>已使用</th>
          <th>剩余额度</th>
          <th>操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="user in users">
          <td>{{ user.username }}</td>
          <td>{{ user.monthlyQuota }}</td>
          <td>{{ user.usedQuota }}</td>
          <td>{{ user.remainingQuota }}</td>
          <td>
            <button @click="editUserQuota(user)">调整配额</button>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</div>
```

### 16.2 User 视图 - 我的资产与风控中心

```html
<!-- 新增 User 自助风控中心 -->
<div class="user-risk-control">
  <h1>我的资产与风控中心</h1>
  
  <!-- 月度配额概览 -->
  <div class="quota-overview">
    <h3>本月配额</h3>
    <div class="progress-bar">
      <div class="progress" :style="{ width: usedPercentage + '%' }"></div>
    </div>
    <div>{{ usedQuota }} / {{ monthlyQuota }}</div>
  </div>
  
  <!-- API Key 列表与策略设置 -->
  <div class="key-policy-list">
    <h3>我的 API Key 与风控策略</h3>
    <div class="key-card" v-for="key in apiKeys">
      <div class="key-header">
        <span class="key-name">{{ key.keyName }}</span>
        <span class="key-status" :class="key.policyStatus">{{ key.policyStatusText }}</span>
      </div>
      
      <!-- 实时消耗展示 -->
      <div class="usage-stats">
        <div class="stat-item">
          <label>今日消耗</label>
          <div>{{ key.todayUsed }}</div>
        </div>
        <div class="stat-item">
          <label>小时消耗</label>
          <div>{{ key.hourlyUsed }}</div>
        </div>
        <div class="stat-item">
          <label>本月累计</label>
          <div>{{ key.monthlyUsed }}</div>
        </div>
      </div>
      
      <!-- 限额设置 -->
      <div class="limit-settings">
        <div class="limit-input">
          <label>单次请求限额</label>
          <input type="number" v-model="key.singleRequestLimit" 
                 @change="updateLimit(key, 'single')" />
          <span>tokens</span>
        </div>
        <div class="limit-input">
          <label>小时限额</label>
          <input type="number" v-model="key.hourlyLimit" 
                 @change="updateLimit(key, 'hourly')" />
          <span>tokens</span>
        </div>
        <div class="limit-input">
          <label>日限额</label>
          <input type="number" v-model="key.dailyLimit" 
                 @change="updateLimit(key, 'daily')" />
          <span>tokens</span>
        </div>
      </div>
      
      <!-- 紧急操作 -->
      <div class="emergency-actions">
        <button v-if="key.policyStatus === 'NORMAL'" 
                @click="freezeKey(key)" class="btn-freeze">
          🔒 紧急冻结
        </button>
        <button v-else @click="unfreezeKey(key)" class="btn-unfreeze">
          🔓 解除冻结
        </button>
      </div>
    </div>
  </div>
</div>
```

---

## 17. 部署与迁移

### 17.1 部署步骤

```bash
# 1. 备份现有数据库
mysqldump -u root -p tokenlimit > backup_$(date +%Y%m%d_%H%M%S).sql

# 2. 执行数据库迁移脚本
mysql -u root -p tokenlimit < scripts/migrate_v6.0.sql

# 3. 停止现有服务
systemctl stop tokenlimit

# 4. 部署新版本代码
git pull origin main
mvn clean package -DskipTests

# 5. 启动服务
systemctl start tokenlimit

# 6. 验证服务健康状态
curl http://localhost:8080/actuator/health
```

### 17.2 迁移注意事项

```text
1. 迁移前务必备份数据库。
2. 年度预算字段直接移除，历史数据不再保留。
3. 现有 API Key 的策略字段初始化为 NULL（不限制）。
4. 迁移后首次启动时，BudgetService 会自动重建 Redis 缓存。
5. 建议低峰期执行迁移，避免影响线上业务。
```

---

## 18. 版本历史

| 版本 | 日期 | 变更内容 |
|---|---|---|
| V6.0 | 2026-01-XX | **预算体系重构**：废除年度预算，建立三级管控体系，新增用户自助风控中心 |
| V5.2 | 2026-01-XX | 优化配额预计算逻辑，支持多种配额周期 |
| V5.1 | 2026-01-XX | 增加 Provider 协议适配策略 |
| V5.0 | 2026-01-XX | 初始版本：支持 Team/User/API Key 三级模型 |

---

## 19. 附录

### 19.1 术语表

| 术语 | 定义 |
|---|---|
| Team | 成本中心，预算池，管理边界 |
| User | 登录账号，成本责任人 |
| API Key | 调用凭证，绑定 Team + User |
| 滚动月度预算 | 每月自动重置的预算机制，不设年度总额 |
| 三级拦截 | Key 级 → User 级 → Team 级的配额检查链 |
| 小时级熔断 | Key 级小时内消耗超限自动拦截 |
| 单次请求限额 | 单次 API 调用的最大 token 数限制 |

### 19.2 参考资料

- [OpenAI API 文档](https://platform.openai.com/docs/api-reference)
- [jtokkit GitHub](https://github.com/knuddelsgmbh/jtokkit)
- [Redis 官方文档](https://redis.io/documentation)

---

**文档结束**
