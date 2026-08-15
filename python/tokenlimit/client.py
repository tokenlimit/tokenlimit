"""客户端主类."""

from __future__ import annotations

import time
from typing import Any

from .config import TokenLimitConfig
from .exceptions import QuotaExceededError
from .local_cache import LocalCache, LocalQuota
from .transport import Transport


class TokenLimitClient:
    """Token Limit 客户端.

    支持直接调用与本地配额缓存两种模式。
    """

    def __init__(self, config: TokenLimitConfig | None = None, **kwargs: Any) -> None:
        self.config = config or TokenLimitConfig(**kwargs)
        self._transport = Transport(
            base_url=self.config.base_url,
            api_key=self.config.api_key,
            api_secret=self.config.api_secret,
            timeout=self.config.timeout,
            max_retries=self.config.max_retries,
            retry_backoff=self.config.retry_backoff,
        )
        self._cache = LocalCache(
            ttl=self.config.cache_ttl,
            capacity=self.config.cache_capacity,
        )

    def consume(self, namespace: str, tokens: int, dimension: str | None = None) -> dict[str, Any]:
        """消耗 token 配额.

        Args:
            namespace: 命名空间
            tokens: 消耗的 token 数量
            dimension: 可选维度

        Returns:
            服务端返回的配额状态，如 {"allowed": True, "remaining": 100}

        Raises:
            QuotaExceededError: 配额超限
        """
        body: dict[str, Any] = {"namespace": namespace, "tokens": tokens}
        if dimension:
            body["dimension"] = dimension
        return self._transport.request("POST", "/api/quota/consume", json_body=body)

    def query(self, namespace: str) -> dict[str, Any]:
        """查询命名空间当前配额状态."""
        return self._transport.request("GET", "/api/quota/status", params={"namespace": namespace})

    def consume_cached(
        self, namespace: str, tokens: int, dimension: str | None = None
    ) -> dict[str, Any]:
        """带本地缓存的消耗，降低服务端压力.

        缓存命中且剩余额度充足时直接本地扣减，否则回源。
        """
        key = self._cache_key(namespace, dimension)
        cached = self._cache.get(key)
        now = time.time()

        if cached is not None and not cached.expired:
            if cached.remaining >= tokens:
                cached.remaining -= tokens
                return {"allowed": True, "remaining": cached.remaining, "cached": True}
            return {"allowed": False, "remaining": cached.remaining, "cached": True}

        result = self.consume(namespace, tokens, dimension)
        remaining = int(result.get("remaining", 0))
        self._cache.set(key, LocalQuota(remaining=remaining, expires_at=now + self.config.cache_ttl))
        return {**result, "cached": False}

    def reset(self, namespace: str, dimension: str | None = None) -> dict[str, Any]:
        """重置命名空间配额."""
        body: dict[str, Any] = {"namespace": namespace}
        if dimension:
            body["dimension"] = dimension
        result = self._transport.request("POST", "/api/quota/reset", json_body=body)
        self._cache.remove(self._cache_key(namespace, dimension))
        return result

    def close(self) -> None:
        self._transport.close()

    @staticmethod
    def _cache_key(namespace: str, dimension: str | None) -> str:
        return f"{namespace}:{dimension or 'default'}"

    def __enter__(self) -> "TokenLimitClient":
        return self

    def __exit__(self, *exc: Any) -> None:
        self.close()
