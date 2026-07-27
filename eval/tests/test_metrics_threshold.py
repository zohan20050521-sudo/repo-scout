"""阈值曲线边界:空 citations、阈值等于 score、左截断警告。"""

from __future__ import annotations

import pytest

from conftest import citation, make_record
from repo_scout_eval.metrics.threshold import (
    TRUNCATION_WARNING,
    replay,
    retained,
    suggest_range,
)


def test_retained_includes_score_equal_to_threshold() -> None:
    hits = [citation("a.md", 0.70), citation("b.md", 0.6999)]
    kept = retained(hits, 0.70)
    assert [c.filePath for c in kept] == ["a.md"], "等于阈值视为保留,低于则剔除"


def test_replay_empty_citations_yields_zero_curve() -> None:
    records = [make_record(case_id="c1", citations=[])]
    points = replay(records, [0.5, 0.8], {"c1": ["docs/api.md"]}, set())
    assert [p.evidence_retained_hit for p in points] == [0.0, 0.0]
    assert all(p.empty_retrieval_rate == 1.0 for p in points)
    assert all(p.observed_min_score is None for p in points)


def test_replay_no_usable_records_returns_zero_points() -> None:
    records = [make_record(case_id="c1", http_status=502)]
    points = replay(records, [0.5], {"c1": ["docs/api.md"]}, set())
    assert points[0].evidence_cases == 0
    assert points[0].evidence_retained_hit == 0.0


def test_replay_hit_drops_as_threshold_rises() -> None:
    records = [
        make_record(case_id="c1", citations=[citation("docs/api.md", 0.72)]),
        make_record(case_id="c2", citations=[citation("README.md", 0.81)]),
    ]
    expected = {"c1": ["docs/api.md"], "c2": ["README.md"]}
    points = replay(records, [0.70, 0.75, 0.85], expected, set())
    assert points[0].evidence_retained_hit == pytest.approx(1.0)
    assert points[1].evidence_retained_hit == pytest.approx(0.5)
    assert points[2].evidence_retained_hit == pytest.approx(0.0)


def test_replay_no_evidence_false_positive_rate() -> None:
    records = [
        make_record(case_id="n1", category="no_evidence", citations=[citation("README.md", 0.758)]),
        make_record(case_id="n2", category="no_evidence", citations=[citation("README.md", 0.60)]),
    ]
    points = replay(records, [0.55, 0.70, 0.80], {}, {"n1", "n2"})
    assert points[0].no_evidence_false_positive_rate == pytest.approx(1.0)
    assert points[1].no_evidence_false_positive_rate == pytest.approx(0.5)
    assert points[2].no_evidence_false_positive_rate == pytest.approx(0.0)


def test_replay_avg_retained_and_recall_proxy() -> None:
    records = [
        make_record(
            case_id="c1",
            citations=[
                citation("docs/api.md", 0.80),
                citation("README.md", 0.62),
                citation("docs/design.md", 0.55),
            ],
        )
    ]
    expected = {"c1": ["docs/api.md", "README.md"]}
    points = replay(records, [0.50, 0.70], expected, set())
    assert points[0].avg_retained_citations == pytest.approx(3.0)
    assert points[0].expected_source_recall_proxy == pytest.approx(1.0)
    assert points[1].avg_retained_citations == pytest.approx(1.0)
    assert points[1].expected_source_recall_proxy == pytest.approx(0.5)


def test_truncation_warning_always_present() -> None:
    records = [make_record(case_id="c1", citations=[citation("docs/api.md", 0.8)])]
    points = replay(records, [0.5], {"c1": ["docs/api.md"]}, set())
    assert TRUNCATION_WARNING in points[0].warnings
    assert points[0].as_row()["left_truncated"] is True


def test_threshold_below_observed_min_gets_extra_warning() -> None:
    records = [make_record(case_id="c1", citations=[citation("docs/api.md", 0.68)])]
    points = replay(records, [0.50, 0.85], {"c1": ["docs/api.md"]}, set())
    assert len(points[0].warnings) == 2
    assert "等价于「不额外过滤」" in points[0].warnings[1]
    assert len(points[1].warnings) == 1


def test_balanced_f1_formula_is_reproducible() -> None:
    records = [
        make_record(case_id="c1", citations=[citation("docs/api.md", 0.80)]),
        make_record(case_id="n1", category="no_evidence", citations=[citation("README.md", 0.60)]),
    ]
    points = replay(records, [0.70], {"c1": ["docs/api.md"]}, {"n1"})
    point = points[0]
    precision = 1.0 - point.no_evidence_false_positive_rate
    recall = point.evidence_retained_hit
    assert point.balanced_f1 == pytest.approx(2 * precision * recall / (precision + recall))


def test_polluted_variant_excluded_from_replay() -> None:
    records = [
        make_record(case_id="c1", variant="fresh", citations=[citation("docs/api.md", 0.80)]),
        make_record(case_id="c1", variant="polluted", citations=[citation("docs/api.md", 0.80)]),
    ]
    points = replay(records, [0.50], {"c1": ["docs/api.md"]}, set())
    assert points[0].evidence_cases == 1


def test_suggest_range_reports_best_point_with_caveat() -> None:
    records = [
        make_record(case_id="c1", citations=[citation("docs/api.md", 0.80)]),
        make_record(case_id="n1", category="no_evidence", citations=[citation("README.md", 0.60)]),
    ]
    points = replay(records, [0.50, 0.70], {"c1": ["docs/api.md"]}, {"n1"})
    text = suggest_range(points)
    assert "balanced_f1 最高点" in text
    assert "不作为通用最优阈值" in text
    assert suggest_range([]) == "无可用数据"
