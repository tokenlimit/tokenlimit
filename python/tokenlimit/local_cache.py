"""本地配额缓存，减少对服务端的请求压力."""

from __future__ import annotations

import threading
import time
from dataclasses import dataclass
from typing import Optional

from cachetools import TTLCache


@dataclass
class LocalQuota:
    """本地缓存的配额状态."""

    remaining: int
    expires_at: float

    @property
    def expired(self) -> bool:
        return self.expires_at < time.time()


class LocalCache:
    """基于 TTLCache 的线程安全本地缓存."""

    def __init__(self, ttl: int = 30, capacity: int = 1024) -> None:
        self._cache: TTLCache[str, LocalQuota] = TTLCache(maxsize=capacity, ttl=ttl)
        self._lock = threading.RLock()

    def get(self, key: str) -> Optional[LocalQuota]:
        with self._lock:
            return self._cache.get(key)

    def set(self, key: str, quota: LocalQuota) -> None:
        with self._lock:
            self._cache[key] = quota

    def remove(self, key: str) -> None:
        with self._lock:
            self._cache.pop(key, None)

    def clear(self) -> None:
        with self._lock:
            self._cache.clear()

    @property
    def size(self) -> int:
        with self._lock:
            return len(self._cache)
