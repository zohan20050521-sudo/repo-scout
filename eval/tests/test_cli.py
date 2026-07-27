"""CLI:validate/summarize/compare 全程脱网,退出码与警告语义。"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from conftest import citation, make_record
from repo_scout_eval.cli import main
from repo_scout_eval.exit_codes import (
    EXIT_CONFIG_ERROR,
    EXIT_OK,
    EXIT_PARTIAL_FAILURE,
    EXIT_PRECHECK_FAILED,
)
from repo_scout_eval.metrics.summary import aggregate
from repo_scout_eval.models import RunManifest
from repo_scout_eval.reports import writers

DATASET = """version: "1"
repo: owner/repo
cases:
  - id: fact-1
    category: rag_fact
    question: 错误码有哪些?
    expected:
      source_paths: [docs/api.md]
      answer_keywords: [INVALID_PARAM]
"""


def build_run(
    tmp_path: Path,
    run_id: str,
    dataset_path: Path,
    dataset_sha: str = "a" * 64,
    target_label: str = "local",
    min_score_label: float | None = None,
    failed: int = 0,
    citation_hit: float = 1.0,
) -> Path:
    run_dir = writers.prepare_run_dir(tmp_path / "results", run_id)
    record = make_record(
        case_id="fact-1",
        citations=[citation("docs/api.md", 0.81)],
        metrics={
            "citation_hit": citation_hit,
            "keyword_coverage": 1.0,
            "has_retrieval_expectation": 1.0,
            "has_keyword_expectation": 1.0,
            "top_score": 0.81,
        },
        answer="根据 docs/api.md,含 INVALID_PARAM。",
        latency_ms=3000,
    )
    writers.append_case(run_dir, record)
    manifest = RunManifest(
        tool_version="0.4.0",
        run_id=run_id,
        started_at="2026-07-27T12:00:00Z",
        finished_at="2026-07-27T12:05:00Z",
        python_version="3.11.9",
        dataset_path=str(dataset_path),
        dataset_version="1",
        dataset_sha256=dataset_sha,
        dataset_case_count=1,
        target_label=target_label,
        target_host="http://localhost:8080",
        min_score_label=min_score_label,
        repo="owner/repo",
        repo_id=1,
        failed_case_count=failed,
        run_params={"thresholds": [0.5, 0.8]},
    )
    writers.write_manifest(run_dir, manifest)
    writers.write_json(run_dir, writers.SUMMARY_JSON, aggregate([record]))
    return run_dir


@pytest.fixture
def dataset_path(tmp_path: Path) -> Path:
    path = tmp_path / "ds.yaml"
    path.write_text(DATASET, encoding="utf-8")
    return path


def test_validate_repo_dataset_exits_zero(repo_dataset_path: Path) -> None:
    assert main(["validate", "--dataset", str(repo_dataset_path)]) == EXIT_OK


def test_validate_bad_dataset_returns_config_error(tmp_path: Path) -> None:
    bad = tmp_path / "bad.yaml"
    bad.write_text('version: "1"\nrepo: o/r\ncases: []\n', encoding="utf-8")
    assert main(["validate", "--dataset", str(bad)]) == EXIT_CONFIG_ERROR


def test_validate_missing_dataset_returns_config_error(tmp_path: Path) -> None:
    assert main(["validate", "--dataset", str(tmp_path / "none.yaml")]) == EXIT_CONFIG_ERROR


def test_summarize_rebuilds_without_network(tmp_path: Path, dataset_path: Path) -> None:
    run_dir = build_run(tmp_path, "run-a", dataset_path)
    (run_dir / writers.SUMMARY_MD).unlink(missing_ok=True)
    assert main(["summarize", str(run_dir)]) == EXIT_OK
    assert (run_dir / writers.SUMMARY_MD).is_file()
    assert (run_dir / writers.THRESHOLD_CSV).is_file()
    summary = json.loads((run_dir / writers.SUMMARY_JSON).read_text(encoding="utf-8"))
    assert summary["retrieval"]["citation_hit"] == 1.0
    assert summary["threshold_curve"][0]["threshold"] == 0.5


def test_summarize_uses_dataset_override(tmp_path: Path, dataset_path: Path) -> None:
    run_dir = build_run(tmp_path, "run-a", tmp_path / "moved.yaml")
    assert main(["summarize", str(run_dir), "--dataset", str(dataset_path)]) == EXIT_OK


def test_summarize_reports_partial_failure_exit_code(tmp_path: Path, dataset_path: Path) -> None:
    run_dir = build_run(tmp_path, "run-f", dataset_path, failed=1)
    assert main(["summarize", str(run_dir)]) == EXIT_PARTIAL_FAILURE


def test_summarize_missing_dir_returns_config_error(tmp_path: Path) -> None:
    assert main(["summarize", str(tmp_path / "nope")]) == EXIT_CONFIG_ERROR


def test_compare_same_dataset_no_warning(
    tmp_path: Path, dataset_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    run_a = build_run(tmp_path, "run-a", dataset_path)
    run_b = build_run(tmp_path, "run-b", dataset_path)
    assert main(["compare", str(run_a), str(run_b)]) == EXIT_OK
    output = capsys.readouterr().out
    assert "⚠" not in output
    assert "citation_hit" in output


def test_compare_warns_on_dataset_mismatch(
    tmp_path: Path, dataset_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    run_a = build_run(tmp_path, "run-a", dataset_path, dataset_sha="a" * 64)
    run_b = build_run(tmp_path, "run-b", dataset_path, dataset_sha="b" * 64)
    assert main(["compare", str(run_a), str(run_b)]) == EXIT_OK
    output = capsys.readouterr().out
    assert "数据集内容哈希不同" in output


def test_compare_warns_on_target_and_min_score_mismatch(
    tmp_path: Path, dataset_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    run_a = build_run(tmp_path, "run-a", dataset_path, target_label="minscore-050", min_score_label=0.5)
    run_b = build_run(tmp_path, "run-b", dataset_path, target_label="minscore-075", min_score_label=0.75)
    assert main(["compare", str(run_a), str(run_b)]) == EXIT_OK
    output = capsys.readouterr().out
    assert "target 不同" in output
    assert "未由服务端证明" in output


def test_compare_shows_metric_delta(
    tmp_path: Path, dataset_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    run_a = build_run(tmp_path, "run-a", dataset_path, citation_hit=1.0)
    run_b = build_run(tmp_path, "run-b", dataset_path, citation_hit=0.0)
    main(["compare", str(run_a), str(run_b)])
    output = capsys.readouterr().out.replace("\n", "")
    assert "-1.0000" in output


def test_run_precheck_failure_exit_code(tmp_path: Path, dataset_path: Path) -> None:
    """base_url 指向不可用端口:前置健康检查失败 → EXIT_PRECHECK_FAILED。"""
    config = tmp_path / "config.yaml"
    config.write_text(
        f"dataset: {dataset_path}\n"
        "repo: owner/repo\n"
        f"output_dir: {tmp_path / 'results'}\n"
        "request_pause_s: 0.0\n"
        "retry:\n  max_attempts: 1\n  backoff_s: 0.0\n  jitter_s: 0.0\n"
        "targets:\n  - label: dead\n    base_url: http://127.0.0.1:9\n",
        encoding="utf-8",
    )
    assert main(["run", "--config", str(config)]) == EXIT_PRECHECK_FAILED


def test_run_missing_config_returns_config_error(tmp_path: Path) -> None:
    assert main(["run", "--config", str(tmp_path / "none.yaml")]) == EXIT_CONFIG_ERROR


def test_version_flag_exits_zero() -> None:
    with pytest.raises(SystemExit) as excinfo:
        main(["--version"])
    assert excinfo.value.code == 0
