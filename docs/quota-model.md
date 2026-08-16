# 配额模型（V5.2）

Token Limit 使用 **责任链拦截 + Redis 双 key（balance / pre）** 的配额模型，**无需 Lua 脚本**，预计算开关可配置。

## 1. 总体流程

```text
调用大模型前（check）：
  1. 责任链拦截（tokenlimit.quota-chain 可配置顺序/裁剪，任一拦截即拒绝）：
     team-balance   团队余额拦截（TOTAL 周期长期规则）
     user-balance   个人余额拦截（TOTAL 周期长期规则，并确定抵扣来源 consumeFrom）
     usage-period   周期用量拦截（MONTH/WEEK/DAY/HOUR/MINUTE 规则，含"每次请求" REQUEST_COUNT 限次）
  2. 预计算开关（tokenlimit.quota-precompute-enabled，默认开启）：
     开启：对适用规则按 jtokkit 预估量原子预扣（INCRBY pre），预扣值凭空写入
     关闭：不预扣，仅判断余额

调用大模型结束后（report）：
  1. 写 MySQL usage_log（事实来源，余额变更发生在此刻）
  2. 预计算开启：回滚预扣（DECRBY pre，减到 0 删 key）
  3. 按厂商返回真实 token 原子扣减余额（DECRBY balance）
```

## 2. Redis 双 key（均缓存 Long 值，key 含 targetCode 即 userId/teamId）

| key 段 | 语义 | 来源 | 写入时机 |
| --- | --- | --- | --- |
| `balance` | 真实余额 = limit - 真实用量 | 真实用量来自 MySQL usage_log 聚合 | 首次访问时计算写入缓存（惰性初始化）；report 阶段原子扣减保持实时；周期 TTL 滚动重建 |
| `pre` | 进行中请求的预扣总量 | 本次请求按预估量计算得出（凭空写入） | check 阶段原子 INCRBY；report 阶段 DECRBY 回滚 |

Key 结构：

```text
{prefix}:quota:{balance|pre}:{targetType}:{targetCode}:{limitType}:{period}:{timeKey}
```

示例：

```text
tokenlimit:quota:balance:team:team-rd:TOKEN:DAY:20260813
tokenlimit:quota:pre:team:team-rd:TOKEN:DAY:20260813
tokenlimit:quota:pre:user:user-001:TOKEN:WEEK:2026W33
tokenlimit:quota:pre:team:team-rd:REQUEST_COUNT:HOUR:2026081614
```

`timeKey` 即周期时间片：MINUTE `yyyyMMddHHmm` / HOUR `yyyyMMddHH` / DAY `yyyyMMdd` / WEEK `yyyy'W'ww`（ISO 周）/ MONTH `yyyyMM` / TOTAL `total`。

## 3. 拦截判定（责任链拦截器）

每个规则均按预计算开关判定（预计算开关让每个拦截规则更精准前置 / 或后置容忍）：

```text
预计算开启（精准前置）：
  balance - pre - est_tokens >= 0  → 放行（==0 也放行，调用尚未发生，真实消耗以调用后 report 为准）
  balance - pre - est_tokens < 0   → 拦截（余额不足）

预计算关闭（后置容忍）：
  balance > 0   → 放行（不预扣、不减预估，余额变化在调用结束后才发生，==0 即无额度）
  balance <= 0  → 拦截
```

- `balance` 为真实余额：优先读 Redis 缓存；key 缺失/负值（首次访问、周期滚动、并发超支残留）时从 MySQL 聚合重建（`limit - 聚合用量`）。
- `pre` 为进行中请求的预扣总量（仅预计算开启时读取）。
- `est_tokens` 为本次预估量：TOKEN/COST 规则用 jtokkit 预估总 token，REQUEST_COUNT 规则为 1（仅预计算开启时参与判定）。

余额不足时返回拒绝（错误码 + 超限详情），责任链后续环节不再执行。

## 4. 预计算开关语义

| 开关 | check 行为 | report 行为 | 并发安全性 |
| --- | --- | --- | --- |
| 开启（默认，严格） | 检查通过后原子预扣预估量 | 回滚预扣 + 按真实值扣减余额 | 存在极小窗口超支 1 次调用（团队调用可能并发透支 Team 额度，可接受） |
| 关闭（宽松） | 仅判断余额不预扣 | 仅按真实值扣减余额 | 并发下最后几次请求可能同时放行（超卖） |

预扣值通过单个原子操作（INCRBY/DECRBY）控制，无需 Lua 脚本；并发下的窗口无法完全消除，属于已知取舍。

## 5. 抵扣来源（consumeFrom）

| quota_mode | consumeFrom | 预扣/结算范围 |
| --- | --- | --- |
| PERSONAL_ONLY | 个人余额充足 = PERSONAL，不足 = 拒绝 | User + Team 规则 |
| TEAM_ONLY | TEAM（跳过个人余额） | 仅 Team 规则 |
| PERSONAL_FIRST_THEN_TEAM | 个人余额充足 = PERSONAL，不足 = TEAM 兜底 | PERSONAL：User + Team；TEAM：仅 Team |

## 6. 预扣残留清理

- pre key TTL = 周期剩余时间，周期滚动后自动清零。
- check 上下文（含预扣记录）在 `check-context-ttl-seconds`（默认 3600 秒）后过期；超时未 report 的预扣残留随周期 key 过期自动清理。
- 进程崩溃导致的预扣残留同样由周期 TTL 兜底，下一周期从 MySQL 重新聚合。

## 7. Redis 故障降级

- 拦截器读余额/预扣异常时：`redis-fallback-enabled=true` 放行（可用性优先），`false` 抛出（一致性优先）。
- report 扣减异常：记录日志不中断流程（MySQL 已持久化，余额可从 MySQL 重新聚合恢复）。
- pre key 为本次请求凭空写入，无历史数据，无需迁移；旧 `used` key（V5.1）自然过期。
