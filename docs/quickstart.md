# 快速开始

## 环境要求

- JDK 17+
- Maven 3.8+
- Node.js 18+
- Python 3.9+
- Docker（可选，用于部署依赖）

## 1. 启动依赖（Redis / MySQL）

```bash
cd deploy
docker compose up -d
```

## 2. 启动服务端

```bash
cd java
mvn -pl tokenlimit-server -am spring-boot:run
```

服务默认运行在 `http://localhost:8080`。

## 3. 启动管理控制台（可选）

```bash
cd console
npm install
npm run dev
```

访问 `http://localhost:5173`。

## 4. 使用 Python SDK

```bash
cd python
pip install -e .

python -c "
from tokenlimit import TokenLimitClient
c = TokenLimitClient(base_url='http://localhost:8080', api_key='your-key')
print(c.consume('product-a', 100))
c.close()
"
```

## 5. 使用 Java SDK

见 `examples/java-demo` 与 `examples/springboot-demo`。

## 常见问题

- **配额超限**：错误码 `4029`，检查命名空间配额是否耗尽或补充速率过低。
- **认证失败**：错误码 `4010`，检查 `api_key` 是否有效。
