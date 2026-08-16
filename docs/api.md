# API 文档

Base URL: `/api`

所有接口返回统一结构：

```json
{
  "code": 0,
  "message": "success",
  "data": {}
}
```

- `code == 0` 表示成功
- `code != 0` 表示失败，见[错误码](#错误码)

认证方式：`Authorization: Bearer <access_key>:<secret>`

> API Key 为两段式凭证：`accessKey`（`tl_ak_xxx`，公开标识）+ `secret`（`sk_tl_xxx`，仅创建/重置时显示一次）。客户端将两段用冒号拼接为单个字符串，网关按第一个冒号拆分后双向校验；兼容 Cursor 等只支持单个 API Key 的客户端。

---

## 认证

### POST `/auth/login`

登录并获取 token。

```json
{ "username": "admin", "password": "xxx" }
```

响应 `data`：

```json
{ "token": "...", "username": "admin" }
```

### GET `/auth/profile`

获取当前用户信息。

---

## 额度管理

### GET `/quota/list`

查询所有命名空间额度配置。

### POST `/quota`

创建额度配置。

```json
{
  "namespace": "product-a",
  "planName": "基础版",
  "maxTokens": 100000,
  "refillRate": 100,
  "burst": 1000,
  "enabled": true
}
```

### PUT `/quota`

更新额度配置（需 `id`）。

### DELETE `/quota/{id}`

删除额度配置。

### POST `/quota/consume`

消耗 token。

```json
{ "namespace": "product-a", "tokens": 100, "dimension": "gpt-4o" }
```

响应 `data`：

```json
{ "allowed": true, "remaining": 99900 }
```

### GET `/quota/status?namespace=xxx`

查询命名空间当前配额状态。

### POST `/quota/reset`

重置命名空间配额。

```json
{ "namespace": "product-a", "dimension": "gpt-4o" }
```

---

## 统计

### GET `/dashboard/stat`

概览统计。

```json
{
  "totalNamespaces": 10,
  "totalQuotas": 12,
  "totalConsumed": 123456,
  "activeNamespaces": 8
}
```

### GET `/dashboard/trend`

用量趋势。

```json
[{ "date": "2026-08-01", "value": 1000 }]
```

---

## 错误码

| code | 含义 |
| --- | --- |
| 4000 | 参数错误 |
| 4010 | 认证失败 |
| 4020 | 无权限 |
| 4029 | 配额超限 |
| 5000 | 服务端内部错误 |
