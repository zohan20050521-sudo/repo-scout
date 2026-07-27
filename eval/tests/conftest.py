"""测试共用夹具。全程 mock,不访问网络、不需要 MySQL/Redis/真实 key。"""

from __future__ import annotations

from pathlib import Path
from typing import Any

import httpx
import pytest

from repo_scout_eval.client import RepoScoutClient
from repo_scout_eval.config import Credentials, RetryPolicy
from repo_scout_eval.models import CaseRecord, Citation, Dataset, EvalCase, Expectation

REPO_ROOT = Path(__file__).resolve().parents[1]


@pytest.fixture
def credentials() -> Credentials:
    return Credentials(internal_key=None, timeout_s=5.0)


@pytest.fixture
def fast_retry() -> RetryPolicy:
    return RetryPolicy(max_attempts=3, backoff_s=0.0, jitter_s=0.0)


def make_client(handler: Any, credentials: Credentials, retry: RetryPolicy | None = None) -> RepoScoutClient:
    """用 httpx MockTransport 构造客户端;sleep 替换为无操作,测试不真的等待。"""
    return RepoScoutClient(
        "http://test.local",
        credentials,
        retry or RetryPolicy(max_attempts=3, backoff_s=0.0, jitter_s=0.0),
        transport=httpx.MockTransport(handler),
        sleep=lambda _seconds: None,
    )


def chat_payload(
    answer: str = "答案",
    sources: list[str] | None = None,
    citations: list[dict[str, Any]] | None = None,
    session_id: str = "11111111-1111-1111-1111-111111111111",
) -> dict[str, Any]:
    return {
        "sessionId": session_id,
        "answer": answer,
        "sources": sources if sources is not None else [],
        "citations": citations if citations is not None else [],
    }


def citation(path: str, score: float, chunk_index: int = 0, excerpt: str = "摘录") -> Citation:
    return Citation(filePath=path, chunkIndex=chunk_index, excerpt=excerpt, score=score)


def make_record(
    case_id: str = "c1",
    category: str = "rag_fact",
    variant: str = "single",
    citations: list[Citation] | None = None,
    metrics: dict[str, float] | None = None,
    http_status: int | None = 200,
    **kwargs: Any,
) -> CaseRecord:
    return CaseRecord(
        case_id=case_id,
        category=category,  # type: ignore[arg-type]
        variant=variant,  # type: ignore[arg-type]
        http_status=http_status,
        citations=citations or [],
        metrics=metrics or {},
        **kwargs,
    )


@pytest.fixture
def simple_dataset() -> Dataset:
    return Dataset(
        version="test",
        repo="owner/repo",
        cases=[
            EvalCase(
                id="fact-1",
                category="rag_fact",
                question="错误码有哪些?",
                expected=Expectation(source_paths=["docs/api.md"], answer_keywords=["INVALID_PARAM"]),
            ),
            EvalCase(
                id="none-1",
                category="no_evidence",
                question="Kafka 配置在哪?",
                expected=Expectation(expect_no_citations=True, forbidden_claims=["topic 名为"]),
            ),
        ],
    )


@pytest.fixture
def repo_dataset_path() -> Path:
    return REPO_ROOT / "datasets" / "v1.yaml"
