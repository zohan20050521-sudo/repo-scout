"""API 客户端:成功、统一错误、401 fail-fast、502 有限重试、4xx 不重试。"""

from __future__ import annotations

import httpx
import pytest

from conftest import chat_payload, make_client
from repo_scout_eval.client import ApiCallError, UnauthorizedError
from repo_scout_eval.config import INTERNAL_KEY_HEADER, Credentials, RetryPolicy, SecretStr


def test_chat_success_parses_sources_and_citations(credentials: Credentials) -> None:
    payload = chat_payload(
        answer="根据 docs/api.md……",
        sources=["docs/api.md"],
        citations=[{"filePath": "docs/api.md", "chunkIndex": 3, "excerpt": "错误码", "score": 0.81}],
    )
    with make_client(lambda request: httpx.Response(200, json=payload), credentials) as client:
        result, response = client.chat("错误码有哪些?", repo_id=1)
    assert result.status == 200
    assert result.retry_count == 0
    assert response is not None
    assert response.sources == ["docs/api.md"]
    assert response.citations[0].score == pytest.approx(0.81)


def test_chat_sends_internal_key_header() -> None:
    seen: dict[str, str] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen.update(request.headers)
        return httpx.Response(200, json=chat_payload())

    creds = Credentials(internal_key=SecretStr("k-123"), timeout_s=5)
    with make_client(handler, creds) as client:
        client.chat("你好")
    assert seen[INTERNAL_KEY_HEADER.lower()] == "k-123"


def test_unauthorized_fails_fast_without_leaking_key() -> None:
    calls = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        return httpx.Response(401, json={"code": "UNAUTHORIZED", "message": "无权访问该接口"})

    creds = Credentials(internal_key=SecretStr("leak-me"), timeout_s=5)
    with make_client(handler, creds) as client, pytest.raises(UnauthorizedError) as excinfo:
        client.chat("你好")
    assert calls["n"] == 1, "401 必须 fail-fast,不重试"
    assert "leak-me" not in str(excinfo.value)
    assert "检查评测客户端" in str(excinfo.value)


def test_invalid_param_is_returned_not_retried(credentials: Credentials) -> None:
    calls = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        return httpx.Response(400, json={"code": "INVALID_PARAM", "message": "message 不能为空"})

    with make_client(handler, credentials) as client:
        result, response = client.chat("")
    assert calls["n"] == 1, "确定性 4xx 不重试"
    assert response is None
    assert result.error_code == "INVALID_PARAM"
    assert result.error_message == "message 不能为空"


def test_repo_not_found_is_not_retried(credentials: Credentials) -> None:
    calls = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        return httpx.Response(404, json={"code": "REPO_NOT_FOUND", "message": "仓库未接入或不存在"})

    with make_client(handler, credentials) as client:
        result, _ = client.chat("你好", repo_id=999999)
    assert calls["n"] == 1
    assert result.error_code == "REPO_NOT_FOUND"


def test_502_retries_up_to_limit_then_raises(credentials: Credentials) -> None:
    calls = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        return httpx.Response(502, json={"code": "LLM_UNAVAILABLE", "message": "上游不可用"})

    with make_client(handler, credentials) as client, pytest.raises(ApiCallError) as excinfo:
        client.chat("你好")
    assert calls["n"] == 3, "有限重试:max_attempts=3"
    assert excinfo.value.status == 502
    assert excinfo.value.retries == 2


def test_502_then_success_records_retry_count(credentials: Credentials) -> None:
    calls = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        if calls["n"] == 1:
            return httpx.Response(502, json={"code": "LLM_UNAVAILABLE", "message": "上游不可用"})
        return httpx.Response(200, json=chat_payload())

    with make_client(handler, credentials) as client:
        result, response = client.chat("你好")
    assert response is not None
    assert result.retry_count == 1


def test_network_error_is_retried_then_raises(credentials: Credentials) -> None:
    calls = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        raise httpx.ConnectError("boom")

    with make_client(handler, credentials) as client, pytest.raises(ApiCallError) as excinfo:
        client.chat("你好")
    assert calls["n"] == 3
    assert "ConnectError" in str(excinfo.value)


def test_429_is_retried(credentials: Credentials) -> None:
    calls = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        return httpx.Response(429, text="rate limited")

    with make_client(handler, credentials) as client, pytest.raises(ApiCallError):
        client.chat("你好")
    assert calls["n"] == 3


def test_non_json_response_degrades_readably(credentials: Credentials) -> None:
    handler = lambda request: httpx.Response(500, text="<html>gateway</html>")  # noqa: E731
    with make_client(handler, credentials) as client:
        result, response = client.chat("你好")
    assert response is None
    assert result.error_message is not None
    assert "响应非 JSON" in result.error_message


def test_register_repo_and_index_status(credentials: Credentials) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/repos":
            return httpx.Response(200, json={"id": 7, "owner": "o", "name": "n", "defaultBranch": "main"})
        return httpx.Response(
            200,
            json={"repoId": 7, "indexed": True, "fileCount": 4, "chunkCount": 63, "indexedAt": None},
        )

    with make_client(handler, credentials) as client:
        repo = client.register_repo("o/n")
        status = client.index_status(7)
    assert repo.id == 7
    assert status.indexed is True
    assert status.chunkCount == 63


def test_register_repo_error_raises_api_call_error(credentials: Credentials) -> None:
    handler = lambda request: httpx.Response(  # noqa: E731
        404, json={"code": "REPO_NOT_FOUND", "message": "查无此仓库"}
    )
    with make_client(handler, credentials) as client, pytest.raises(ApiCallError) as excinfo:
        client.register_repo("o/none")
    assert excinfo.value.code == "REPO_NOT_FOUND"


def test_custom_retry_statuses_respected(credentials: Credentials) -> None:
    calls = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        return httpx.Response(503, text="unavailable")

    retry = RetryPolicy(max_attempts=2, backoff_s=0.0, jitter_s=0.0, retry_statuses=[502])
    with make_client(handler, credentials, retry) as client:
        result, _ = client.chat("你好")
    assert calls["n"] == 1, "503 不在 retry_statuses 内则不重试"
    assert result.status == 503
