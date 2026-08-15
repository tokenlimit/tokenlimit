"""HTTP 传输层."""

from __future__ import annotations

import json
import time
from typing import Any

import requests

from .exceptions import AuthenticationError, QuotaExceededError, ServerError, TokenLimitError


class Transport:
    """基于 requests 的同步 HTTP 客户端."""

    def __init__(
        self,
        base_url: str,
        api_key: str,
        api_secret: str = "",
        timeout: float = 15.0,
        max_retries: int = 3,
        retry_backoff: float = 0.5,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.api_secret = api_secret
        self.timeout = timeout
        self.max_retries = max_retries
        self.retry_backoff = retry_backoff
        self._session = requests.Session()

    def request(
        self,
        method: str,
        path: str,
        *,
        json_body: dict[str, Any] | None = None,
        params: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        """发送请求并在失败时重试."""
        url = f"{self.base_url}{path}"
        headers = {"Content-Type": "application/json"}
        if self.api_key:
            credential = f"{self.api_key}:{self.api_secret}" if self.api_secret else self.api_key
            headers["Authorization"] = f"Bearer {credential}"

        last_error: Exception | None = None
        for attempt in range(self.max_retries + 1):
            try:
                resp = self._session.request(
                    method,
                    url,
                    json=json_body,
                    params=params,
                    headers=headers,
                    timeout=self.timeout,
                )
                return self._parse(resp)
            except TokenLimitError:
                raise
            except requests.RequestException as exc:
                last_error = exc
                if attempt < self.max_retries:
                    time.sleep(self.retry_backoff * (2**attempt))
                    continue
        raise ServerError(f"请求失败: {last_error}")

    def _parse(self, resp: requests.Response) -> dict[str, Any]:
        try:
            payload = resp.json()
        except ValueError:
            raise ServerError(f"响应解析失败: {resp.status_code}") from None

        code = payload.get("code", -1)
        message = payload.get("message", "unknown error")

        if resp.status_code == 401:
            raise AuthenticationError(message, code=code)
        if resp.status_code == 429 or code == 4029:
            raise QuotaExceededError(message, remaining=payload.get("remaining"))
        if code != 0:
            raise TokenLimitError(message, code=code)
        return payload.get("data", {})

    def close(self) -> None:
        self._session.close()


def _dump(body: dict[str, Any]) -> str:
    return json.dumps(body, ensure_ascii=False)
