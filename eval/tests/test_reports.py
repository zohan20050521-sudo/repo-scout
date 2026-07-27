"""产物读写:JSONL/JSON/CSV/Markdown、Unicode、原子写与目录保护。"""

from __future__ import annotations

import csv
import json
from pathlib import Path

import pytest

from conftest import citation, make_record
from repo_scout_eval.metrics.summary import aggregate
from repo_scout_eval.metrics.threshold import replay
from repo_scout_eval.models import RunManifest
from repo_scout_eval.reports import markdown, writers


def manifest() -> RunManifest:
    return RunManifest(
        tool_version="0.4.0",
        run_id="20260727T120000Z-local",
        started_at="2026-07-27T12:00:00Z",
        finished_at="2026-07-27T12:05:00Z",
        python_version="3.11.9 (main)",
        dataset_path="datasets/v1.yaml",
        dataset_version="1",
        dataset_sha256="a" * 64,
        dataset_case_count=33,
        target_label="local",
        target_host="http://localhost:8080",
        repo="zohan20050521-sudo/repo-scout",
        repo_id=1,
        repo_head_commit="unknown",
        indexed=True,
        index_file_count=4,
        index_chunk_count=63,
    )


def test_prepare_run_dir_refuses_existing_non_empty(tmp_path: Path) -> None:
    run_dir = tmp_path / "run-1"
    run_dir.mkdir()
    (run_dir / "cases.jsonl").write_text("{}\n", encoding="utf-8")
    with pytest.raises(writers.ResultsError, match="--overwrite"):
        writers.prepare_run_dir(tmp_path, "run-1")
    assert writers.prepare_run_dir(tmp_path, "run-1", overwrite=True) == run_dir


def test_prepare_run_dir_allows_empty_existing(tmp_path: Path) -> None:
    (tmp_path / "run-2").mkdir()
    assert writers.prepare_run_dir(tmp_path, "run-2").name == "run-2"


def test_append_case_preserves_completed_records_and_unicode(tmp_path: Path) -> None:
    run_dir = writers.prepare_run_dir(tmp_path, "run")
    writers.append_case(run_dir, make_record(case_id="c1", answer="中文答案 ✓"))
    writers.append_case(run_dir, make_record(case_id="c2", answer="第二条"))
    raw = (run_dir / writers.CASES_FILE).read_text(encoding="utf-8")
    assert "中文答案 ✓" in raw, "UTF-8 原样写入,不做 ascii 转义"
    records = writers.read_cases(run_dir)
    assert [r.case_id for r in records] == ["c1", "c2"]


def test_read_cases_reports_bad_line(tmp_path: Path) -> None:
    run_dir = writers.prepare_run_dir(tmp_path, "run")
    (run_dir / writers.CASES_FILE).write_text("{not json}\n", encoding="utf-8")
    with pytest.raises(writers.ResultsError, match="JSON 解析失败"):
        writers.read_cases(run_dir)


def test_read_cases_missing_and_empty(tmp_path: Path) -> None:
    run_dir = writers.prepare_run_dir(tmp_path, "run")
    with pytest.raises(writers.ResultsError, match="缺少逐题结果文件"):
        writers.read_cases(run_dir)
    (run_dir / writers.CASES_FILE).write_text("\n", encoding="utf-8")
    with pytest.raises(writers.ResultsError, match="为空"):
        writers.read_cases(run_dir)


def test_manifest_roundtrip_excludes_no_secret_fields(tmp_path: Path) -> None:
    run_dir = writers.prepare_run_dir(tmp_path, "run")
    writers.write_manifest(run_dir, manifest())
    raw = (run_dir / writers.MANIFEST_FILE).read_text(encoding="utf-8")
    payload = json.loads(raw)
    assert "internal_key" not in raw.lower()
    assert payload["target_host"] == "http://localhost:8080"
    assert writers.read_manifest(run_dir).run_id == "20260727T120000Z-local"


def test_atomic_write_leaves_no_tmp_file(tmp_path: Path) -> None:
    target = tmp_path / "out" / "summary.md"
    writers.atomic_write_text(target, "内容")
    assert target.read_text(encoding="utf-8") == "内容"
    assert list(target.parent.glob(".*tmp")) == []


def test_metrics_csv_has_stable_columns_and_rounding(tmp_path: Path) -> None:
    run_dir = writers.prepare_run_dir(tmp_path, "run")
    records = [
        make_record(case_id="c1", metrics={"citation_hit": 1.0, "top_score": 0.8123456}),
        make_record(case_id="c2", metrics={"citation_hit": 0.0}),
    ]
    rows, columns = writers.case_metric_rows(records)
    writers.write_csv(run_dir, writers.METRICS_CSV, rows, columns)
    with (run_dir / writers.METRICS_CSV).open(encoding="utf-8") as handle:
        parsed = list(csv.DictReader(handle))
    assert parsed[0]["case_id"] == "c1"
    assert parsed[0]["top_score"] == "0.812346"
    assert parsed[1]["top_score"] == "", "缺失指标留空,列仍存在"
    assert "answer" not in columns, "CSV 不含长文本"


def test_write_csv_with_no_rows_still_writes_header(tmp_path: Path) -> None:
    run_dir = writers.prepare_run_dir(tmp_path, "run")
    writers.write_csv(run_dir, writers.THRESHOLD_CSV, [], ["threshold"])
    assert (run_dir / writers.THRESHOLD_CSV).read_text(encoding="utf-8") == "threshold\n"


def test_read_json_rejects_non_object(tmp_path: Path) -> None:
    run_dir = writers.prepare_run_dir(tmp_path, "run")
    (run_dir / "summary.json").write_text("[1,2]", encoding="utf-8")
    with pytest.raises(writers.ResultsError, match="JSON 对象"):
        writers.read_json(run_dir, "summary.json")


def test_markdown_summary_contains_required_sections_and_caveats() -> None:
    records = [
        make_record(
            case_id="fact-1",
            citations=[citation("docs/api.md", 0.81)],
            metrics={
                "citation_hit": 1.0,
                "keyword_coverage": 1.0,
                "has_retrieval_expectation": 1.0,
                "has_keyword_expectation": 1.0,
                "top_score": 0.81,
            },
            answer="根据 docs/api.md,统一错误码包括 INVALID_PARAM。" * 20,
            latency_ms=4200,
        ),
        make_record(
            case_id="none-1",
            category="no_evidence",
            citations=[citation("README.md", 0.758)],
            metrics={"expects_no_citations": 1.0, "false_positive_retrieval": 1.0, "top_score": 0.758},
            answer="仓库文档中未找到相关实现。",
        ),
    ]
    summary = aggregate(records)
    points = replay(records, [0.5, 0.75], {"fact-1": ["docs/api.md"]}, {"none-1"})
    pollution_summary = {
        "pair_count": 1,
        "usable_pair_count": 1,
        "mean_delta_keyword_coverage": -0.25,
        "mean_latency_delta_ms": 300.0,
        "regressed_case_ids": ["poll-1"],
        "note": "delta = polluted - fresh",
        "integrity_violations": [],
    }
    text = markdown.render_summary(manifest(), summary, records, points, pollution_summary)
    assert "## 运行清单" in text
    assert "## 总体指标" in text
    assert "## 分类别指标" in text
    assert "左截断" in text
    assert "当前后端默认 `RAG_MIN_SCORE=0.75`" in text
    assert "不关闭 Issue #3" in text
    assert "…(共" in text, "长答案在 Markdown 中截断"
    assert "cases.jsonl" in text


def test_markdown_reports_failures_and_empty_threshold() -> None:
    records = [
        make_record(case_id="c1", http_status=502, error_code="LLM_UNAVAILABLE", error_message="上游不可用")
    ]
    summary = aggregate(records)
    text = markdown.render_summary(manifest(), summary, records, [], None)
    assert "LLM_UNAVAILABLE" in text
    assert "未生成曲线" in text


def test_markdown_flags_unverified_min_score_label() -> None:
    unverified = manifest().model_copy(update={"min_score_label": 0.75})
    text = markdown.render_summary(unverified, aggregate([]), [], [], None)
    assert "未由服务端证明" in text
