# 配额模型（V5.1）

## 概述

Token Limit 使用 **Redis 双 key + Lua 原子脚本** 的配额模型，支持两种拦截策略（配置项 `tokenlimit.quota-check-mode`，默认 `PREDUCT`）。

## 双拦截策略

```text
PREDUCT（默认，严格）：
  调用前：判断剩余额度（limit - used - pre）> 0
          按 jtokkit 预估量预扣：pre += est_tokens（Lua 原子）
          任一规则预扣后剩余 < 0 → 拦截（回滚已预扣规则）
  调用后：回滚预扣 pre -= est_tokens
          按厂商真实值累加 used += actual_tokens
  并发安全性：预扣与检查在同一个 Lua 脚本内完成，天然防并发超卖

CHECK_ONLY（宽松）：
  调用前：检查 used >= limit？拦截 : 放行（不预扣）
  调用后：used += actual_tokens
  并发下最后一次请求可能同时放行（超卖）
```

预扣值 = jtokkit 预估总 token（`REQUEST_COUNT` 规则为 1）。

## Redis Key 结构

```text
{prefix}:quota:used:{targetType}:{targetCode}:{limitType}:{period}:{timeKey}
{prefix}:quota:pre:{targetType}:{targetCode}:{limitType}:{period}:{timeKey}
```

- `used`：已完成调用的真实用量，与 MySQL `usage_log` 聚合一致；
- `pre`：进行中请求的预扣总量（PREDUCT 模式）；
- `targetType`：`team` / `user`；
- `timeKey`：周期时间片（DAY 为 `yyyyMMdd`，MONTH 为 `yyyyMM`，TOTAL 为 `total`）。

示例：

```text
tokenlimit:quota:used:team:team-rd:TOKEN:DAY:20260813
tokenlimit:quota:pre:team:team-rd:TOKEN:DAY:20260813
```

两个 key 均设置周期剩余时间 TTL；周期滚动时自动清零。
预扣残留（check 后未 report）随周期 key TTL 自动清理；check 上下文在 `check-context-ttl-seconds`（默认 3600 秒）后过期。

## Lua 脚本

| 脚本 | 阶段 | 语义 |
| --- | --- | --- |
| `lua/quota_deduct.lua` | check | `used + pre + delta > limit` 返回 0（拒绝），否则 `pre += delta` |
| `lua/quota_adjust.lua` | report | `pre -= rollback`（减到 0 删 key），`used += actual`（首次创建设 TTL） |

## check 流程

```text
1. 鉴权（accessKey + secret 双向校验，HMAC-SHA256 + pepper）
2. 校验 Team / User 状态
3. Team 规则：PREDUCT 逐条 Lua 原子预扣 / CHECK_ONLY 只读检查
   任一失败 → 429 TEAM_QUOTA_EXCEEDED（PREDUCT 先回滚已预扣规则）
4. 按 User.quota_mode 决定抵扣来源：
   - PERSONAL_ONLY：User 规则预扣/检查，失败 → 429 USER_QUOTA_EXCEEDED
   - TEAM_ONLY：consumeFrom = TEAM（不碰 User 规则）
   - PERSONAL_FIRST_THEN_TEAM：User 预扣失败 → 回滚 User 预扣，团队兜底
5. 保存 check 上下文（traceId → team/apiKeyId/user/model/预估/consumeFrom/规则/模式）
```

## report 流程

```text
1. 读取 check 上下文（缺失 → TRACE_NOT_FOUND）
2. 判定 usage_source（PROVIDER / ESTIMATED）与异常检测（偏差 > 50% 标记 anomaly）
3. 先写 MySQL usage_log（事实来源，异步）
4. 再更新 Redis（PREDUCT：settle 回滚预扣 + 累加真实值；CHECK_ONLY：仅累加）
5. 删除 check 上下文
```

`consumeFrom=PERSONAL` 时同时结算 Team 与 User 规则；`=TEAM` 时仅结算 Team（团队兜底，个人额度不动）。

## 与速率限制的区别

- **速率限制**（rate limit）：限制每秒请求次数（`tokenlimit.rate-limit`，默认关闭）。
- **配额控制**（quota）：限制累计/总可用资源量（token / 费用 / 次数）。
