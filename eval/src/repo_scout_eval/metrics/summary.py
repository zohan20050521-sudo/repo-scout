"""从逐题记录聚合汇总指标。纯函数,可脱离网络对已有 JSONL 重算。

只展示原始分项 + 一个公开公式的透明总分,不给来源不明的单一综合分。
"""

from __future__ import annotations

from ..models import CaseRecord
from .answer import mean, percentile

COMPOSITE_WEIGHTS: dict[str, float] = {
    "citation_hit": 0.3,
    "keyword_coverage": 0.4,
    "no_evidence_clean": 0.2,
    "success_rate": 0.1,
}
"""透明分项总分权重。composite = Σ(weight × 分项),分项同时全部展示。"""


def _avg_over(records: list[CaseRecord], metric: str, gate: str | None = None) -> tuple[float, int]:
    """对声明了对应预期的题求均值。gate 为门控指标名(值 >0 表示该题参与)。"""
    values = [
        r.metrics[metric]
        for r in records
        if metric in r.metrics and (gate is None or r.metrics.get(gate, 0.0) > 0.0)
    ]
    return (mean(values), len(values))


def aggregate(records: list[CaseRecord]) -> dict[str, object]:
    """汇总。polluted/priming 变体不进普通题平均(见 CaseRecord.scored)。"""
    total = len(records)
    scored = [r for r in records if r.scored]
    attempted = [r for r in records if r.variant != "priming"]
    ok = [r for r in attempted if r.ok]
    latencies = [float(r.latency_ms) for r in attempted]

    retrieval_pool = [r for r in scored if r.metrics.get("has_retrieval_expectation", 0.0) > 0.0]
    keyword_pool = [r for r in scored if r.metrics.get("has_keyword_expectation", 0.0) > 0.0]
    forbidden_pool = [r for r in scored if r.metrics.get("has_forbidden_expectation", 0.0) > 0.0]
    no_evidence_pool = [r for r in scored if r.metrics.get("expects_no_citations", 0.0) > 0.0]

    citation_hit = mean([r.metrics.get("citation_hit", 0.0) for r in retrieval_pool])
    keyword_coverage = mean([r.metrics.get("keyword_coverage", 0.0) for r in keyword_pool])
    no_evidence_fp = mean([r.metrics.get("false_positive_retrieval", 0.0) for r in no_evidence_pool])
    success_rate = len(ok) / len(attempted) if attempted else 0.0

    parts = {
        "citation_hit": citation_hit,
        "keyword_coverage": keyword_coverage,
        "no_evidence_clean": 1.0 - no_evidence_fp,
        "success_rate": success_rate,
    }
    composite = sum(COMPOSITE_WEIGHTS[name] * value for name, value in parts.items())

    return {
        "record_count": total,
        "attempted_count": len(attempted),
        "scored_count": len(scored),
        "success_count": len(ok),
        "success_rate": round(success_rate, 4),
        "error_code_counts": _error_counts(attempted),
        "retry_total": sum(r.retry_count for r in attempted),
        "latency_ms": {
            "p50": round(percentile(latencies, 0.5), 1),
            "p95": round(percentile(latencies, 0.95), 1),
            "mean": round(mean(latencies), 1),
            "max": round(max(latencies), 1) if latencies else 0.0,
        },
        "retrieval": {
            "case_count": len(retrieval_pool),
            "citation_hit": round(citation_hit, 4),
            "source_recall": round(_avg_over(retrieval_pool, "source_recall")[0], 4),
            "citation_precision_proxy": round(_avg_over(retrieval_pool, "citation_precision_proxy")[0], 4),
            "mrr": round(_avg_over(retrieval_pool, "mrr")[0], 4),
            "mean_top_score": round(_avg_over(retrieval_pool, "top_score")[0], 4),
            "mean_expected_source_max_score": round(
                _avg_over(retrieval_pool, "expected_source_max_score")[0], 4
            ),
            "mean_citation_count": round(_avg_over(scored, "citation_count")[0], 4),
            "empty_citation_rate": round(_avg_over(scored, "empty_citations")[0], 4),
        },
        "no_evidence": {
            "case_count": len(no_evidence_pool),
            "false_positive_retrieval_rate": round(no_evidence_fp, 4),
            "mean_top_score": round(_avg_over(no_evidence_pool, "top_score")[0], 4),
            "top_scores": sorted(round(r.metrics.get("top_score", 0.0), 4) for r in no_evidence_pool),
        },
        "answer": {
            "keyword_case_count": len(keyword_pool),
            "keyword_coverage_proxy": round(keyword_coverage, 4),
            "keyword_all_hit_rate": round(_avg_over(keyword_pool, "keyword_all_hit")[0], 4),
            "forbidden_case_count": len(forbidden_pool),
            "forbidden_claim_hit_rate": round(_avg_over(forbidden_pool, "forbidden_claim_hit")[0], 4),
            "empty_answer_rate": round(_avg_over(scored, "empty_answer")[0], 4),
            "short_answer_rate": round(_avg_over(scored, "short_answer")[0], 4),
            "mean_answer_chars": round(_avg_over(scored, "answer_chars")[0], 1),
        },
        "by_category": _by_category(scored, attempted),
        "composite": {
            "value": round(composite, 4),
            "weights": dict(COMPOSITE_WEIGHTS),
            "parts": {name: round(value, 4) for name, value in parts.items()},
            "note": "composite 只是上述分项的加权和,权重人为设定;判断效果请看原始分项。",
        },
        "proxy_note": (
            "keyword_coverage / citation_precision_proxy 等均为基于人工标注的 deterministic proxy,"
            "不等于事实正确率或语义相关性真值。"
        ),
    }


def _error_counts(records: list[CaseRecord]) -> dict[str, int]:
    counts: dict[str, int] = {}
    for record in records:
        if record.ok:
            continue
        key = record.error_code or (f"HTTP_{record.http_status}" if record.http_status else "NETWORK")
        counts[key] = counts.get(key, 0) + 1
    return dict(sorted(counts.items()))


def _by_category(scored: list[CaseRecord], attempted: list[CaseRecord]) -> dict[str, dict[str, object]]:
    categories = sorted({r.category for r in attempted})
    out: dict[str, dict[str, object]] = {}
    for category in categories:
        cat_attempted = [r for r in attempted if r.category == category]
        cat_scored = [r for r in scored if r.category == category]
        latencies = [float(r.latency_ms) for r in cat_attempted]
        out[category] = {
            "attempted": len(cat_attempted),
            "scored": len(cat_scored),
            "success_rate": round(
                sum(1 for r in cat_attempted if r.ok) / len(cat_attempted) if cat_attempted else 0.0, 4
            ),
            "citation_hit": round(_avg_over(cat_scored, "citation_hit", "has_retrieval_expectation")[0], 4),
            "keyword_coverage": round(
                _avg_over(cat_scored, "keyword_coverage", "has_keyword_expectation")[0], 4
            ),
            "forbidden_claim_hit": round(
                _avg_over(cat_scored, "forbidden_claim_hit", "has_forbidden_expectation")[0], 4
            ),
            "p50_latency_ms": round(percentile(latencies, 0.5), 1),
            "p95_latency_ms": round(percentile(latencies, 0.95), 1),
        }
    return out
