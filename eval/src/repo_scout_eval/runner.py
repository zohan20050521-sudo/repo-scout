"""编排一次 run:前置准备 → 逐 case 执行 → 落盘产物。

并发默认 1;conversation / pollution_pair 必须串行,故本版本对全部 case 串行执行,
concurrency>1 仅对无 session 依赖的单轮 case 生效。
"""

from __future__ import annotations

import logging
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from . import __version__
from .client import RepoScoutClient, UnauthorizedError
from .config import Credentials, RunConfig, TargetConfig
from .datasets import dataset_sha256, load_dataset, select_cases
from .execution import execute_case
from .judge import JudgeError, LlmJudge
from .metrics import pollution, threshold
from .metrics import summary as summary_metrics
from .models import CaseRecord, Dataset, EvalCase, RunManifest
from .prepare import PrepareError, prepare_repo
from .prepare import check_health as _check_health
from .reports import markdown, writers

log = logging.getLogger("repo_scout_eval.runner")

SERIAL_CATEGORIES = frozenset({"conversation", "pollution_pair"})


@dataclass
class RunOutcome:
    run_dir: Path
    manifest: RunManifest
    records: list[CaseRecord]
    summary: dict[str, Any]

    @property
    def failed_count(self) -> int:
        return sum(1 for r in self.records if not r.ok and r.variant != "priming")


def make_run_id(target_label: str, now: datetime | None = None) -> str:
    stamp = (now or datetime.now(UTC)).strftime("%Y%m%dT%H%M%SZ")
    return f"{stamp}-{target_label}"


def run_target(
    config: RunConfig,
    target: TargetConfig,
    credentials: Credentials,
    dataset: Dataset,
    dataset_path: Path,
    output_base: Path,
    overwrite: bool = False,
    progress: Any = None,
    run_id: str | None = None,
) -> RunOutcome:
    """对单个 target 执行整套数据集,产物写入独立目录。"""
    cases = select_cases(dataset, config.only_cases, config.only_categories, config.seed)
    if not cases:
        raise PrepareError("过滤后没有可执行的 case,请检查 only_cases / only_categories")

    resolved_id = run_id or make_run_id(target.label)
    run_dir = writers.prepare_run_dir(output_base, resolved_id, overwrite=overwrite)
    started_at = datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")
    log.info(
        "run start id=%s target=%s host=%s internal_key=%s cases=%s",
        resolved_id,
        target.label,
        target.host(),
        credentials.key_state(),
        len(cases),
    )

    records: list[CaseRecord] = []
    manifest = RunManifest(
        tool_version=__version__,
        run_id=resolved_id,
        started_at=started_at,
        python_version=sys.version,
        dataset_path=str(dataset_path),
        dataset_version=dataset.version,
        dataset_sha256=dataset_sha256(dataset_path),
        dataset_case_count=len(dataset.cases),
        target_label=target.label,
        target_host=target.host(),
        min_score_label=target.min_score_label,
        min_score_label_verified=False,
        repo=config.repo,
        judge_enabled=config.judge.enabled,
        run_params=_run_params(config, target, len(cases)),
    )
    writers.write_manifest(run_dir, manifest)

    with RepoScoutClient(target.resolved_base_url(), credentials, config.retry) as client:
        _check_health(client)
        prepared = prepare_repo(client, config.repo, config.prepare)
        manifest.repo_id = prepared.repo_id
        manifest.repo_default_branch = prepared.default_branch
        manifest.indexed = prepared.status.indexed
        manifest.index_file_count = prepared.status.fileCount
        manifest.index_chunk_count = prepared.status.chunkCount
        writers.write_manifest(run_dir, manifest)

        for repetition in range(1, config.repetitions + 1):
            records += _execute_repetition(
                cases, client, config, target, prepared.repo_id, repetition, run_dir, progress
            )

    _apply_judge(config, records, dataset, manifest)
    outcome = _finalize(run_dir, manifest, records, dataset, config)
    log.info("run done id=%s records=%s failed=%s", resolved_id, len(records), outcome.failed_count)
    return outcome


def _execute_repetition(
    cases: list[EvalCase],
    client: RepoScoutClient,
    config: RunConfig,
    target: TargetConfig,
    repo_id: int,
    repetition: int,
    run_dir: Path,
    progress: Any,
) -> list[CaseRecord]:
    serial = [c for c in cases if c.category in SERIAL_CATEGORIES or config.concurrency == 1]
    parallel = [c for c in cases if c not in serial]
    produced: list[CaseRecord] = []

    for case in serial:
        produced += _run_one(case, client, config, target, repo_id, repetition, run_dir, progress)
        if config.fail_fast and any(not r.ok and r.variant != "priming" for r in produced[-3:]):
            log.error("fail_fast 生效,停止后续 case: case=%s", case.id)
            return produced
    if parallel:
        with ThreadPoolExecutor(max_workers=config.concurrency) as pool:
            futures = [
                pool.submit(_run_one, case, client, config, target, repo_id, repetition, run_dir, progress)
                for case in parallel
            ]
            for future in futures:
                produced += future.result()
    return produced


def _run_one(
    case: EvalCase,
    client: RepoScoutClient,
    config: RunConfig,
    target: TargetConfig,
    repo_id: int,
    repetition: int,
    run_dir: Path,
    progress: Any,
) -> list[CaseRecord]:
    produced = execute_case(
        case, client, repo_id, repetition, target.label, config.request_pause_s, time.sleep
    )
    for record in produced:
        writers.append_case(run_dir, record)
    if progress is not None:
        progress(case, produced)
    if config.request_pause_s:
        time.sleep(config.request_pause_s)
    return produced


def _run_params(config: RunConfig, target: TargetConfig, case_count: int) -> dict[str, Any]:
    return {
        "selected_case_count": case_count,
        "repetitions": config.repetitions,
        "concurrency": config.concurrency,
        "seed": config.seed,
        "fail_fast": config.fail_fast,
        "only_cases": list(config.only_cases),
        "only_categories": list(config.only_categories),
        "thresholds": list(config.thresholds),
        "request_pause_s": config.request_pause_s,
        "retry": config.retry.model_dump(),
        "prepare": config.prepare.model_dump(),
        "target_label": target.label,
    }


def _apply_judge(
    config: RunConfig, records: list[CaseRecord], dataset: Dataset, manifest: RunManifest
) -> None:
    if not config.judge.enabled:
        return
    try:
        judge = LlmJudge.from_env(config.judge)
    except JudgeError as exc:
        log.warning("judge 初始化失败,跳过 judge 评分: %s", exc)
        return
    manifest.judge_prompt_version = judge.prompt_version
    questions = {case.id: (case.question or "") for case in dataset.cases}
    judged = 0
    for record in records:
        if judged >= config.judge.max_cases or not record.scored:
            continue
        record.judge = judge.evaluate(questions.get(record.case_id, record.question), record.answer)
        judged += 1
    judge.close()


def _finalize(
    run_dir: Path,
    manifest: RunManifest,
    records: list[CaseRecord],
    dataset: Dataset,
    config: RunConfig,
) -> RunOutcome:
    summary = build_summary(records, dataset, config.thresholds)
    manifest.finished_at = datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")
    manifest.case_count = len(records)
    manifest.failed_case_count = sum(1 for r in records if not r.ok and r.variant != "priming")
    writers.write_manifest(run_dir, manifest)
    write_artifacts(run_dir, manifest, records, dataset, config.thresholds, summary)
    return RunOutcome(run_dir, manifest, records, summary)


def expected_path_map(dataset: Dataset) -> dict[str, list[str]]:
    return {case.id: list(case.expected.source_paths) for case in dataset.cases}


def no_evidence_ids(dataset: Dataset) -> set[str]:
    return {case.id for case in dataset.cases if case.expected.expect_no_citations}


def build_summary(records: list[CaseRecord], dataset: Dataset, thresholds: list[float]) -> dict[str, Any]:
    summary = summary_metrics.aggregate(records)
    points = threshold.replay(records, thresholds, expected_path_map(dataset), no_evidence_ids(dataset))
    pairs = pollution.build_pairs(records)
    summary["threshold_curve"] = [p.as_row() for p in points]
    summary["threshold_note"] = threshold.TRUNCATION_WARNING
    summary["threshold_suggestion"] = threshold.suggest_range(points)
    summary["pollution"] = pollution.summarize_pairs(pairs) if pairs else None
    return summary


def write_artifacts(
    run_dir: Path,
    manifest: RunManifest,
    records: list[CaseRecord],
    dataset: Dataset,
    thresholds: list[float],
    summary: dict[str, Any],
) -> None:
    """写出 summary.json / summary.md / 三张 CSV。逐题 JSONL 已在执行中追加。"""
    points = threshold.replay(records, thresholds, expected_path_map(dataset), no_evidence_ids(dataset))
    pairs = pollution.build_pairs(records)
    writers.write_json(run_dir, writers.SUMMARY_JSON, summary)

    rows, columns = writers.case_metric_rows(records)
    writers.write_csv(run_dir, writers.METRICS_CSV, rows, columns)

    threshold_rows = [p.as_row() for p in points]
    threshold_columns = list(threshold_rows[0].keys()) if threshold_rows else ["threshold"]
    writers.write_csv(run_dir, writers.THRESHOLD_CSV, threshold_rows, threshold_columns)

    pair_rows = [p.as_row() for p in pairs]
    pair_columns = list(pair_rows[0].keys()) if pair_rows else ["case_id"]
    writers.write_csv(run_dir, writers.POLLUTION_CSV, pair_rows, pair_columns)

    writers.atomic_write_text(
        run_dir / writers.SUMMARY_MD,
        markdown.render_summary(manifest, summary, records, points, summary.get("pollution")),
    )


def load_dataset_for_run(config: RunConfig, base_dir: Path) -> tuple[Dataset, Path]:
    from .config import resolve_relative

    dataset_path = resolve_relative(base_dir, config.dataset)
    return load_dataset(dataset_path), dataset_path


__all__ = [
    "PrepareError",
    "RunOutcome",
    "UnauthorizedError",
    "build_summary",
    "load_dataset_for_run",
    "make_run_id",
    "run_target",
    "write_artifacts",
]
