"""Python SDK 示例."""

import os

from tokenlimit import TokenLimitClient, quota


def main() -> None:
    client = TokenLimitClient(
        base_url=os.environ.get("TOKENLIMIT_BASE_URL", "http://localhost:8080"),
        api_key=os.environ.get("TOKENLIMIT_API_KEY", "test-key"),
        api_secret=os.environ.get("TOKENLIMIT_API_SECRET", ""),
    )

    # 方式一：直接调用
    result = client.consume(namespace="product-a", tokens=100)
    print("consume result:", result)

    # 方式二：查询状态
    status = client.query(namespace="product-a")
    print("status:", status)

    client.close()


# 方式三：装饰器
@quota(namespace="product-b", tokens=50)
def run_llm() -> str:
    return "LLM 结果"


if __name__ == "__main__":
    main()
    print("decorator result:", run_llm())
