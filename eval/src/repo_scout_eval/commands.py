"""四个 CLI 子命令的实现。Rich 输出与退出码语义集中在此。"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from rich.console import Console
from rich.table import Table

from .config import Credentials, RunConfig, load_run_config, resolve_relative
from .datasets import category_counts, load_dataset
from .exit_codes import EXIT_OK, EXIT_PARTIAL_FAILURE
from .models import CaseRecord, Dataset, EvalCase
from .reports import writers
from .runner import RunOutcome, build_summary, load_dataset_for_run, make_run_id, run_target

CATEGORY_MINIMUMS: dict[str, int] = {
    "rag_fact": 8,
    "rag_multi_source": 3,
    "tool_live": 4,
    "no_evidence": 5,
    "conversation": 2,
    "pollution_pair": 3,
}
"""任务书约定的类别下限;validate 低于下限时给出警告(不判失败)。"""


def cmd_validate(console: Console, dataset_path: Path) -> int:
    """离线校验数据集:唯一 id、category、字段与预期约束,不访问网络。"""
    dataset = load_dataset(dataset_path)
    counts = category_counts(dataset)
    table = Table(title=f"数据集 {dataset_path} (v{dataset.version}, repo={dataset.repo})")
    table.add_column("category")
    table.add_column("数量", justify="right")
    table.add_column("下限", justify="right")
    table.add_column("状态")
    for category, count in counts.items():
        minimum = CATEGORY_MINIMUMS.get(category)
        state = "-" if minimum is None else ("ok" if count >= minimum else "低于建议下限")
        table.add_row(category, str(count), "-" if minimum is None else str(minimum), state)
    console.print(table)
    console.print(f"共 {len(dataset.cases)} 个 case,schema 校验通过。")
    _print_expectation_stats(console, dataset)
    return EXIT_OK


def _print_expectation_stats(console: Console, dataset: Dataset) -> None:
    with_paths = sum(1 for c in dataset.cases if c.expected.source_paths)
    with_keywords = sum(1 for c in dataset.cases if c.expected.answer_keywords)
    no_citation = sum(1 for c in dataset.cases if c.expected.expect_no_citations)
    turns = sum(len(c.turns) for c in dataset.cases)
    priming = sum(len(c.priming_questions) for c in dataset.cases)
    console.print(
        f"标注覆盖:source_paths={with_paths} answer_keywords={with_keywords} "
        f"expect_no_citations={no_citation};多轮 turns={turns},pollution priming={priming}"
    )


def cmd_run(
    console: Console,
    config_path: Path,
    overwrite: bool,
    overrides: dict[str, Any],
) -> tuple[int, list[RunOutcome]]:
    """跑一次完整评测(可含多个 target),返回退出码与各 target 结果。"""
    config = load_run_config(config_path)
    config = config.model_copy(update={k: v for k, v in overrides.items() if v is not None})
    # 配置里的相对路径按当前工作目录解析(README 约定 `cd eval` 后运行),
    # 与手输 --dataset 的行为一致,避免同一相对路径在两处含义不同。
    base_dir = Path.cwd()
    dataset, dataset_path = load_dataset_for_run(config, base_dir)
    credentials = Credentials.from_env()
    output_base = resolve_relative(base_dir, config.output_dir)

    console.print(
        f"[bold]数据集[/bold] {dataset_path} v{dataset.version} · {len(dataset.cases)} case · "
        f"target {len(config.targets)} 个 · internal key {credentials.key_state()}"
    )
    outcomes: list[RunOutcome] = []
    exit_code = EXIT_OK
    for target in config.targets:
        run_id = make_run_id(target.label)
        console.print(f"[bold cyan]▶ target {target.label}[/bold cyan] → {target.host()} (run {run_id})")
        outcome = run_target(
            config,
            target,
            credentials,
            dataset,
            dataset_path,
            output_base,
            overwrite=overwrite,
            progress=_make_progress(console),
            run_id=run_id,
        )
        outcomes.append(outcome)
        _print_outcome(console, outcome)
        if outcome.failed_count:
            exit_code = EXIT_PARTIAL_FAILURE
    if len(outcomes) > 1:
        _print_target_comparison(console, outcomes)
    return exit_code, outcomes


def _make_progress(console: Console) -> Any:
    def report(case: EvalCase, records: list[CaseRecord]) -> None:
        scored = [r for r in records if r.variant != "priming"]
        failed = [r for r in scored if not r.ok]
        status = "[green]ok[/green]" if not failed else f"[red]fail×{len(failed)}[/red]"
        latency = max((r.latency_ms for r in scored), default=0)
        hint = ""
        if scored:
            hint = (
                f" cit_hit={max(r.metrics.get('citation_hit', 0.0) for r in scored):.0f}"
                f" kw={max(r.metrics.get('keyword_coverage', 0.0) for r in scored):.2f}"
            )
        console.print(f"  {status} {case.id} [{case.category}] {latency}ms{hint}")

    return report


def _print_outcome(console: Console, outcome: RunOutcome) -> None:
    summary = outcome.summary
    retrieval = summary.get("retrieval", {})
    answer = summary.get("answer", {})
    console.print(
        f"  成功率 {summary.get('success_rate')} · citation_hit {retrieval.get('citation_hit')} · "
        f"keyword_coverage {answer.get('keyword_coverage_proxy')} · "
        f"P50 {summary.get('latency_ms', {}).get('p50')}ms"
    )
    console.print(f"  产物目录:{outcome.run_dir}")


def _print_target_comparison(console: Console, outcomes: list[RunOutcome]) -> None:
    table = Table(title="多 target 横向对比(min_score_label 未由服务端证明)")
    table.add_column("target")
    table.add_column("min_score_label")
    table.add_column("成功率")
    table.add_column("citation_hit")
    table.add_column("no_evidence_fp")
    table.add_column("kw_cov")
    for outcome in outcomes:
        summary = outcome.summary
        table.add_row(
            outcome.manifest.target_label,
            str(outcome.manifest.min_score_label),
            str(summary.get("success_rate")),
            str(summary.get("retrieval", {}).get("citation_hit")),
            str(summary.get("no_evidence", {}).get("false_positive_retrieval_rate")),
            str(summary.get("answer", {}).get("keyword_coverage_proxy")),
        )
    console.print(table)


def cmd_summarize(console: Console, run_dir: Path, dataset_override: Path | None) -> int:
    """从已有 JSONL 脱网重建汇总,证明指标计算与 live I/O 解耦。"""
    records = writers.read_cases(run_dir)
    manifest = writers.read_manifest(run_dir)
    dataset_path = dataset_override or Path(manifest.dataset_path)
    dataset = load_dataset(dataset_path)
    thresholds = _thresholds_from(manifest.run_params)
    summary = build_summary(records, dataset, thresholds)
    from .runner import write_artifacts

    write_artifacts(run_dir, manifest, records, dataset, thresholds, summary)
    console.print(f"已从 {len(records)} 条记录重建汇总:{run_dir}")
    _print_outcome(console, RunOutcome(run_dir, manifest, records, summary))
    return EXIT_PARTIAL_FAILURE if manifest.failed_case_count else EXIT_OK


def _thresholds_from(run_params: dict[str, Any]) -> list[float]:
    from .config import DEFAULT_THRESHOLDS

    raw = run_params.get("thresholds")
    if isinstance(raw, list) and raw:
        return [float(value) for value in raw]
    return list(DEFAULT_THRESHOLDS)


COMPARE_ROWS: tuple[tuple[str, str, str], ...] = (
    ("成功率", "success_rate", ""),
    ("citation_hit", "retrieval", "citation_hit"),
    ("source_recall", "retrieval", "source_recall"),
    ("citation_precision_proxy", "retrieval", "citation_precision_proxy"),
    ("mrr", "retrieval", "mrr"),
    ("keyword_coverage_proxy", "answer", "keyword_coverage_proxy"),
    ("forbidden_claim_hit_rate", "answer", "forbidden_claim_hit_rate"),
    ("no_evidence_fp_rate", "no_evidence", "false_positive_retrieval_rate"),
    ("p50_latency_ms", "latency_ms", "p50"),
    ("p95_latency_ms", "latency_ms", "p95"),
)


def cmd_compare(console: Console, run_a: Path, run_b: Path) -> int:
    """展示两个 run 的主要指标差值,并对 target/dataset 不一致给警告。"""
    manifest_a, manifest_b = writers.read_manifest(run_a), writers.read_manifest(run_b)
    summary_a = writers.read_json(run_a, writers.SUMMARY_JSON)
    summary_b = writers.read_json(run_b, writers.SUMMARY_JSON)

    for message in _compare_warnings(manifest_a, manifest_b):
        console.print(f"[yellow]⚠ {message}[/yellow]")

    table = Table(title=f"{manifest_a.run_id} → {manifest_b.run_id}")
    table.add_column("指标")
    table.add_column("A", justify="right")
    table.add_column("B", justify="right")
    table.add_column("Δ (B-A)", justify="right")
    for label, group, key in COMPARE_ROWS:
        value_a = _pick(summary_a, group, key)
        value_b = _pick(summary_b, group, key)
        delta = "-" if value_a is None or value_b is None else f"{value_b - value_a:+.4f}"
        table.add_row(label, _fmt(value_a), _fmt(value_b), delta)
    console.print(table)
    return EXIT_OK


def _compare_warnings(manifest_a: Any, manifest_b: Any) -> list[str]:
    warnings: list[str] = []
    if manifest_a.dataset_sha256 != manifest_b.dataset_sha256:
        warnings.append("两个 run 的数据集内容哈希不同,指标差值不可直接比较")
    if manifest_a.dataset_version != manifest_b.dataset_version:
        warnings.append(f"数据集版本不同:{manifest_a.dataset_version} vs {manifest_b.dataset_version}")
    if manifest_a.target_label != manifest_b.target_label or manifest_a.target_host != manifest_b.target_host:
        warnings.append(
            f"target 不同:{manifest_a.target_label}@{manifest_a.target_host} vs "
            f"{manifest_b.target_label}@{manifest_b.target_host}"
        )
    if manifest_a.min_score_label != manifest_b.min_score_label:
        warnings.append("min_score_label 不同,且该标签由操作者声明、未由服务端证明,差值不能当作阈值因果结论")
    if manifest_a.repo != manifest_b.repo:
        warnings.append(f"目标仓库不同:{manifest_a.repo} vs {manifest_b.repo}")
    return warnings


def _pick(summary: dict[str, Any], group: str, key: str) -> float | None:
    node: Any = summary.get(group)
    if not key:
        return float(node) if isinstance(node, int | float) else None
    if isinstance(node, dict) and isinstance(node.get(key), int | float):
        return float(node[key])
    return None


def _fmt(value: float | None) -> str:
    return "-" if value is None else f"{value:.4f}"


def resolve_config_path(value: str) -> Path:
    return Path(value)


__all__ = [
    "CATEGORY_MINIMUMS",
    "RunConfig",
    "cmd_compare",
    "cmd_run",
    "cmd_summarize",
    "cmd_validate",
    "resolve_config_path",
]
