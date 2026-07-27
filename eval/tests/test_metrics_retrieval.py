"""检索指标:citation hit / recall / precision proxy / MRR / no-evidence 误召回。"""

from __future__ import annotations

import pytest

from conftest import citation
from repo_scout_eval.metrics.retrieval import (
    citation_hit,
    citation_precision_proxy,
    expected_source_max_score,
    false_positive_retrieval,
    mrr,
    normalize_path,
    retrieval_metrics,
    source_recall,
    top_score,
)
from repo_scout_eval.models import Expectation


def test_normalize_path_handles_prefixes_and_case() -> None:
    assert normalize_path("./docs/API.md") == "docs/api.md"
    assert normalize_path("/README.md") == "readme.md"
    assert normalize_path("  docs/api.md  ") == "docs/api.md"


def test_citation_hit_and_recall_single_source() -> None:
    expected = Expectation(source_paths=["docs/api.md"])
    hits = [citation("docs/api.md", 0.8)]
    assert citation_hit(hits, expected) == 1.0
    assert source_recall(hits, expected) == 1.0
    misses = [citation("README.md", 0.8)]
    assert citation_hit(misses, expected) == 0.0
    assert source_recall(misses, expected) == 0.0


def test_source_recall_partial_multi_source() -> None:
    expected = Expectation(source_paths=["README.md", "docs/design.md"])
    hits = [citation("README.md", 0.7)]
    assert citation_hit(hits, expected) == 1.0
    assert source_recall(hits, expected) == pytest.approx(0.5)


def test_precision_proxy_uses_allowed_list_when_present() -> None:
    expected = Expectation(source_paths=["docs/api.md"], allowed_source_paths=["docs/api.md", "README.md"])
    hits = [citation("docs/api.md", 0.8), citation("README.md", 0.7), citation("docs/design.md", 0.6)]
    assert citation_precision_proxy(hits, expected) == pytest.approx(2 / 3)


def test_precision_proxy_falls_back_to_source_paths() -> None:
    expected = Expectation(source_paths=["docs/api.md"])
    hits = [citation("docs/api.md", 0.8), citation("README.md", 0.7)]
    assert citation_precision_proxy(hits, expected) == pytest.approx(0.5)


def test_precision_proxy_empty_citations_is_zero() -> None:
    assert citation_precision_proxy([], Expectation(source_paths=["a.md"])) == 0.0


def test_mrr_reflects_rank() -> None:
    expected = Expectation(source_paths=["docs/design.md"])
    ranked = [citation("README.md", 0.9), citation("docs/design.md", 0.8)]
    assert mrr(ranked, expected) == pytest.approx(0.5)
    assert mrr(list(reversed(ranked)), expected) == pytest.approx(1.0)
    assert mrr([citation("docs/api.md", 0.9)], expected) == 0.0


def test_mrr_third_position() -> None:
    expected = Expectation(source_paths=["docs/api.md"])
    hits = [citation("a.md", 0.9), citation("b.md", 0.8), citation("docs/api.md", 0.7)]
    assert mrr(hits, expected) == pytest.approx(1 / 3)


def test_top_score_and_expected_source_max_score() -> None:
    expected = Expectation(source_paths=["docs/api.md"])
    hits = [citation("README.md", 0.9), citation("docs/api.md", 0.72)]
    assert top_score(hits) == pytest.approx(0.9)
    assert expected_source_max_score(hits, expected) == pytest.approx(0.72)
    assert top_score([]) == 0.0
    assert expected_source_max_score([], expected) == 0.0


def test_no_evidence_false_positive() -> None:
    expected = Expectation(expect_no_citations=True, forbidden_claims=["x"])
    assert false_positive_retrieval([citation("README.md", 0.758)], expected) == 1.0
    assert false_positive_retrieval([], expected) == 0.0


def test_false_positive_not_counted_for_normal_case() -> None:
    expected = Expectation(source_paths=["docs/api.md"])
    assert false_positive_retrieval([citation("README.md", 0.9)], expected) == 0.0


def test_retrieval_metrics_bundle_keys_and_counts() -> None:
    expected = Expectation(source_paths=["docs/api.md"])
    hits = [citation("docs/api.md", 0.8, 0), citation("docs/api.md", 0.7, 1)]
    metrics = retrieval_metrics(hits, expected)
    assert metrics["citation_count"] == 2.0
    assert metrics["unique_source_count"] == 1.0
    assert metrics["empty_citations"] == 0.0
    assert metrics["citation_hit"] == 1.0
    assert retrieval_metrics([], expected)["empty_citations"] == 1.0


def test_metrics_without_expectation_are_zero_not_error() -> None:
    metrics = retrieval_metrics([citation("a.md", 0.5)], Expectation(answer_keywords=["x"]))
    assert metrics["citation_hit"] == 0.0
    assert metrics["source_recall"] == 0.0
    assert metrics["mrr"] == 0.0
