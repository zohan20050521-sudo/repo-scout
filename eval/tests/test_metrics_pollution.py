"""pollution 配对:独立 session、相同 B、顺序与差值。"""

from __future__ import annotations

import pytest

from conftest import citation, make_record
from repo_scout_eval.metrics.pollution import build_pairs, summarize_pairs


def pair_records(
    fresh_metrics: dict[str, float],
    polluted_metrics: dict[str, float],
    question: str = "B 问题",
    polluted_question: str | None = None,
    fresh_session: str = "aaa",
    polluted_session: str = "bbb",
    priming: int = 2,
) -> list:
    records = [
        make_record(
            case_id="p1",
            category="pollution_pair",
            variant="fresh",
            question=question,
            session_ref=fresh_session,
            metrics=fresh_metrics,
            latency_ms=1000,
        )
    ]
    for index in range(priming):
        records.append(
            make_record(
                case_id="p1",
                category="pollution_pair",
                variant="priming",
                question=f"A{index}",
                session_ref=polluted_session,
                turn_index=index,
            )
        )
    records.append(
        make_record(
            case_id="p1",
            category="pollution_pair",
            variant="polluted",
            question=polluted_question or question,
            session_ref=polluted_session,
            metrics=polluted_metrics,
            latency_ms=1500,
        )
    )
    return records


def test_pair_built_with_distinct_sessions_and_same_question() -> None:
    records = pair_records({"keyword_coverage": 1.0}, {"keyword_coverage": 0.5})
    pairs = build_pairs(records)
    assert len(pairs) == 1
    pair = pairs[0]
    assert pair.same_question is True
    assert pair.distinct_sessions is True
    assert pair.priming_count == 2
    assert pair.deltas["keyword_coverage"] == pytest.approx(-0.5)
    assert pair.polluted_latency_ms - pair.fresh_latency_ms == 500


def test_same_session_flagged_as_integrity_violation() -> None:
    records = pair_records(
        {"keyword_coverage": 1.0}, {"keyword_coverage": 1.0}, fresh_session="same", polluted_session="same"
    )
    pairs = build_pairs(records)
    assert pairs[0].distinct_sessions is False
    summary = summarize_pairs(pairs)
    assert summary["integrity_violations"]


def test_differing_b_question_excluded_from_usable() -> None:
    records = pair_records(
        {"keyword_coverage": 1.0}, {"keyword_coverage": 0.0}, polluted_question="不一样的 B"
    )
    pairs = build_pairs(records)
    assert pairs[0].same_question is False
    summary = summarize_pairs(pairs)
    assert summary["usable_pair_count"] == 0
    assert summary["mean_delta_keyword_coverage"] == 0.0


def test_unpaired_records_are_ignored() -> None:
    records = [
        make_record(case_id="p1", category="pollution_pair", variant="fresh", metrics={}),
    ]
    assert build_pairs(records) == []


def test_pairs_keyed_by_repetition() -> None:
    records: list = []
    for repetition in (1, 2):
        for variant in ("fresh", "polluted"):
            records.append(
                make_record(
                    case_id="p1",
                    category="pollution_pair",
                    variant=variant,
                    repetition=repetition,
                    question="B",
                    session_ref=f"{variant}{repetition}",
                    metrics={"keyword_coverage": 1.0 if variant == "fresh" else 0.5},
                )
            )
    pairs = build_pairs(records)
    assert {p.repetition for p in pairs} == {1, 2}
    summary = summarize_pairs(pairs)
    assert summary["usable_pair_count"] == 2
    assert summary["mean_delta_keyword_coverage"] == pytest.approx(-0.5)


def test_failed_side_excluded_from_usable() -> None:
    records = pair_records({"keyword_coverage": 1.0}, {"keyword_coverage": 0.0})
    records[-1].http_status = 502
    records[-1].error_code = "LLM_UNAVAILABLE"
    pairs = build_pairs(records)
    summary = summarize_pairs(pairs)
    assert pairs[0].polluted_ok is False
    assert summary["pair_count"] == 1
    assert summary["usable_pair_count"] == 0


def test_regression_detected_via_keyword_or_forbidden() -> None:
    records = pair_records(
        {"keyword_coverage": 1.0, "forbidden_claim_hit": 0.0},
        {"keyword_coverage": 1.0, "forbidden_claim_hit": 1.0},
    )
    summary = summarize_pairs(build_pairs(records))
    assert summary["regressed_case_ids"] == ["p1"]


def test_no_difference_reported_honestly() -> None:
    records = pair_records({"keyword_coverage": 1.0}, {"keyword_coverage": 1.0})
    summary = summarize_pairs(build_pairs(records))
    assert summary["mean_delta_keyword_coverage"] == 0.0
    assert summary["regressed_case_ids"] == []
    assert "不代表直接观测到 Redis 记忆" in str(summary["note"])


def test_citation_deltas_computed() -> None:
    records = pair_records(
        {"citation_hit": 1.0, "citation_count": 4.0, "top_score": 0.81},
        {"citation_hit": 0.0, "citation_count": 2.0, "top_score": 0.70},
    )
    pair = build_pairs(records)[0]
    assert pair.deltas["citation_hit"] == pytest.approx(-1.0)
    assert pair.deltas["citation_count"] == pytest.approx(-2.0)
    assert pair.deltas["top_score"] == pytest.approx(-0.11)


def test_pair_row_contains_fresh_polluted_and_delta_columns() -> None:
    records = pair_records({"keyword_coverage": 1.0}, {"keyword_coverage": 0.5})
    row = build_pairs(records)[0].as_row()
    for prefix in ("fresh_", "polluted_", "delta_"):
        assert f"{prefix}keyword_coverage" in row
    assert row["latency_delta_ms"] == 500


def test_non_pollution_records_ignored() -> None:
    records = [make_record(case_id="c1", category="rag_fact", citations=[citation("a.md", 0.8)])]
    assert build_pairs(records) == []
