# 配额模型

## 概述

Token Limit 使用 **令牌桶算法** 控制每个命名空间的 token 消耗速率与总量。

## 令牌桶模型

```
        补充速率 refillRate（每秒补充 N 个 token）
                    │
                    ▼
            ┌───────────────┐
            │   令牌桶       │  容量 = burst
            │  tokens: X/总  │
            └──────┬────────┘
                   │
        每次 consume(namespace, tokens)
                   │
        ┌──────────▼──────────┐
        │  tokens 充足？        │
        │  X >= 请求 token     │
        └───┬─────────────┬───┘
         充足│          不足│
            ▼            ▼
      X -= tokens    返回 4029
      返回 remaining   配额超限
```

## 关键参数

| 参数 | 说明 | 默认 |
| --- | --- | --- |
| `maxTokens` | 桶的总容量（最大 token 数） | 由套餐决定 |
| `refillRate` | 每秒补充 token 数 | 由套餐决定 |
| `burst` | 突发容量，允许瞬时超出补充速率的额度 | 通常 = maxTokens |
| `dimension` | 维度，同命名空间下细分 | 可选 |

## Redis 实现

每个命名空间维护以下键：

```
tokenlimit:{namespace}:{dimension}:bucket   →  当前 token 数
tokenlimit:{namespace}:{dimension}:ts       →  上次补充时间戳
```

**补充逻辑**（Lua 脚本保证原子性）：

```
elapsed = now - ts
add = elapsed * refillRate
current = min(current + add, maxTokens)
ts = now
```

## 配额类型

| 类型 | 说明 |
| --- | --- |
| **单次消耗** | 每次调用消耗固定 token 数，如 LLM 输入 |
| **动态消耗** | 根据实际 token 数上报，如流式输出后结算 |

## 与速率限制的区别

- **速率限制**（rate limit）：限制每秒请求次数。
- **配额控制**（quota）：限制累计/总可用资源量。
- Token Limit 二者兼有：`refillRate` 控制补充速率（速率维度），`maxTokens` 控制总量（配额维度）。
