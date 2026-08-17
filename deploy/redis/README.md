# Redis

Token Limit 服务端依赖 Redis 存储运行时令牌状态。

通过 Docker Compose 启动：

```bash
cd deploy
docker compose up -d redis
```

默认连接地址：`localhost:6379`（无密码，生产环境请配置密码）。

## 键设计

```
tokenlimit:{namespace}:{dimension}:bucket   →  当前 token 数（令牌桶）
tokenlimit:{namespace}:{dimension}:ts       →  上次补充时间戳
```
