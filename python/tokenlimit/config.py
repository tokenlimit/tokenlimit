"""客户端配置."""

from __future__ import annotations

import os
from dataclasses import dataclass, field


@dataclass
class TokenLimitConfig:
    """Token Limit 客户端配置.

    Attributes:
        base_url: 服务端地址，如 http://localhost:8080
        api_key: API Key access key（tl_&lt;ns&gt;_ak_xxx）
        api_secret: API Key secret，用于双向校验（Bearer &lt;access_key&gt;:&lt;secret&gt;）
        timeout: 请求超时时间（秒）
        max_retries: 最大重试次数
        retry_backoff: 重试退避基数（秒）
        enable_local_cache: 是否启用本地配额缓存
        cache_ttl: 本地缓存有效期（秒）
        cache_capacity: 本地缓存容量
    """

    base_url: str = field(
        default_factory=lambda: os.environ.get("TOKENLIMIT_BASE_URL", "http://localhost:8080")
    )
    api_key: str = field(default_factory=lambda: os.environ.get("TOKENLIMIT_API_KEY", ""))
    api_secret: str = field(default_factory=lambda: os.environ.get("TOKENLIMIT_API_SECRET", ""))
    timeout: float = 15.0
    max_retries: int = 3
    retry_backoff: float = 0.5
    enable_local_cache: bool = True
    cache_ttl: int = 30
    cache_capacity: int = 1024
