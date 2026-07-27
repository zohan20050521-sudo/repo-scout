"""打分组合层:门控位、session 散列、失败记录字段。"""

from __future__ import annotations

from conftest import citation
from repo_scout_eval.models import ChatResponse, Expectation
from repo_scout_eval.scoring import SESSION_REF_LEN, empty_metrics, gates, score_chat, session_ref


def test_session_ref_is_hashed_and_truncated() -> None:
    session_id = "0f14d0ab-9605-4a62-a9e4-5ed26688389b"
    ref = session_ref(session_id)
    assert ref is not None
    assert len(ref) == SESSION_REF_LEN
    assert session_id not in ref
    assert session_ref(session_id) == ref, "同一 session 稳定散列"
    assert session_ref(None) is None
    assert session_ref("") is None


def test_gates_reflect_declared_expectations() -> None:
    expected = Expectation(source_paths=["a.md"], answer_keywords=["k"], forbidden_claims=["f"])
    result = gates(expected)
    assert result["has_retrieval_expectation"] == 1.0
    assert result["has_keyword_expectation"] == 1.0
    assert result["has_forbidden_expectation"] == 1.0
    assert result["expects_no_citations"] == 0.0


def test_gates_for_no_evidence_case() -> None:
    expected = Expectation(expect_no_citations=True, forbidden_claims=["f"])
    result = gates(expected)
    assert result["expects_no_citations"] == 1.0
    assert result["has_retrieval_expectation"] == 0.0


def test_score_chat_combines_retrieval_and_answer_metrics() -> None:
    response = ChatResponse(
        sessionId="s",
        answer="含 INVALID_PARAM",
        sources=["docs/api.md"],
        citations=[citation("docs/api.md", 0.8)],
    )
    expected = Expectation(source_paths=["docs/api.md"], answer_keywords=["INVALID_PARAM"])
    metrics = score_chat(response, expected)
    assert metrics["citation_hit"] == 1.0
    assert metrics["keyword_coverage"] == 1.0
    assert metrics["has_retrieval_expectation"] == 1.0


def test_empty_metrics_only_keeps_gates() -> None:
    metrics = empty_metrics(Expectation(source_paths=["a.md"]))
    assert set(metrics) == {
        "has_retrieval_expectation",
        "has_keyword_expectation",
        "has_forbidden_expectation",
        "expects_no_citations",
    }
