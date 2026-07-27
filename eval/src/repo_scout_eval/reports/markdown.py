"""Markdown 摘要渲染:纯字符串拼装,长文本截断并以 case id 索引 JSONL 原文。"""

from __future__ import annotations

from typing import Any

from ..metrics.threshold import TRUNCATION_WARNING, ThresholdPoint, suggest_range
from ..models import CaseRecord, RunManifest

EXCERPT_LIMIT = 160


def _table(headers: list[str], rows: list[list[str]]) -> str:
    lines = ["| " + " | ".join(headers) + " |", "| " + " | ".join("---" for _ in headers) + " |"]
    lines += ["| " + " | ".join(row) + " |" for row in rows]
    return "\n".join(lines)


def _truncate(text: str, limit: int = EXCERPT_LIMIT) -> str:
    flat = " ".join((text or "").split())
    return flat if len(flat) <= limit else flat[:limit] + f"…(共 {len(flat)} 字符,原文见 cases.jsonl)"


def render_summary(
    manifest: RunManifest,
    summary: dict[str, Any],
    records: list[CaseRecord],
    threshold_points: list[ThresholdPoint],
    pollution_summary: dict[str, Any] | None,
) -> str:
    parts = [
        f"# repo-scout 评测汇总 `{manifest.run_id}`",
        "",
        _render_manifest(manifest),
        "",
        _render_overview(summary),
        "",
        _render_categories(summary),
        "",
        _render_threshold(threshold_points),
    ]
    if pollution_summary:
        parts += ["", _render_pollution(pollution_summary)]
    parts += ["", _render_failures(records), "", _render_cases(records)]
    return "\n".join(parts) + "\n"


def _render_manifest(manifest: RunManifest) -> str:
    label_note = "(未由服务端证明,仅操作者声明)" if manifest.min_score_label is not None else ""
    rows = [
        ["tool_version", manifest.tool_version],
        ["schema_version", manifest.schema_version],
        ["target", f"`{manifest.target_label}` @ `{manifest.target_host}`"],
        ["min_score_label", f"{manifest.min_score_label}{label_note}"],
        ["repo", f"`{manifest.repo}` (repoId={manifest.repo_id}, head={manifest.repo_head_commit})"],
        [
            "dataset",
            f"`{manifest.dataset_path}` v{manifest.dataset_version} sha256={manifest.dataset_sha256[:12]}…",
        ],
        [
            "index",
            f"indexed={manifest.indexed} files={manifest.index_file_count} "
            f"chunks={manifest.index_chunk_count}",
        ],
        ["window", f"{manifest.started_at} → {manifest.finished_at}"],
        ["python", manifest.python_version.split()[0]],
        ["judge", "enabled" if manifest.judge_enabled else "disabled"],
    ]
    return "## 运行清单\n\n" + _table(["项", "值"], [[k, str(v)] for k, v in rows])


def _render_overview(summary: dict[str, Any]) -> str:
    latency = summary.get("latency_ms", {})
    retrieval = summary.get("retrieval", {})
    answer = summary.get("answer", {})
    no_evidence = summary.get("no_evidence", {})
    composite = summary.get("composite", {})
    rows = [
        [
            "成功率",
            f"{summary.get('success_rate')}",
            f"{summary.get('success_count')}/{summary.get('attempted_count')}",
        ],
        [
            "latency P50 / P95 (ms)",
            f"{latency.get('p50')} / {latency.get('p95')}",
            f"mean={latency.get('mean')}",
        ],
        ["citation_hit", f"{retrieval.get('citation_hit')}", f"n={retrieval.get('case_count')}"],
        ["source_recall", f"{retrieval.get('source_recall')}", "命中预期路径占比均值"],
        [
            "citation_precision_proxy",
            f"{retrieval.get('citation_precision_proxy')}",
            "基于标注路径的 proxy",
        ],
        ["mrr", f"{retrieval.get('mrr')}", "首个预期来源 reciprocal rank"],
        [
            "mean_top_score",
            f"{retrieval.get('mean_top_score')}",
            f"empty_citation_rate={retrieval.get('empty_citation_rate')}",
        ],
        [
            "keyword_coverage_proxy",
            f"{answer.get('keyword_coverage_proxy')}",
            f"n={answer.get('keyword_case_count')}",
        ],
        [
            "forbidden_claim_hit_rate",
            f"{answer.get('forbidden_claim_hit_rate')}",
            f"n={answer.get('forbidden_case_count')}",
        ],
        [
            "no_evidence 误召回率",
            f"{no_evidence.get('false_positive_retrieval_rate')}",
            f"mean_top_score={no_evidence.get('mean_top_score')}",
        ],
        ["composite(透明加权)", f"{composite.get('value')}", f"weights={composite.get('weights')}"],
    ]
    note = summary.get("proxy_note", "")
    errors = summary.get("error_code_counts") or {}
    body = "## 总体指标\n\n" + _table(["指标", "值", "备注"], [[a, b, c] for a, b, c in rows])
    if errors:
        body += f"\n\n错误码分布:`{errors}`,重试合计 {summary.get('retry_total')} 次。"
    return body + f"\n\n> {note}"


def _render_categories(summary: dict[str, Any]) -> str:
    by_category = summary.get("by_category") or {}
    rows = [
        [
            name,
            str(data.get("attempted")),
            str(data.get("success_rate")),
            str(data.get("citation_hit")),
            str(data.get("keyword_coverage")),
            str(data.get("forbidden_claim_hit")),
            str(data.get("p50_latency_ms")),
            str(data.get("p95_latency_ms")),
        ]
        for name, data in by_category.items()
    ]
    headers = ["category", "n", "成功率", "citation_hit", "keyword_cov", "forbidden_hit", "P50 ms", "P95 ms"]
    return "## 分类别指标\n\n" + _table(headers, rows)


def _render_threshold(points: list[ThresholdPoint]) -> str:
    if not points:
        return "## minScore 阈值重放(Issue #4)\n\n本次运行无可用 citations,未生成曲线。"
    headers = [
        "threshold",
        "evidence_retained_hit",
        "expected_source_recall_proxy",
        "no_evidence_fp_rate",
        "avg_retained_citations",
        "empty_retrieval_rate",
        "balanced_f1",
    ]
    rows = [
        [
            f"{p.threshold:.2f}",
            f"{p.evidence_retained_hit:.3f}",
            f"{p.expected_source_recall_proxy:.3f}",
            f"{p.no_evidence_false_positive_rate:.3f}",
            f"{p.avg_retained_citations:.2f}",
            f"{p.empty_retrieval_rate:.3f}",
            f"{p.balanced_f1:.3f}",
        ]
        for p in points
    ]
    observed = points[0].observed_min_score
    body = "## minScore 阈值重放(Issue #4)\n\n" + _table(headers, rows)
    body += f"\n\n> {TRUNCATION_WARNING}"
    if observed is not None:
        body += f"\n>\n> 本次 run 观测到的最低 citation score = {observed:.4f}。"
    return body + f"\n\n{suggest_range(points)}\n\n本 PR 不修改后端默认 `RAG_MIN_SCORE=0.5`,不关闭 Issue #4。"


def _render_pollution(summary: dict[str, Any]) -> str:
    rows = [
        ["配对总数", str(summary.get("pair_count"))],
        ["可用配对(双侧成功且 B 一致)", str(summary.get("usable_pair_count"))],
        ["mean Δ keyword_coverage", str(summary.get("mean_delta_keyword_coverage"))],
        ["mean Δ forbidden_claim_hit", str(summary.get("mean_delta_forbidden_claim_hit"))],
        ["mean Δ citation_hit", str(summary.get("mean_delta_citation_hit"))],
        ["mean Δ source_recall", str(summary.get("mean_delta_source_recall"))],
        ["mean Δ mrr", str(summary.get("mean_delta_mrr"))],
        ["mean Δ citation_count", str(summary.get("mean_delta_citation_count"))],
        ["mean Δ answer_chars", str(summary.get("mean_delta_answer_chars"))],
        ["mean Δ latency (ms)", str(summary.get("mean_latency_delta_ms"))],
        ["出现退化的 case", ", ".join(summary.get("regressed_case_ids") or []) or "无"],
    ]
    body = "## 多轮历史摘录影响(Issue #3,fresh vs polluted)\n\n" + _table(["项", "值"], rows)
    violations = summary.get("integrity_violations") or []
    if violations:
        body += f"\n\n⚠️ 配对完整性异常:`{violations}`"
    return (
        body
        + f"\n\n> {summary.get('note')}\n\n"
        + "本 PR 只产出观测数据,不修改服务端 memory 行为,不关闭 Issue #3。"
    )


def _render_failures(records: list[CaseRecord]) -> str:
    failures = [r for r in records if not r.ok and r.variant != "priming"]
    if not failures:
        return "## 失败 case\n\n无。"
    rows = [
        [
            r.case_id,
            r.variant,
            str(r.http_status),
            r.error_code or "-",
            _truncate(r.error_message or "", 80),
        ]
        for r in failures
    ]
    return "## 失败 case\n\n" + _table(["case_id", "variant", "status", "code", "message"], rows)


def _render_cases(records: list[CaseRecord]) -> str:
    rows = []
    for record in records:
        if record.variant == "priming":
            continue
        status = "ok" if record.ok else f"fail({record.error_code or record.http_status})"
        rows.append(
            [
                f"`{record.case_id}`",
                record.category,
                record.variant,
                status,
                f"{record.metrics.get('citation_hit', 0.0):.0f}",
                f"{record.metrics.get('keyword_coverage', 0.0):.2f}",
                str(record.latency_ms),
                _truncate(record.answer, 90),
            ]
        )
    headers = ["case_id", "category", "variant", "状态", "cit_hit", "kw_cov", "ms", "answer 摘要"]
    return "## 逐题摘要\n\n" + _table(headers, rows) + "\n\n完整 answer 与 citations 见 `cases.jsonl`。"
