"""本地缓存单元测试."""

import time

from tokenlimit.local_cache import LocalCache, LocalQuota


def test_set_and_get():
    cache = LocalCache(ttl=30)
    cache.set("k", LocalQuota(remaining=100, expires_at=time.time() + 30))
    assert cache.get("k") is not None
    assert cache.get("k").remaining == 100  # type: ignore[union-attr]


def test_expired():
    cache = LocalCache(ttl=30)
    cache.set("k", LocalQuota(remaining=100, expires_at=time.time() - 1))
    assert cache.get("k") is None


def test_remove_and_clear():
    cache = LocalCache(ttl=30)
    cache.set("k", LocalQuota(remaining=100, expires_at=time.time() + 30))
    cache.remove("k")
    assert cache.get("k") is None

    cache.set("k1", LocalQuota(remaining=1, expires_at=time.time() + 30))
    cache.set("k2", LocalQuota(remaining=2, expires_at=time.time() + 30))
    cache.clear()
    assert cache.size == 0
