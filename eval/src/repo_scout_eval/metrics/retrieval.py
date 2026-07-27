"""检索侧确定性指标:纯函数,输入 citations 与标注预期,不做 I/O。

全部指标基于人工标注路径,是 deterministic proxy,不是语义真值。公式见 eval/README.md。
"""

from __future__ import annotations

from ..models import Citation, Expectation


def normalize_path(path: str) -> str:
    """路径归一:去首尾空白与前导 ./ 、/,统一小写以容忍大小写差异。"""
    cleaned = path.strip().lstrip("./").lstrip("/")
    return cleaned.lower()


def _paths(citations: list[Citation]) -> list[str]:
    return [normalize_path(c.filePath) for c in citations]


def citation_hit(citations: list[Citation], expected: Expectation) -> float:
    """至少命中一个预期 source 记 1.0;无预期路径记 0.0(该题不参与该指标)。"""
    if not expected.source_paths:
        return 0.0
    wanted = {normalize_path(p) for p in expected.source_paths}
    return 1.0 if wanted & set(_paths(citations)) else 0.0


def source_recall(citations: list[Citation], expected: Expectation) -> float:
    """命中的预期 source path 占全部预期 path 的比例。"""
    if not expected.source_paths:
        return 0.0
    wanted = {normalize_path(p) for p in expected.source_paths}
    got = set(_paths(citations))
    return len(wanted & got) / len(wanted)


def citation_precision_proxy(citations: list[Citation], expected: Expectation) -> float:
    """citations 中落在允许/预期路径内的条目占比。

    proxy 而非真值:一条未列入白名单的 citation 也可能是相关文档,只是标注未覆盖。
    """
    if not citations:
        return 0.0
    whitelist = {normalize_path(p) for p in expected.precision_whitelist()}
    if not whitelist:
        return 0.0
    hits = sum(1 for path in _paths(citations) if path in whitelist)
    return hits / len(citations)


def mrr(citations: list[Citation], expected: Expectation) -> float:
    """首个预期 source 在 citations 排序中的 reciprocal rank(1-based);未命中为 0。"""
    if not expected.source_paths:
        return 0.0
    wanted = {normalize_path(p) for p in expected.source_paths}
    for index, path in enumerate(_paths(citations), start=1):
        if path in wanted:
            return 1.0 / index
    return 0.0


def top_score(citations: list[Citation]) -> float:
    return max((c.score for c in citations), default=0.0)


def expected_source_max_score(citations: list[Citation], expected: Expectation) -> float:
    """命中预期 source 的 citation 中的最高分;未命中为 0。"""
    wanted = {normalize_path(p) for p in expected.source_paths}
    scores = [c.score for c in citations if normalize_path(c.filePath) in wanted]
    return max(scores, default=0.0)


def false_positive_retrieval(citations: list[Citation], expected: Expectation) -> float:
    """no-evidence 题仍返回 citation 记 1.0。仅对 expect_no_citations 的 case 有意义。"""
    if not expected.expect_no_citations:
        return 0.0
    return 1.0 if citations else 0.0


def retrieval_metrics(citations: list[Citation], expected: Expectation) -> dict[str, float]:
    """一次算出全部检索指标,键名与 CSV/summary 列名一致。"""
    unique_paths = {normalize_path(c.filePath) for c in citations}
    return {
        "citation_hit": citation_hit(citations, expected),
        "source_recall": source_recall(citations, expected),
        "citation_precision_proxy": citation_precision_proxy(citations, expected),
        "mrr": mrr(citations, expected),
        "top_score": top_score(citations),
        "expected_source_max_score": expected_source_max_score(citations, expected),
        "citation_count": float(len(citations)),
        "unique_source_count": float(len(unique_paths)),
        "empty_citations": 1.0 if not citations else 0.0,
        "false_positive_retrieval": false_positive_retrieval(citations, expected),
    }


def has_retrieval_expectation(expected: Expectation) -> bool:
    """该题是否参与 hit/recall/MRR 平均(只有标注了预期路径才参与)。"""
    return bool(expected.source_paths)
