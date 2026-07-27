"""repo-scout REST 客户端:唯一 HTTP 出口,只调公开契约端点。

- 凭据只从 Credentials 注入 header,日志与异常文本不含 key;
- 401 fail-fast;429/502/网络错误有限重试;确定性 4xx 不重试;
- 契约见 docs/api.md。
"""

from __future__ import annotations

import logging
import random
import time
from dataclasses import dataclass
from types import TracebackType
from typing import Any

import httpx

from .config import Credentials, RetryPolicy
from .models import ChatResponse, IndexStatusResponse, RepoResponse, ReportResponse

log = logging.getLogger("repo_scout_eval.client")

CHAT_PATH = "/api/chat"
HEALTH_PATH = "/api/health"
REPOS_PATH = "/api/repos"


class UnauthorizedError(Exception):
    """门禁开启但 key 缺失/不匹配。必须 fail-fast,且不打印 key。"""

    def __init__(self) -> None:
        super().__init__(
            "后端返回 401 UNAUTHORIZED:请检查评测客户端与服务端的内部门禁配置"
            "(环境变量 REPO_SCOUT_INTERNAL_KEY 是否与服务端 INTERNAL_API_KEY 一致)"
        )


class ApiCallError(Exception):
    """重试耗尽后的失败;携带最后一次的状态码与统一错误码。"""

    def __init__(self, message: str, status: int | None, code: str | None, retries: int) -> None:
        super().__init__(message)
        self.status = status
        self.code = code
        self.retries = retries


@dataclass(frozen=True)
class ApiResult:
    """一次调用的原始结果与观测数据。"""

    status: int
    payload: dict[str, Any]
    latency_ms: int
    retry_count: int

    @property
    def error_code(self) -> str | None:
        code = self.payload.get("code")
        return code if isinstance(code, str) and self.status >= 400 else None

    @property
    def error_message(self) -> str | None:
        message = self.payload.get("message")
        return message if isinstance(message, str) and self.status >= 400 else None


class RepoScoutClient:
    """同步客户端。低并发场景下无需 async,便于串行会话严格排序。"""

    def __init__(
        self,
        base_url: str,
        credentials: Credentials,
        retry: RetryPolicy | None = None,
        transport: httpx.BaseTransport | None = None,
        sleep: Any = time.sleep,
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._retry = retry or RetryPolicy()
        self._sleep = sleep
        self._client = httpx.Client(
            base_url=self._base_url,
            timeout=credentials.timeout_s,
            headers={"Accept": "application/json", **credentials.headers()},
            transport=transport,
        )

    def __enter__(self) -> RepoScoutClient:
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        self.close()

    def close(self) -> None:
        self._client.close()

    # --- 端点封装 ---

    def health(self) -> ApiResult:
        return self._request("GET", HEALTH_PATH, label="health")

    def register_repo(self, repo: str) -> RepoResponse:
        result = self._require_ok(self._request("POST", REPOS_PATH, json={"repo": repo}, label="repos"))
        return RepoResponse.model_validate(result.payload)

    def index_status(self, repo_id: int) -> IndexStatusResponse:
        path = f"{REPOS_PATH}/{repo_id}/index-status"
        result = self._require_ok(self._request("GET", path, label="index-status"))
        return IndexStatusResponse.model_validate(result.payload)

    def trigger_index(self, repo_id: int) -> dict[str, Any]:
        path = f"{REPOS_PATH}/{repo_id}/index"
        return self._require_ok(self._request("POST", path, label="index")).payload

    def chat(
        self,
        message: str,
        session_id: str | None = None,
        repo_id: int | None = None,
        label: str = "chat",
    ) -> tuple[ApiResult, ChatResponse | None]:
        """返回原始结果与解析后的响应;失败时第二项为 None,由调用方记录错误。"""
        body: dict[str, Any] = {"message": message}
        if session_id:
            body["sessionId"] = session_id
        if repo_id is not None:
            body["repoId"] = repo_id
        result = self._request("POST", CHAT_PATH, json=body, label=label)
        if result.status != 200:
            return result, None
        return result, ChatResponse.model_validate(result.payload)

    def report(self, repo_id: int, label: str = "report") -> tuple[ApiResult, ReportResponse | None]:
        path = f"{REPOS_PATH}/{repo_id}/report"
        result = self._request("POST", path, label=label)
        if result.status != 200:
            return result, None
        return result, ReportResponse.model_validate(result.payload)

    # --- 内部 ---

    def _require_ok(self, result: ApiResult) -> ApiResult:
        if result.status != 200:
            raise ApiCallError(
                f"接口返回 {result.status} {result.error_code or ''}: {result.error_message or '无消息'}",
                result.status,
                result.error_code,
                result.retry_count,
            )
        return result

    def _request(self, method: str, path: str, label: str, json: dict[str, Any] | None = None) -> ApiResult:
        started = time.perf_counter()
        retries = 0
        last_error: str = "未知错误"
        last_status: int | None = None
        while True:
            try:
                response = self._client.request(method, path, json=json)
            except httpx.HTTPError as exc:
                last_error = f"网络错误: {type(exc).__name__}"
                last_status = None
            else:
                if response.status_code == 401:
                    raise UnauthorizedError
                payload = _decode(response)
                if not self._should_retry(response.status_code):
                    latency_ms = int((time.perf_counter() - started) * 1000)
                    return ApiResult(response.status_code, payload, latency_ms, retries)
                last_status = response.status_code
                code = payload.get("code")
                last_error = f"HTTP {response.status_code} {code if isinstance(code, str) else ''}".strip()
            if retries + 1 >= self._retry.max_attempts:
                raise ApiCallError(
                    f"{label} 调用失败({self._retry.max_attempts} 次尝试): {last_error}",
                    last_status,
                    None,
                    retries,
                )
            delay = self._retry.backoff_s + random.random() * self._retry.jitter_s
            log.warning("重试 %s: attempt=%s reason=%s delay=%.1fs", label, retries + 1, last_error, delay)
            self._sleep(delay)
            retries += 1

    def _should_retry(self, status: int) -> bool:
        return status in set(self._retry.retry_statuses)


def _decode(response: httpx.Response) -> dict[str, Any]:
    """错误体与成功体都可能非 JSON(代理层),统一降级为可读结构。"""
    try:
        data = response.json()
    except ValueError:
        return {"code": None, "message": f"响应非 JSON(前 200 字符): {response.text[:200]}"}
    if isinstance(data, dict):
        return data
    return {"payload": data}
