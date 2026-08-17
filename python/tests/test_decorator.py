"""装饰器测试."""

from unittest.mock import Mock

import pytest

from tokenlimit.decorator import quota
from tokenlimit.exceptions import QuotaExceededError


def test_quota_success():
    client = Mock()
    client.consume_cached.return_value = {"allowed": True, "remaining": 99}

    @quota(namespace="demo", tokens=1, client=client)
    def fn():
        return "ok"

    assert fn() == "ok"
    client.consume_cached.assert_called_once_with("demo", 1, None)


def test_quota_exceeded():
    client = Mock()
    client.consume_cached.return_value = {"allowed": False, "remaining": 0}

    @quota(namespace="demo", tokens=100, client=client)
    def fn():
        return "ok"

    with pytest.raises(QuotaExceededError):
        fn()
