"""客户端测试."""

from tokenlimit.client import TokenLimitClient
from tokenlimit.config import TokenLimitConfig


def test_client_construction():
    client = TokenLimitClient(
        base_url="http://localhost:8080",
        api_key="test-key",
    )
    assert client.config.base_url == "http://localhost:8080"
    assert client.config.api_key == "test-key"
    client.close()


def test_config_defaults():
    config = TokenLimitConfig(base_url="http://x", api_key="k")
    assert config.timeout == 15.0
    assert config.max_retries == 3
    assert config.enable_local_cache is True


def test_cache_key():
    assert TokenLimitClient._cache_key("ns", None) == "ns:default"
    assert TokenLimitClient._cache_key("ns", "dim") == "ns:dim"
