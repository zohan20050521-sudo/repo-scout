"""Issue #3:fresh vs polluted 配对差值计算。

测的是**输出行为影响**:两条路径用独立 session、逐字相同的目标问题 B。citations 只反映
B 本轮检索,无法显示 Redis 中的旧摘录,因此不能声称直接观测到 prompt token 或 memory 内容。
差值无显著性检验;样本少时只能作为趋势提示。
"""

from __future__ import annotations

from dataclasses import dataclass

from ..models import CaseRecord

COMPARED_METRICS: tuple[str, ...] = (
    "keyword_coverage",
    "forbidden_claim_hit",
    "citation_hit",
    "source_recall",
    "citation_precision_proxy",
    "mrr",
    "top_score",
    "citation_count",
    "answer_chars",
)


@dataclass(frozen=True)
class PollutionPair:
    """一组 (case_id, repetition) 的配对结果。delta = polluted - fresh。"""

    case_id: str
    repetition: int
    target_label: str
    fresh_ok: bool
    polluted_ok: bool
    priming_count: int
    fresh_latency_ms: int
    polluted_latency_ms: int
    deltas: dict[str, float]
    fresh_metrics: dict[str, float]
    polluted_metrics: dict[str, float]
    same_question: bool
    distinct_sessions: bool

    def as_row(self) -> dict[str, object]:
        row: dict[str, object] = {
            "case_id": self.case_id,
            "repetition": self.repetition,
            "target_label": self.target_label,
            "fresh_ok": self.fresh_ok,
            "polluted_ok": self.polluted_ok,
            "priming_count": self.priming_count,
            "fresh_latency_ms": self.fresh_latency_ms,
            "polluted_latency_ms": self.polluted_latency_ms,
            "latency_delta_ms": self.polluted_latency_ms - self.fresh_latency_ms,
            "same_question": self.same_question,
            "distinct_sessions": self.distinct_sessions,
        }
        for name in COMPARED_METRICS:
            row[f"fresh_{name}"] = round(self.fresh_metrics.get(name, 0.0), 4)
            row[f"polluted_{name}"] = round(self.polluted_metrics.get(name, 0.0), 4)
            row[f"delta_{name}"] = round(self.deltas.get(name, 0.0), 4)
        return row


def build_pairs(records: list[CaseRecord]) -> list[PollutionPair]:
    """从逐题记录里按 (case_id, repetition, target) 配对 fresh / polluted。"""
    fresh_map: dict[tuple[str, int, str], CaseRecord] = {}
    polluted_map: dict[tuple[str, int, str], CaseRecord] = {}
    priming_counts: dict[tuple[str, int, str], int] = {}
    for record in records:
        if record.category != "pollution_pair":
            continue
        key = (record.case_id, record.repetition, record.target_label)
        if record.variant == "fresh":
            fresh_map[key] = record
        elif record.variant == "polluted":
            polluted_map[key] = record
        elif record.variant == "priming":
            priming_counts[key] = priming_counts.get(key, 0) + 1

    pairs: list[PollutionPair] = []
    for key in sorted(fresh_map.keys() & polluted_map.keys()):
        fresh, polluted = fresh_map[key], polluted_map[key]
        deltas = {
            name: polluted.metrics.get(name, 0.0) - fresh.metrics.get(name, 0.0) for name in COMPARED_METRICS
        }
        pairs.append(
            PollutionPair(
                case_id=key[0],
                repetition=key[1],
                target_label=key[2],
                fresh_ok=fresh.ok,
                polluted_ok=polluted.ok,
                priming_count=priming_counts.get(key, 0),
                fresh_latency_ms=fresh.latency_ms,
                polluted_latency_ms=polluted.latency_ms,
                deltas=deltas,
                fresh_metrics=dict(fresh.metrics),
                polluted_metrics=dict(polluted.metrics),
                same_question=fresh.question == polluted.question,
                distinct_sessions=(
                    fresh.session_ref is not None
                    and polluted.session_ref is not None
                    and fresh.session_ref != polluted.session_ref
                ),
            )
        )
    return pairs


def summarize_pairs(pairs: list[PollutionPair]) -> dict[str, object]:
    """汇总配对差值。usable 仅统计两条路径都成功、且 B 文本一致、session 独立的配对。"""
    usable = [p for p in pairs if p.fresh_ok and p.polluted_ok and p.same_question]
    summary: dict[str, object] = {
        "pair_count": len(pairs),
        "usable_pair_count": len(usable),
        "integrity_violations": [
            {
                "case_id": p.case_id,
                "repetition": p.repetition,
                "same_question": p.same_question,
                "distinct_sessions": p.distinct_sessions,
            }
            for p in pairs
            if not p.same_question or not p.distinct_sessions
        ],
        "note": (
            "delta = polluted - fresh。仅衡量输出行为差异,不代表直接观测到 Redis 记忆或 prompt token;"
            "样本量小时不足以判定显著性。"
        ),
    }
    for name in COMPARED_METRICS:
        values = [p.deltas[name] for p in usable]
        summary[f"mean_delta_{name}"] = round(sum(values) / len(values), 4) if values else 0.0
    latencies = [p.polluted_latency_ms - p.fresh_latency_ms for p in usable]
    summary["mean_latency_delta_ms"] = round(sum(latencies) / len(latencies), 1) if latencies else 0.0
    regressed = [
        p.case_id for p in usable if p.deltas["keyword_coverage"] < 0 or p.deltas["forbidden_claim_hit"] > 0
    ]
    summary["regressed_case_ids"] = sorted(set(regressed))
    return summary
