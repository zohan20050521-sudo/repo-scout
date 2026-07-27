"""数据集 schema:唯一 id、非法 category、空预期、类别形状约束。"""

from __future__ import annotations

from pathlib import Path

import pytest

from repo_scout_eval.datasets import (
    DatasetError,
    category_counts,
    dataset_sha256,
    load_dataset,
    select_cases,
)
from repo_scout_eval.models import Dataset, EvalCase, Expectation

BASE = """version: "1"
repo: owner/repo
cases:
"""


def write(tmp_path: Path, body: str) -> Path:
    path = tmp_path / "ds.yaml"
    path.write_text(BASE + body, encoding="utf-8")
    return path


def test_repo_dataset_loads_and_covers_six_categories(repo_dataset_path: Path) -> None:
    dataset = load_dataset(repo_dataset_path)
    counts = category_counts(dataset)
    for category in (
        "rag_fact",
        "rag_multi_source",
        "tool_live",
        "no_evidence",
        "conversation",
        "pollution_pair",
    ):
        assert counts.get(category, 0) > 0, f"缺少类别 {category}"
    assert counts["rag_fact"] >= 8
    assert counts["no_evidence"] >= 5
    assert counts["pollution_pair"] >= 3
    assert len(dataset.cases) >= 20


def test_repo_dataset_source_paths_limited_to_indexed_scope(repo_dataset_path: Path) -> None:
    """索引范围只有 README + docs/**,标注不应引用不会被索引的文件。"""
    dataset = load_dataset(repo_dataset_path)
    for case in dataset.cases:
        for path in case.expected.source_paths + case.expected.allowed_source_paths:
            assert path == "README.md" or path.startswith("docs/"), f"{case.id} 引用了索引范围外的 {path}"


def test_duplicate_ids_rejected(tmp_path: Path) -> None:
    path = write(
        tmp_path,
        """  - id: dup
    category: rag_fact
    question: q1
    expected:
      answer_keywords: [a]
  - id: dup
    category: rag_fact
    question: q2
    expected:
      answer_keywords: [b]
""",
    )
    with pytest.raises(DatasetError, match="重复"):
        load_dataset(path)


def test_unknown_category_rejected_with_readable_message(tmp_path: Path) -> None:
    path = write(
        tmp_path,
        """  - id: c1
    category: mystery
    question: q
    expected:
      answer_keywords: [a]
""",
    )
    with pytest.raises(DatasetError, match="非法 category"):
        load_dataset(path)


def test_empty_expectation_rejected(tmp_path: Path) -> None:
    path = write(tmp_path, "  - id: c1\n    category: rag_fact\n    question: q\n")
    with pytest.raises(DatasetError, match="expected 不能为空"):
        load_dataset(path)


def test_empty_dataset_rejected(tmp_path: Path) -> None:
    path = tmp_path / "empty.yaml"
    path.write_text('version: "1"\nrepo: o/r\ncases: []\n', encoding="utf-8")
    with pytest.raises(DatasetError, match="不能为空"):
        load_dataset(path)


def test_missing_file_rejected(tmp_path: Path) -> None:
    with pytest.raises(DatasetError, match="不存在"):
        load_dataset(tmp_path / "nope.yaml")


def test_invalid_yaml_rejected(tmp_path: Path) -> None:
    path = tmp_path / "bad.yaml"
    path.write_text("version: [unclosed\n", encoding="utf-8")
    with pytest.raises(DatasetError, match="YAML 解析失败"):
        load_dataset(path)


def test_unknown_field_rejected(tmp_path: Path) -> None:
    path = write(
        tmp_path,
        """  - id: c1
    category: rag_fact
    question: q
    typo_field: 1
    expected:
      answer_keywords: [a]
""",
    )
    with pytest.raises(DatasetError, match="schema 校验失败"):
        load_dataset(path)


def test_conversation_requires_two_turns() -> None:
    with pytest.raises(ValueError, match="至少需要 2 轮"):
        EvalCase(
            id="c",
            category="conversation",
            turns=[{"question": "q", "expected": {"answer_keywords": ["a"]}}],  # type: ignore[list-item]
        )


def test_conversation_rejects_top_level_question() -> None:
    with pytest.raises(ValueError, match="不应带 question"):
        EvalCase(
            id="c",
            category="conversation",
            question="q",
            turns=[
                {"question": "q1", "expected": {"answer_keywords": ["a"]}},  # type: ignore[list-item]
                {"question": "q2", "expected": {"answer_keywords": ["b"]}},  # type: ignore[list-item]
            ],
        )


def test_pollution_pair_requires_two_to_four_priming() -> None:
    with pytest.raises(ValueError, match="2–4 个 priming_questions"):
        EvalCase(
            id="p",
            category="pollution_pair",
            question="B",
            priming_questions=["a"],
            expected=Expectation(answer_keywords=["x"]),
        )
    with pytest.raises(ValueError, match="2–4 个 priming_questions"):
        EvalCase(
            id="p",
            category="pollution_pair",
            question="B",
            priming_questions=["a", "b", "c", "d", "e"],
            expected=Expectation(answer_keywords=["x"]),
        )


def test_priming_only_allowed_for_pollution_pair() -> None:
    with pytest.raises(ValueError, match="仅用于 pollution_pair"):
        EvalCase(
            id="f",
            category="rag_fact",
            question="q",
            priming_questions=["a", "b"],
            expected=Expectation(answer_keywords=["x"]),
        )


def test_no_evidence_requires_forbidden_claims() -> None:
    with pytest.raises(ValueError, match="forbidden_claims"):
        EvalCase(
            id="n",
            category="no_evidence",
            question="q",
            expected=Expectation(expect_no_citations=True),
        )


def test_report_structure_requires_sections() -> None:
    with pytest.raises(ValueError, match="require_markdown_sections"):
        EvalCase(
            id="r",
            category="report_structure",
            question="q",
            expected=Expectation(answer_keywords=["x"]),
        )


def test_select_cases_filters_and_seed_is_stable(simple_dataset: Dataset) -> None:
    assert [c.id for c in select_cases(simple_dataset, only_cases=["none-1"])] == ["none-1"]
    assert [c.id for c in select_cases(simple_dataset, only_categories=["rag_fact"])] == ["fact-1"]
    first = [c.id for c in select_cases(simple_dataset, seed=42)]
    second = [c.id for c in select_cases(simple_dataset, seed=42)]
    assert first == second


def test_dataset_sha256_changes_with_content(tmp_path: Path) -> None:
    path = write(
        tmp_path,
        "  - id: c1\n    category: rag_fact\n    question: q\n    expected:\n      answer_keywords: [a]\n",
    )
    first = dataset_sha256(path)
    path.write_text(path.read_text(encoding="utf-8") + "\n# comment\n", encoding="utf-8")
    assert dataset_sha256(path) != first
