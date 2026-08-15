# 对账机制

## 背景

配额消耗可能发生在：

- **服务端**：通过 `/quota/consume` 实时扣减。
- **客户端本地缓存**：SDK 启用本地缓存时，先本地扣减，再异步/批量回传。
- **异步上报**：流式输出等场景，消耗在结束后结算。

上述路径可能导致服务端与客户端对用量统计不一致，需要对账。

## 记录模型

每条消耗记录（`quota_usage`）包含：

```
id, namespace, dimension, tokens, source(server/client/callback),
request_id, status(pending/settled/reconciled), created_at, settled_at
```

## 对账流程

```
客户端本地消耗 ──▶ 生成 usage 记录(request_id)
                        │
                        ▼
             批量上报 /quota/usage/batch
                        │
        ┌───────────────▼────────────────┐
        │ 服务端按 request_id 去重        │
        │ 已存在：忽略（幂等）            │
        │ 不存在：入账并更新用量统计      │
        └───────────────┬────────────────┘
                        │
                        ▼
                定时任务对账（每日）
                        │
        ┌───────────────▼────────────────┐
        │ 服务端记录 vs 客户端上报          │
        │ 不一致 → 标记 suspicious        │
        │ 一致   → 标记 reconciled        │
        └─────────────────────────────────┘
```

## 幂等性

- 每次消耗生成唯一 `request_id`（客户端侧 UUID）。
- 服务端以 `request_id` 为唯一键，重复上报自动忽略。

## 差异处理

| 差异类型 | 处理策略 |
| --- | --- |
| 客户端上报缺失 | 以服务端记录为准，标记客户端缺失 |
| 服务端缺失 | 以客户端上报为准，补录 |
| 金额/数量不一致 | 标记 `suspicious`，人工复核 |

## 补偿

- 超时未上报的本地消耗，客户端在下一个批次重试。
- 重试达到上限仍未上报的，进入对账待处理队列。

## 统计口径

- **实时**：以服务端 `/quota/consume` 成功扣减为准。
- **最终一致**：以每日对账完成后的 `quota_usage` 结算数据为准。

---

## 对账中心（PRD Phase 4，已实现）

上述为 V1 客户端/服务端幂等对账机制；V2 对账中心则面向**供应商计费**：

### 数据模型

| 表 | 说明 |
| --- | --- |
| `tl_model_price` | 模型价格（供应商/模型/输入输出单价），成本核算基准 |
| `tl_vendor_bill` | 供应商账单（账单日期/供应商/模型/供应商 Tokens/成本），对账比对基准 |
| `tl_reconcile_task` | 对账任务（状态：PENDING/RUNNING/COMPLETED/FAILED） |
| `tl_reconcile_item` | 对账明细（我方 vs 供应商 Tokens/成本差异、差异率、状态） |

### 对账执行流程

1. 创建对账任务（账单日期 + 供应商），初始状态 `PENDING`；
2. 执行任务：从 `tl_usage_log` 按 `(provider, model)` 聚合当日 `SUCCESS` 用量（我方 Tokens/成本）；
3. 与 `tl_vendor_bill` 同日期、同供应商、同模型的账单对比；
4. 计算差异与差异率：`token_diff = provider - our`，差异率 = `|diff| / provider`（供应商为 0 时按差异是否非零处理）；
5. 差异率 > 3%（tokens 或成本任一）判定为 `DIFFERENCE`，否则 `CONSISTENT`；
6. 汇总任务统计（明细总数、差异数、平均差异率）并置为 `COMPLETED`。

### 状态流转

- 任务：`PENDING → RUNNING → COMPLETED`（失败置 `FAILED`）；
- 明细：`CONSISTENT / DIFFERENCE`，差异项可**发起争议**（→ `DISPUTED`）。

### 管理端 API

| 资源 | 端点 |
| --- | --- |
| 对账任务 | `GET/POST /api/v1/admin/reconciles`、`GET/DELETE /{id}`、`POST /{id}/execute`、`GET /{id}/items`、`PUT /items/{id}/status`、`GET /stats` |
| 供应商账单 | `GET/POST /api/v1/admin/vendor-bills`、`POST /batch`、`GET/PUT/DELETE /{id}` |
| 模型价格 | `GET/POST /api/v1/admin/model-prices`、`GET/PUT/DELETE /{id}`、`PUT /{id}/status` |

### 统计卡片

对账中心提供：本月对账任务数、发现差异数（>3%）、待处理争议数、平均差异率（最近已完成任务）。
