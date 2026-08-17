# tokenlimit

Token Limit 服务的 Python 客户端 SDK。

## 安装

```bash
pip install -e .
```

## 快速开始

```python
from tokenlimit import TokenLimitClient

client = TokenLimitClient(
    base_url="http://localhost:8080",
    api_key="your-api-key",
)

# 直接调用
client.consume(namespace="product-a", tokens=100)

# 或使用装饰器
from tokenlimit import quota

@quota(namespace="product-a", max_tokens=1000)
def run_llm():
    return "result"
```

## 开发

```bash
pip install -e ".[dev]"
pytest
```
