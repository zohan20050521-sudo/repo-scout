"""汇总聚合:门控位、变体隔离、成功率与分类别指标。"""

from __future__ import annotations

import pytest

from conftest import citation, make_record
from repo_scout_eval.metrics.summary import COMPOSITE_WEIGHTS, aggregate
from repo_scout_eval.models import Expectation
from repo_scout_eval.scoring import score_answer


def scored_record(**kwargs: object) -> object:
    return make_record(**kwargs)  # type: ignore[arg-type]


def test_aggregate_success_rate_excludes_priming() -> None:
    records = [
        make_record(case_id="c1", metrics={}),
        make_record(case_id="c2", http_status=502, error_code="LLM_UNAVAILABLE"),
        make_record(case_id="p1", category="pollution_pair", variant="priming", http_status=502),
    ]
    summary = aggregate(records)
    assert summary["attempted_count"] == 2
    assert summary["success_rate"] == pytest.approx(0.5)
    assert summary["error_code_counts"] == {"LLM_UNAVAILABLE": 1}


def test_polluted_variant_not_in_scored_average() -> None:
    expected = Expectation(source_paths=["docs/api.md"], answer_keywords=["x"])
    good = score_answer("含 x", [citation("docs/api.md", 0.8)], expected)
    bad = score_answer("无关", [citation("README.md", 0.8)], expected)
    records = [
        make_record(case_id="p1", category="pollution_pair", variant="fresh", metrics=good),
        make_record(case_id="p1", category="pollution_pair", variant="polluted", metrics=bad),
    ]
    summary = aggregate(records)
    assert summary["retrieval"]["citation_hit"] == pytest.approx(1.0)
    assert summary["retrieval"]["case_count"] == 1


def test_gates_limit_which_cases_enter_each_average() -> None:
    with_paths = score_answer(
        "答案", [citation("docs/api.md", 0.8)], Expectation(source_paths=["docs/api.md"])
    )
    only_keywords = score_answer("答案 x", [], Expectation(answer_keywords=["x"]))
    records = [
        make_record(case_id="c1", metrics=with_paths),
        make_record(case_id="c2", metrics=only_keywords),
    ]
    summary = aggregate(records)
    assert summary["retrieval"]["case_count"] == 1
    assert summary["answer"]["keyword_case_count"] == 1
    assert summary["answer"]["keyword_coverage_proxy"] == pytest.approx(1.0)


def test_no_evidence_section_reports_top_score_distribution() -> None:
    expected = Expectation(expect_no_citations=True, forbidden_claims=["使用 Seata"])
    hit = score_answer("仓库中未找到", [citation("README.md", 0.758)], expected)
    clean = score_answer("仓库中未找到", [], expected)
    records = [
        make_record(case_id="n1", category="no_evidence", metrics=hit),
        make_record(case_id="n2", category="no_evidence", metrics=clean),
    ]
    summary = aggregate(records)
    assert summary["no_evidence"]["false_positive_retrieval_rate"] == pytest.approx(0.5)
    assert summary["no_evidence"]["top_scores"] == [0.0, 0.758]


def test_forbidden_claim_rate_counted() -> None:
    expected = Expectation(expect_no_citations=True, forbidden_claims=["使用 Seata"])
    fabricated = score_answer("该项目使用 Seata。", [], expected)
    records = [make_record(case_id="n1", category="no_evidence", metrics=fabricated)]
    summary = aggregate(records)
    assert summary["answer"]["forbidden_claim_hit_rate"] == pytest.approx(1.0)


def test_latency_percentiles_present() -> None:
    records = [make_record(case_id=f"c{i}", latency_ms=ms) for i, ms in enumerate([100, 200, 900])]
    summary = aggregate(records)
    assert summary["latency_ms"]["p50"] == 200.0
    assert summary["latency_ms"]["max"] == 900.0


def test_composite_is_transparent_weighted_sum() -> None:
    expected = Expectation(source_paths=["docs/api.md"], answer_keywords=["x"])
    metrics = score_answer("含 x", [citation("docs/api.md", 0.8)], expected)
    summary = aggregate([make_record(case_id="c1", metrics=metrics)])
    composite = summary["composite"]
    assert composite["weights"] == COMPOSITE_WEIGHTS
    manual = sum(COMPOSITE_WEIGHTS[k] * v for k, v in composite["parts"].items())
    assert composite["value"] == pytest.approx(manual, abs=1e-4)


def test_by_category_breakdown() -> None:
    expected = Expectation(source_paths=["docs/api.md"], answer_keywords=["x"])
    metrics = score_answer("含 x", [citation("docs/api.md", 0.8)], expected)
    records = [
        make_record(case_id="c1", category="rag_fact", metrics=metrics, latency_ms=500),
        make_record(case_id="t1", category="tool_live", metrics={}, latency_ms=800),
    ]
    summary = aggregate(records)
    assert set(summary["by_category"]) == {"rag_fact", "tool_live"}
    assert summary["by_category"]["rag_fact"]["citation_hit"] == pytest.approx(1.0)
    assert summary["by_category"]["tool_live"]["p50_latency_ms"] == 800.0


def test_proxy_note_present() -> None:
    summary = aggregate([make_record(case_id="c1", metrics={})])
    assert "deterministic proxy" in str(summary["proxy_note"])


def test_empty_records_do_not_crash() -> None:
    summary = aggregate([])
    assert summary["record_count"] == 0
    assert summary["success_rate"] == 0.0
