"""结果文件读写:UTF-8、原子替换,失败时保留已写入内容。"""

from __future__ import annotations

import csv
import io
import json
import os
from collections.abc import Iterable, Sequence
from pathlib import Path
from typing import Any

from ..models import CaseRecord, RunManifest

CASES_FILE = "cases.jsonl"
MANIFEST_FILE = "manifest.json"
SUMMARY_JSON = "summary.json"
SUMMARY_MD = "summary.md"
METRICS_CSV = "metrics.csv"
THRESHOLD_CSV = "threshold_curve.csv"
POLLUTION_CSV = "pollution_pairs.csv"


class ResultsError(Exception):
    """结果目录冲突或产物不完整。"""


def prepare_run_dir(base: Path, run_id: str, overwrite: bool = False) -> Path:
    """创建 run 目录。已存在且非空时必须显式 overwrite,否则报错不覆盖。"""
    run_dir = base / run_id
    if run_dir.exists() and any(run_dir.iterdir()) and not overwrite:
        raise ResultsError(f"结果目录已存在且非空: {run_dir};如需覆盖请显式传入 --overwrite")
    run_dir.mkdir(parents=True, exist_ok=True)
    return run_dir


def atomic_write_text(path: Path, content: str) -> None:
    """同目录临时文件 + os.replace,避免半截文件。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(f".{path.name}.tmp")
    tmp.write_text(content, encoding="utf-8")
    os.replace(tmp, path)


def append_case(run_dir: Path, record: CaseRecord) -> None:
    """逐题追加写入,进程中断时已完成的 case 仍保留在 JSONL 中。"""
    line = json.dumps(record.model_dump(mode="json"), ensure_ascii=False)
    with (run_dir / CASES_FILE).open("a", encoding="utf-8") as handle:
        handle.write(line + "\n")
        handle.flush()


def read_cases(run_dir: Path) -> list[CaseRecord]:
    path = run_dir / CASES_FILE
    if not path.is_file():
        raise ResultsError(f"缺少逐题结果文件: {path}")
    records: list[CaseRecord] = []
    for lineno, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not raw.strip():
            continue
        try:
            payload = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise ResultsError(f"{path}:{lineno} JSON 解析失败: {exc}") from exc
        try:
            records.append(CaseRecord.model_validate(payload))
        except Exception as exc:
            raise ResultsError(f"{path}:{lineno} 记录 schema 非法: {exc}") from exc
    if not records:
        raise ResultsError(f"逐题结果文件为空: {path}")
    return records


def write_manifest(run_dir: Path, manifest: RunManifest) -> None:
    atomic_write_text(
        run_dir / MANIFEST_FILE,
        json.dumps(manifest.model_dump(mode="json"), ensure_ascii=False, indent=2) + "\n",
    )


def read_manifest(run_dir: Path) -> RunManifest:
    path = run_dir / MANIFEST_FILE
    if not path.is_file():
        raise ResultsError(f"缺少 manifest: {path}")
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise ResultsError(f"manifest JSON 解析失败: {path}: {exc}") from exc
    try:
        return RunManifest.model_validate(payload)
    except Exception as exc:
        raise ResultsError(f"manifest schema 非法: {path}: {exc}") from exc


def write_json(run_dir: Path, filename: str, payload: dict[str, Any]) -> None:
    atomic_write_text(run_dir / filename, json.dumps(payload, ensure_ascii=False, indent=2) + "\n")


def read_json(run_dir: Path, filename: str) -> dict[str, Any]:
    path = run_dir / filename
    if not path.is_file():
        raise ResultsError(f"缺少文件: {path}")
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ResultsError(f"{path} 根节点必须是 JSON 对象")
    return data


def write_csv(run_dir: Path, filename: str, rows: Sequence[dict[str, Any]], columns: Sequence[str]) -> None:
    """tidy CSV,便于后续自行绘图;无数据也写表头,保证列稳定。"""
    buffer = io.StringIO(newline="")
    writer = csv.DictWriter(buffer, fieldnames=list(columns), extrasaction="ignore", lineterminator="\n")
    writer.writeheader()
    for row in rows:
        writer.writerow(row)
    atomic_write_text(run_dir / filename, buffer.getvalue())


def case_metric_rows(records: Iterable[CaseRecord]) -> tuple[list[dict[str, Any]], list[str]]:
    """把逐题记录摊平为 tidy 行(不含 answer/excerpt 长文本)。"""
    base_columns = [
        "schema_version",
        "case_id",
        "category",
        "variant",
        "repetition",
        "turn_index",
        "target_label",
        "http_status",
        "error_code",
        "latency_ms",
        "retry_count",
        "session_ref",
    ]
    rows: list[dict[str, Any]] = []
    metric_names: set[str] = set()
    for record in records:
        row: dict[str, Any] = {name: getattr(record, name) for name in base_columns}
        for key, value in record.metrics.items():
            row[key] = round(value, 6)
            metric_names.add(key)
        rows.append(row)
    return rows, base_columns + sorted(metric_names)
