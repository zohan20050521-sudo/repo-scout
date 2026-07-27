"""数据集加载与校验:纯文件 I/O + schema 校验,不访问网络。"""

from __future__ import annotations

import hashlib
from pathlib import Path
from typing import Any

import yaml

from .models import CATEGORIES, Dataset, EvalCase


class DatasetError(Exception):
    """数据集文件缺失、YAML 非法或 schema 校验失败。"""


def load_dataset(path: str | Path) -> Dataset:
    dataset_path = Path(path)
    if not dataset_path.is_file():
        raise DatasetError(f"数据集文件不存在: {dataset_path}")
    try:
        raw: Any = yaml.safe_load(dataset_path.read_text(encoding="utf-8"))
    except yaml.YAMLError as exc:
        raise DatasetError(f"数据集 YAML 解析失败: {dataset_path}: {exc}") from exc
    if not isinstance(raw, dict):
        raise DatasetError(f"数据集根节点必须是映射: {dataset_path}")
    unknown = _unknown_categories(raw)
    if unknown:
        raise DatasetError(f"数据集含非法 category: {', '.join(unknown)};合法值: {', '.join(CATEGORIES)}")
    try:
        return Dataset.model_validate(raw)
    except DatasetError:
        raise
    except Exception as exc:
        raise DatasetError(f"数据集 schema 校验失败: {dataset_path}: {exc}") from exc


def _unknown_categories(raw: dict[str, Any]) -> list[str]:
    """先给出可读的 category 错误,避免 pydantic Literal 报错难以阅读。"""
    cases = raw.get("cases")
    if not isinstance(cases, list):
        return []
    bad: list[str] = []
    for case in cases:
        if isinstance(case, dict):
            category = case.get("category")
            if isinstance(category, str) and category not in CATEGORIES:
                bad.append(category)
    return sorted(set(bad))


def dataset_sha256(path: str | Path) -> str:
    """数据集内容哈希,写进 manifest 供复现比对。"""
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def category_counts(dataset: Dataset) -> dict[str, int]:
    counts: dict[str, int] = {}
    for case in dataset.cases:
        counts[case.category] = counts.get(case.category, 0) + 1
    return dict(sorted(counts.items()))


def select_cases(
    dataset: Dataset,
    only_cases: list[str] | None = None,
    only_categories: list[str] | None = None,
    seed: int | None = None,
) -> list[EvalCase]:
    """按 id/category 过滤;seed 提供时按 (hash(seed, id)) 稳定重排,否则保持数据集顺序。"""
    cases = list(dataset.cases)
    if only_cases:
        wanted = set(only_cases)
        cases = [c for c in cases if c.id in wanted]
    if only_categories:
        wanted_cat = set(only_categories)
        cases = [c for c in cases if c.category in wanted_cat]
    if seed is not None:
        cases.sort(key=lambda c: hashlib.sha256(f"{seed}:{c.id}".encode()).hexdigest())
    return cases
