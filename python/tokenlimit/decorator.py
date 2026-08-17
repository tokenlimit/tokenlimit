"""配额装饰器."""

from __future__ import annotations

import functools
import threading
from typing import Any, Callable, TypeVar, cast

from .client import TokenLimitClient
from .exceptions import QuotaExceededError

F = TypeVar("F", bound=Callable[..., Any])

_client_singleton: TokenLimitClient | None = None
_client_lock = threading.Lock()


def get_default_client() -> TokenLimitClient:
    """获取全局默认客户端（懒加载单例）."""
    global _client_singleton
    with _client_lock:
        if _client_singleton is None:
            _client_singleton = TokenLimitClient()
        return _client_singleton


def set_default_client(client: TokenLimitClient) -> None:
    """设置全局默认客户端."""
    global _client_singleton
    _client_singleton = client


def quota(
    namespace: str,
    *,
    tokens: int = 1,
    dimension: str | None = None,
    client: TokenLimitClient | None = None,
) -> Callable[[F], F]:
    """在函数调用前消耗指定数量的 token 配额.

    Args:
        namespace: 命名空间
        tokens: 每次调用消耗的 token 数量
        dimension: 可选维度
        client: 指定客户端，默认使用全局单例

    Usage:
        @quota(namespace="product-a", tokens=100)
        def run_llm():
            ...
    """

    def decorator(func: F) -> F:
        @functools.wraps(func)
        def wrapper(*args: Any, **kwargs: Any) -> Any:
            c = client or get_default_client()
            result = c.consume_cached(namespace, tokens, dimension)
            if not result.get("allowed", False):
                raise QuotaExceededError(
                    f"配额超限: {namespace}, remaining={result.get('remaining')}",
                    remaining=result.get("remaining"),
                )
            return func(*args, **kwargs)

        return cast(F, wrapper)

    return decorator
