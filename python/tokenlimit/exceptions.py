"""异常定义."""


class TokenLimitError(Exception):
    """Token Limit 基础异常."""

    def __init__(self, message: str, code: int | None = None) -> None:
        super().__init__(message)
        self.message = message
        self.code = code


class AuthenticationError(TokenLimitError):
    """认证失败."""


class QuotaExceededError(TokenLimitError):
    """配额超限."""

    def __init__(self, message: str, remaining: int | None = None) -> None:
        super().__init__(message, code=4029)
        self.remaining = remaining


class ServerError(TokenLimitError):
    """服务端错误."""
