# TokenLimit Java Server

企业大模型 Token 预算网关与 AI FinOps 平台 - Java 后端服务

## 📖 项目简介

TokenLimit 是一个专注于企业大模型调用治理的网关服务，提供：
- **OpenAI 兼容代理**：统一接入 DeepSeek、OpenAI、通义千问、智谱、Kimi 等厂商
- **配额控制与事前拦截**：Team/User 余额不足时直接拦截，防止账单爆炸
- **Token 计量与预算管理**：精确到分钟的用量统计和预算控制
- **峰谷定价策略**：支持时间动态定价，引导非实时任务调度至低谷时段，节省 30-50% 成本
- **多租户支持**：Team 成本中心隔离，User 责任到人

## 🏗️ 技术架构

- **运行环境**: Java 21+
- **核心框架**: Spring Boot 3.x
- **ORM**: MyBatis-Plus
- **缓存**: Redis (配额扣减、预扣管理)
- **数据库**: MySQL 8.0+
- **Token 计算**: jtokkit (预估输入输出 Token)
- **安全**: JWT + BCrypt 密码加密

## 🚀 快速开始

### 方式一：单机模式（零依赖，推荐开发测试）

无需安装 MySQL 和 Redis，一键启动体验！

**Linux/Mac:**
```bash
cd tokenlimit-java/scripts
./startup.sh
```

**Windows:**
```cmd
cd tokenlimit-java\scripts
startup.bat
```

启动成功后访问：
- 健康检查：http://localhost:8080/health
- API 文档：http://localhost:8080/swagger-ui.html
- 管理控制台：http://localhost:8080/console/

停止服务：
```bash
./shutdown.sh    # Linux/Mac
shutdown.bat     # Windows
```

### 方式二：生产模式（MySQL + Redis）

**Linux/Mac:**
```bash
export TL_DB_MODE=mysql
export TL_DB_HOST=localhost
export TL_DB_PORT=3306
export TL_DB_NAME=tokenlimit
export TL_DB_USER=root
export TL_DB_PASSWORD=your_password

export TL_REDIS_MODE=external
export TL_REDIS_HOST=localhost
export TL_REDIS_PORT=6379
export TL_REDIS_PASSWORD=

cd tokenlimit-java/scripts
./startup.sh
```

**Windows:**
```cmd
set TL_DB_MODE=mysql
set TL_DB_HOST=localhost
set TL_DB_PORT=3306
set TL_DB_NAME=tokenlimit
set TL_DB_USER=root
set TL_DB_PASSWORD=your_password

set TL_REDIS_MODE=external
set TL_REDIS_HOST=localhost
set TL_REDIS_PORT=6379
set TL_REDIS_PASSWORD=

cd tokenlimit-java\scripts
startup.bat
```

### 方式三：Docker Compose（推荐生产环境）

```bash
cd deploy
docker-compose up -d
```

详见 [Docker 部署指南](#-docker-部署)。

## 📁 项目结构

```
tokenlimit-java/
├── src/main/java/com/tokenlimit/server/
│   ├── controller/
│   │   ├── admin/          # 管理端接口 (/api/admin/**)
│   │   │   ├── AdminAuthController.java       # 登录认证
│   │   │   ├── AdminTeamController.java       # Team 管理
│   │   │   ├── AdminUserController.java       # User 管理
│   │   │   ├── AdminApiKeyController.java     # API Key 管理
│   │   │   ├── AdminModelPriceController.java # 模型价格管理 (含峰谷配置)
│   │   │   └── AdminUsageController.java      # 用量统计
│   │   ├── ProxyGatewayController.java        # OpenAI 兼容代理 (/v1/**)
│   │   └── HealthController.java              # 健康检查 (/health)
│   ├── service/
│   │   ├── PriceCalculatorService.java        # 计费引擎 (峰谷定价核心)
│   │   ├── QuotaService.java                  # 配额扣减与预扣管理
│   │   ├── TokenEstimationService.java        # Token 预估
│   │   └── ...
│   ├── entity/
│   │   ├── ModelPrice.java                    # 模型价格实体 (含峰谷字段)
│   │   ├── UsageLog.java                      # 用量日志 (含价格快照)
│   │   ├── Team.java                          # Team 实体
│   │   ├── User.java                          # User 实体
│   │   └── ...
│   ├── security/
│   │   ├── JwtAuthenticationFilter.java       # JWT 鉴权过滤器
│   │   └── JwtUtil.java                       # JWT 工具类
│   └── config/
│       ├── SecurityConfig.java                # 安全配置
│       └── CorsConfig.java                    # 跨域配置
├── src/main/resources/
│   ├── application.yml                        # 应用配置
│   └── mapper/                                # MyBatis XML
├── deploy/
│   └── migration/                             # 数据库迁移脚本
└── README.md
```

## 🔑 核心功能

### 1. OpenAI 兼容代理

支持标准 OpenAI SDK 直接调用：

```python
from openai import OpenAI

client = OpenAI(
    base_url="http://localhost:8080/v1",
    api_key="your-api-key"  # 格式：sk-<API_KEY_ID>.<SECRET>
)

response = client.chat.completions.create(
    model="deepseek-chat",
    messages=[{"role": "user", "content": "Hello"}]
)
```

### 2. 峰谷定价策略 (V5.5 新增)

在管理控制台配置峰谷规则：

```json
{
  "modelId": "deepseek-chat",
  "pricingType": "PEAK_OFF_PEAK",
  "inputPricePerToken": 0.000001,
  "outputPricePerToken": 0.000004,
  "peakMultiplier": 1.0,
  "offPeakMultiplier": 0.5,
  "offPeakStart": "22:00:00",
  "offPeakEnd": "08:00:00"
}
```

系统自动根据请求时间计算折扣，低谷时段 (22:00-08:00) 享受 5 折优惠。

### 3. 配额控制

- **事前拦截**: 请求前检查 Team/User 余额，不足直接返回 402 Payment Required
- **预扣机制**: 基于预估 Token 预扣配额，防止超额调用
- **事后结算**: 根据实际用量多退少补

### 4. 用量统计

提供多维度用量查询：
- 按 Team/User/API Key 分组
- 按时间范围聚合 (日/周/月)
- 按模型/Provider 统计
- 峰谷时段对比分析

## 🔐 安全配置

### JWT 鉴权

管理端接口 (`/api/admin/**`) 需要 JWT Token：

```bash
# 登录获取 Token
curl -X POST http://localhost:8080/api/admin/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'

# 使用 Token 访问管理接口
curl http://localhost:8080/api/admin/teams \
  -H "Authorization: Bearer <token>"
```

### 路径白名单

以下路径跳过 JWT 鉴权：
- `/v1/**` - OpenAI 兼容代理 (使用 API Key 鉴权)
- `/health` - 健康检查
- `/api/admin/auth/login` - 登录接口
- `/api/admin/auth/register` - 注册接口

## 📊 监控与运维

### 健康检查

```bash
curl http://localhost:8080/health
# 返回: {"status":"UP","timestamp":"2025-01-15T10:30:00"}
```

### K8s 探针配置

```yaml
livenessProbe:
  httpGet:
    path: /health
    port: 8080
  initialDelaySeconds: 30
  periodSeconds: 10

readinessProbe:
  httpGet:
    path: /health
    port: 8080
  initialDelaySeconds: 10
  periodSeconds: 5
```

### 日志管理

应用日志输出到 `logs/tokenlimit.log`，支持按天滚动：

```yaml
logging:
  file:
    name: logs/tokenlimit.log
  logback:
    rollingpolicy:
      max-file-size: 100MB
      max-history: 30
```

## 🧪 测试

```bash
# 运行单元测试
mvn test

# 运行集成测试
mvn verify

# 生成覆盖率报告
mvn jacoco:report
```

## 📦 Docker 部署

```bash
# 构建镜像
docker build -t tokenlimit/server:latest .

# 运行容器
docker run -d \
  --name tokenlimit-server \
  -p 8080:8080 \
  -e SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/tokenlimit \
  -e SPRING_DATA_REDIS_HOST=redis \
  tokenlimit/server:latest
```

完整 Docker Compose 配置参考 `/deploy/docker-compose.yml`。

## 🔄 版本历史

- **V5.5** (当前): 峰谷定价策略、路径结构优化、健康检查标准化
- **V5.0**: 基础计费引擎、配额控制、OpenAI 兼容代理
- **V4.0**: 多供应商支持、Team/User 管理体系

## 🤝 贡献指南

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/amazing-feature`)
3. 提交变更 (`git commit -m 'Add amazing feature'`)
4. 推送到分支 (`git push origin feature/amazing-feature`)
5. 提交 Pull Request

## 📄 许可证

Apache License 2.0 - 详见 [LICENSE](../LICENSE)

## 📞 联系方式

- 官网：https://tokenlimit.com
- 邮箱：support@tokenlimit.com
- 文档：https://docs.tokenlimit.com

---

**让每一分 AI 支出都可控、可视、可优化！** 🚀
