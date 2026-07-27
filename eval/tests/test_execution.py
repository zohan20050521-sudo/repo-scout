"""case 执行:会话隔离、多轮串行、pollution 顺序、失败记录与 session 脱敏。"""

from __future__ import annotations

from typing import Any

import httpx
import pytest

from conftest import chat_payload, make_client
from repo_scout_eval.config import Credentials
from repo_scout_eval.execution import execute_case
from repo_scout_eval.models import EvalCase, Expectation
from repo_scout_eval.scoring import session_ref

CITATION = {"filePath": "docs/api.md", "chunkIndex": 0, "excerpt": "错误码", "score": 0.81}


class Recorder:
    """记录每次 chat 请求体,便于断言 session 隔离与问题文本一致性。"""

    def __init__(self, answers: list[str] | None = None) -> None:
        self.requests: list[dict[str, Any]] = []
        self.answers = answers or []
        self._session_counter = 0

    def __call__(self, request: httpx.Request) -> httpx.Response:
        import json

        body = json.loads(request.content.decode("utf-8")) if request.content else {}
        self.requests.append(body)
        session_id = body.get("sessionId")
        if not session_id:
            self._session_counter += 1
            session_id = f"{self._session_counter:08d}-1111-1111-1111-111111111111"
        index = len(self.requests) - 1
        answer = self.answers[index] if index < len(self.answers) else "含 INVALID_PARAM 的答案"
        return httpx.Response(
            200,
            json=chat_payload(
                answer=answer, sources=["docs/api.md"], citations=[CITATION], session_id=session_id
            ),
        )


def fact_case() -> EvalCase:
    return EvalCase(
        id="fact-1",
        category="rag_fact",
        question="错误码有哪些?",
        expected=Expectation(source_paths=["docs/api.md"], answer_keywords=["INVALID_PARAM"]),
    )


def test_single_case_records_metrics_and_hashed_session(credentials: Credentials) -> None:
    recorder = Recorder()
    with make_client(recorder, credentials) as client:
        records = execute_case(fact_case(), client, 1, 1, "local", 0.0, lambda _s: None)
    assert len(records) == 1
    record = records[0]
    assert record.ok
    assert record.variant == "single"
    assert record.metrics["citation_hit"] == 1.0
    assert record.metrics["keyword_coverage"] == 1.0
    assert record.session_ref == session_ref("00000001-1111-1111-1111-111111111111")
    assert len(record.session_ref or "") == 12, "session 只存散列前缀"
    assert recorder.requests[0]["repoId"] == 1
    assert "sessionId" not in recorder.requests[0], "single case 必须新开会话"


def test_failed_case_keeps_gates_and_error_fields(credentials: Credentials) -> None:
    handler = lambda request: httpx.Response(  # noqa: E731
        400, json={"code": "INVALID_PARAM", "message": "message 不能为空"}
    )
    with make_client(handler, credentials) as client:
        records = execute_case(fact_case(), client, 1, 1, "local", 0.0, lambda _s: None)
    record = records[0]
    assert not record.ok
    assert record.error_code == "INVALID_PARAM"
    assert record.metrics["has_retrieval_expectation"] == 1.0
    assert "citation_hit" not in record.metrics


def test_conversation_reuses_session_and_binds_once(credentials: Credentials) -> None:
    case = EvalCase(
        id="conv-1",
        category="conversation",
        turns=[
            {"question": "错误响应结构?", "expected": {"answer_keywords": ["code"]}},  # type: ignore[list-item]
            {"question": "刚才那个用哪个码?", "expected": {"answer_keywords": ["INVALID_PARAM"]}},  # type: ignore[list-item]
        ],
    )
    recorder = Recorder(answers=["含 code 的答案", "是 INVALID_PARAM"])
    with make_client(recorder, credentials) as client:
        records = execute_case(case, client, 5, 1, "local", 0.0, lambda _s: None)
    assert [r.variant for r in records] == ["turn", "turn"]
    assert [r.turn_index for r in records] == [0, 1]
    assert recorder.requests[0]["repoId"] == 5
    assert "repoId" not in recorder.requests[1], "沿用绑定不再传 repoId"
    assert recorder.requests[1]["sessionId"] == "00000001-1111-1111-1111-111111111111"
    assert records[0].session_ref == records[1].session_ref


def test_conversation_stops_after_failed_turn(credentials: Credentials) -> None:
    calls = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        return httpx.Response(400, json={"code": "INVALID_PARAM", "message": "坏了"})

    case = EvalCase(
        id="conv-1",
        category="conversation",
        turns=[
            {"question": "q1", "expected": {"answer_keywords": ["a"]}},  # type: ignore[list-item]
            {"question": "q2", "expected": {"answer_keywords": ["b"]}},  # type: ignore[list-item]
        ],
    )
    with make_client(handler, credentials) as client:
        records = execute_case(case, client, 1, 1, "local", 0.0, lambda _s: None)
    assert len(records) == 1
    assert calls["n"] == 1


def pollution_case() -> EvalCase:
    return EvalCase(
        id="poll-1",
        category="pollution_pair",
        question="错误码有哪些?",
        priming_questions=["Docker 前置?", "记忆 TTL?"],
        expected=Expectation(source_paths=["docs/api.md"], answer_keywords=["INVALID_PARAM"]),
    )


def test_pollution_pair_order_sessions_and_identical_b(credentials: Credentials) -> None:
    recorder = Recorder()
    with make_client(recorder, credentials) as client:
        records = execute_case(pollution_case(), client, 3, 1, "local", 0.0, lambda _s: None)
    assert [r.variant for r in records] == ["fresh", "priming", "priming", "polluted"]

    fresh, polluted = records[0], records[-1]
    assert fresh.question == polluted.question == "错误码有哪些?", "B 必须逐字一致"
    assert fresh.session_ref != polluted.session_ref, "两条路径必须独立 session"

    # fresh 新开会话;priming 首轮新开并绑定;polluted 复用 priming 的 session
    assert "sessionId" not in recorder.requests[0]
    assert "sessionId" not in recorder.requests[1]
    assert recorder.requests[1]["repoId"] == 3
    assert recorder.requests[2]["sessionId"] == recorder.requests[3]["sessionId"]
    assert recorder.requests[3]["sessionId"] != "00000001-1111-1111-1111-111111111111"


def test_pollution_priming_variant_excluded_from_scoring(credentials: Credentials) -> None:
    recorder = Recorder()
    with make_client(recorder, credentials) as client:
        records = execute_case(pollution_case(), client, 3, 1, "local", 0.0, lambda _s: None)
    priming = [r for r in records if r.variant == "priming"]
    assert all(not r.scored for r in priming)
    assert all(r.metrics.get("has_retrieval_expectation", 0.0) == 0.0 for r in priming)


def test_pollution_aborts_when_priming_fails(credentials: Credentials) -> None:
    calls = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        if calls["n"] == 1:
            return httpx.Response(200, json=chat_payload(answer="ok", citations=[CITATION]))
        return httpx.Response(502, json={"code": "LLM_UNAVAILABLE", "message": "上游不可用"})

    with make_client(handler, credentials) as client:
        records = execute_case(pollution_case(), client, 3, 1, "local", 0.0, lambda _s: None)
    assert [r.variant for r in records] == ["fresh", "priming"]
    assert not any(r.variant == "polluted" for r in records)


def test_pause_between_pollution_steps_is_applied(credentials: Credentials) -> None:
    slept: list[float] = []
    with make_client(Recorder(), credentials) as client:
        execute_case(pollution_case(), client, 3, 1, "local", 1.5, slept.append)
    assert slept == [1.5, 1.5, 1.5], "fresh 后与每个 priming 后各暂停一次"


def test_report_case_scores_sections(credentials: Credentials) -> None:
    report = (
        "## 项目定位\n定位\n## 技术栈\nSpring\n## 目录结构解读\nsrc\n## 上手指引\nrun\n## 近期动向\ncommits"
    )
    handler = lambda request: httpx.Response(  # noqa: E731
        200, json={"repoId": 1, "generatedAt": "2026-07-27T00:00:00", "costMs": 12345, "report": report}
    )
    case = EvalCase(
        id="report-1",
        category="report_structure",
        question="(report)",
        expected=Expectation(
            require_markdown_sections=[
                "## 项目定位",
                "## 技术栈",
                "## 目录结构解读",
                "## 上手指引",
                "## 近期动向",
            ]
        ),
    )
    with make_client(handler, credentials) as client:
        records = execute_case(case, client, 1, 1, "local", 0.0, lambda _s: None)
    assert records[0].metrics["section_coverage"] == pytest.approx(1.0)
    assert records[0].metrics["report_cost_ms"] == 12345.0


def test_report_failure_recorded(credentials: Credentials) -> None:
    handler = lambda request: httpx.Response(  # noqa: E731
        404, json={"code": "REPO_NOT_FOUND", "message": "仓库未接入或不存在"}
    )
    case = EvalCase(
        id="report-1",
        category="report_structure",
        question="(report)",
        expected=Expectation(require_markdown_sections=["## 项目定位"]),
    )
    with make_client(handler, credentials) as client:
        records = execute_case(case, client, 1, 1, "local", 0.0, lambda _s: None)
    assert records[0].error_code == "REPO_NOT_FOUND"
