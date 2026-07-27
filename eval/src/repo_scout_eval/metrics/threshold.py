"""Issue #4:minScore 离线阈值重放(左截断曲线)。

观测边界:后端只返回**已通过运行时 RAG_MIN_SCORE 过滤**的 citations,低于该阈值的候选
对评测不可见。因此本模块只能回答「在已返回 citations 上继续提高阈值会怎样」,
不能推断低于服务端当前阈值的完整曲线。要拿更完整曲线,需操作者另起一个把
RAG_MIN_SCORE 调低的评测专用后端实例(工具不自行改后端)。
"""

from __future__ import annotations

from dataclasses import dataclass, field

from ..models import CaseRecord, Citation
from .retrieval import normalize_path

TRUNCATION_WARNING = (
    "阈值曲线为左截断:后端已按运行时 RAG_MIN_SCORE 过滤候选,低于该阈值的候选不可见,"
    "本曲线只反映在已返回 citations 上继续提高阈值的影响。"
)


@dataclass(frozen=True)
class ThresholdPoint:
    """单个阈值下的重放结果。retained 指 score >= threshold 的 citations。"""

    threshold: float
    evidence_cases: int
    evidence_retained_hit: float
    expected_source_recall_proxy: float
    no_evidence_cases: int
    no_evidence_false_positive_rate: float
    avg_retained_citations: float
    empty_retrieval_rate: float
    balanced_f1: float
    observed_min_score: float | None = None
    warnings: list[str] = field(default_factory=lambda: [TRUNCATION_WARNING])

    def as_row(self) -> dict[str, object]:
        return {
            "threshold": round(self.threshold, 4),
            "evidence_cases": self.evidence_cases,
            "evidence_retained_hit": round(self.evidence_retained_hit, 4),
            "expected_source_recall_proxy": round(self.expected_source_recall_proxy, 4),
            "no_evidence_cases": self.no_evidence_cases,
            "no_evidence_false_positive_rate": round(self.no_evidence_false_positive_rate, 4),
            "avg_retained_citations": round(self.avg_retained_citations, 4),
            "empty_retrieval_rate": round(self.empty_retrieval_rate, 4),
            "balanced_f1": round(self.balanced_f1, 4),
            "left_truncated": True,
        }


def retained(citations: list[Citation], threshold: float) -> list[Citation]:
    """保留 score >= threshold 的 citations(等于阈值视为保留)。"""
    return [c for c in citations if c.score >= threshold]


def _expected_paths(record: CaseRecord, expected_paths: dict[str, list[str]]) -> set[str]:
    return {normalize_path(p) for p in expected_paths.get(record.case_id, [])}


def replay(
    records: list[CaseRecord],
    thresholds: list[float],
    expected_paths: dict[str, list[str]],
    no_evidence_case_ids: set[str],
) -> list[ThresholdPoint]:
    """对一次 run 的原始 citations 重放候选阈值。

    - evidence case:标注了 expected source path 且成功的普通题;
    - no_evidence case:标注 expect_no_citations 的题;
    - balanced_f1:2PR/(P+R),P = 1 - no_evidence_false_positive_rate,R = evidence_retained_hit。
    """
    usable = [r for r in records if r.scored]
    evidence = [r for r in usable if expected_paths.get(r.case_id)]
    no_evidence = [r for r in usable if r.case_id in no_evidence_case_ids]
    all_scores = [c.score for r in usable for c in r.citations]
    observed_min = min(all_scores) if all_scores else None

    points: list[ThresholdPoint] = []
    for threshold in thresholds:
        hits = 0
        recalls: list[float] = []
        for record in evidence:
            wanted = _expected_paths(record, expected_paths)
            kept = {normalize_path(c.filePath) for c in retained(record.citations, threshold)}
            matched = wanted & kept
            hits += 1 if matched else 0
            recalls.append(len(matched) / len(wanted) if wanted else 0.0)
        fp = sum(1 for r in no_evidence if retained(r.citations, threshold))
        kept_counts = [float(len(retained(r.citations, threshold))) for r in usable]
        recall = hits / len(evidence) if evidence else 0.0
        fp_rate = fp / len(no_evidence) if no_evidence else 0.0
        precision = 1.0 - fp_rate
        f1 = (2 * precision * recall / (precision + recall)) if (precision + recall) > 0 else 0.0
        warnings = [TRUNCATION_WARNING]
        if observed_min is not None and threshold <= observed_min:
            warnings.append(
                f"threshold={threshold:.2f} 不高于本次 run 观测到的最低 citation score "
                f"{observed_min:.4f},该点等价于「不额外过滤」,不代表服务端在该阈值下的真实行为。"
            )
        points.append(
            ThresholdPoint(
                threshold=threshold,
                evidence_cases=len(evidence),
                evidence_retained_hit=recall,
                expected_source_recall_proxy=(sum(recalls) / len(recalls) if recalls else 0.0),
                no_evidence_cases=len(no_evidence),
                no_evidence_false_positive_rate=fp_rate,
                avg_retained_citations=(sum(kept_counts) / len(kept_counts) if kept_counts else 0.0),
                empty_retrieval_rate=(
                    sum(1 for c in kept_counts if c == 0) / len(kept_counts) if kept_counts else 0.0
                ),
                balanced_f1=f1,
                observed_min_score=observed_min,
                warnings=warnings,
            )
        )
    return points


def suggest_range(points: list[ThresholdPoint]) -> str:
    """给出建议区间的可读描述。仅为数据观察,不修改后端默认值。"""
    if not points:
        return "无可用数据"
    best = max(points, key=lambda p: (p.balanced_f1, -p.threshold))
    return (
        f"本次数据下 balanced_f1 最高点为 threshold={best.threshold:.2f}"
        f"(evidence_retained_hit={best.evidence_retained_hit:.2f},"
        f"no_evidence_false_positive_rate={best.no_evidence_false_positive_rate:.2f});"
        "该结论仅限本数据集与本次 run,且受左截断限制,不作为通用最优阈值。"
    )
