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

### 2.2 核心价值

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
不做预估冻结结算模型。
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

采用**简单计数器模型**：

```text
调用前：检查 used >= limit？拦截 : 放行
调用后：used += actual_tokens（厂商返回的真实值）
```

不做预估冻结，不做预扣减。

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
即使本次调用导致 used 超过 limit，也允许本次调用完成。
下次调用时，基于更新后的 used 判断，会被拦截。
超额幅度 = 一次调用的 token 消耗，通常在可控范围内。
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
tokenlimit:quota:used:team:{team_code}:{limit_type}:{period}:{timeKey}
tokenlimit:quota:used:user:{user_code}:{limit_type}:{period}:{timeKey}
```

示例：

```text
tokenlimit:quota:used:team:team-rd:TOKEN:DAY:20260813
tokenlimit:quota:used:user:zhangsan:TOKEN:DAY:20260813
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

    -- 费用
    cost DECIMAL(18,6) NULL,

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

### 15.5 Provider Credential 验收

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
12. 配额采用简单计数器模型：调用前检查 used，调用后累加真实值。
13. 不做预估冻结结算，本次超额允许完成，下次拦截。
14. 统一使用 jtokkit 做 token 预估基准。
15. usage_log 同时记录预估值和真实值。
16. 正常情况以厂商真实 usage 为准。
17. 中断 / 异常情况下使用 jtokkit 预估值。
18. 预估值与真实值偏差过大时，触发异常告警。
19. MySQL 持久化所有用量记录，Redis 用于实时配额检查。
20. 写入顺序：先写 MySQL，再更新 Redis。
21. 第一版目标是立即防止 Cursor / DeepSeek Harness 调用导致账单爆炸。
```