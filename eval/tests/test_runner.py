"""端到端(mock 传输):前置准备、产物齐全、中断保留、脱网 summarize。"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import httpx
import pytest

from conftest import chat_payload
from repo_scout_eval.client import RepoScoutClient
from repo_scout_eval.config import Credentials, PrepareConfig, RetryPolicy, RunConfig, TargetConfig
from repo_scout_eval.datasets import load_dataset
from repo_scout_eval.metrics.summary import aggregate
from repo_scout_eval.prepare import PrepareError, check_health, prepare_repo
from repo_scout_eval.reports import writers
from repo_scout_eval.runner import build_summary, make_run_id, run_target

CITATION = {"filePath": "docs/api.md", "chunkIndex": 0, "excerpt": "错误码", "score": 0.81}

DATASET = """version: "1"
repo: owner/repo
cases:
  - id: fact-1
    category: rag_fact
    question: 错误码有哪些?
    expected:
      source_paths: [docs/api.md]
      answer_keywords: [INVALID_PARAM]
  - id: none-1
    category: no_evidence
    question: Kafka 配置在哪?
    expected:
      expect_no_citations: true
      forbidden_claims: ["topic 名为"]
  - id: poll-1
    category: pollution_pair
    question: 错误码有哪些?
    priming_questions: ["Docker 前置?", "记忆 TTL?"]
    expected:
      source_paths: [docs/api.md]
      answer_keywords: [INVALID_PARAM]
"""


def api_handler(chat_answer: str = "含 INVALID_PARAM 的答案", indexed: bool = True) -> Any:
    state = {"sessions": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path == "/api/health":
            return httpx.Response(200, json={"status": "UP", "application": "repo-scout"})
        if path == "/api/repos" and request.method == "POST":
            return httpx.Response(
                200, json={"id": 1, "owner": "owner", "name": "repo", "defaultBranch": "main"}
            )
        if path.endswith("/index-status"):
            return httpx.Response(
                200,
                json={
                    "repoId": 1,
                    "indexed": indexed,
                    "fileCount": 4 if indexed else 0,
                    "chunkCount": 63 if indexed else 0,
                    "indexedAt": "2026-07-27T12:00:00" if indexed else None,
                },
            )
        if path.endswith("/index"):
            return httpx.Response(200, json={"repoId": 1, "fileCount": 4, "chunkCount": 63, "costMs": 2450})
        if path == "/api/chat":
            body = json.loads(request.content.decode("utf-8"))
            session_id = body.get("sessionId")
            if not session_id:
                state["sessions"] += 1
                session_id = f"{state['sessions']:08d}-1111-1111-1111-111111111111"
            return httpx.Response(
                200,
                json=chat_payload(
                    answer=chat_answer, sources=["docs/api.md"], citations=[CITATION], session_id=session_id
                ),
            )
        return httpx.Response(404, json={"code": "NOT_FOUND", "message": path})

    return handler


def write_dataset(tmp_path: Path) -> Path:
    path = tmp_path / "ds.yaml"
    path.write_text(DATASET, encoding="utf-8")
    return path


def make_config(dataset_path: Path) -> RunConfig:
    return RunConfig(
        dataset=str(dataset_path),
        repo="owner/repo",
        targets=[TargetConfig(label="local", base_url="http://test.local", min_score_label=0.5)],
        prepare=PrepareConfig(),
        retry=RetryPolicy(max_attempts=2, backoff_s=0.0, jitter_s=0.0),
        request_pause_s=0.0,
        thresholds=[0.5, 0.75, 0.85],
    )


def run_with_handler(tmp_path: Path, handler: Any, config: RunConfig, dataset_path: Path) -> Any:
    """注入 MockTransport:替换 RepoScoutClient 构造以避免真实网络。"""
    import repo_scout_eval.runner as runner_module

    original = runner_module.RepoScoutClient

    def factory(base_url: str, credentials: Credentials, retry: Any = None) -> RepoScoutClient:
        return original(
            base_url, credentials, retry, transport=httpx.MockTransport(handler), sleep=lambda _s: None
        )

    runner_module.RepoScoutClient = factory  # type: ignore[misc, assignment]
    try:
        return run_target(
            config,
            config.targets[0],
            Credentials(internal_key=None, timeout_s=5.0),
            load_dataset(dataset_path),
            dataset_path,
            tmp_path / "results",
            run_id="testrun-local",
        )
    finally:
        runner_module.RepoScoutClient = original  # type: ignore[misc]


def test_run_produces_all_artifacts(tmp_path: Path) -> None:
    dataset_path = write_dataset(tmp_path)
    outcome = run_with_handler(tmp_path, api_handler(), make_config(dataset_path), dataset_path)
    for filename in (
        writers.CASES_FILE,
        writers.MANIFEST_FILE,
        writers.SUMMARY_JSON,
        writers.SUMMARY_MD,
        writers.METRICS_CSV,
        writers.THRESHOLD_CSV,
        writers.POLLUTION_CSV,
    ):
        assert (outcome.run_dir / filename).is_file(), f"缺少产物 {filename}"
    assert outcome.manifest.repo_id == 1
    assert outcome.manifest.indexed is True
    assert outcome.manifest.dataset_sha256
    assert outcome.manifest.finished_at
    assert outcome.failed_count == 0


def test_run_manifest_has_no_credentials(tmp_path: Path) -> None:
    dataset_path = write_dataset(tmp_path)
    outcome = run_with_handler(tmp_path, api_handler(), make_config(dataset_path), dataset_path)
    raw = (outcome.run_dir / writers.MANIFEST_FILE).read_text(encoding="utf-8")
    assert "Internal-Key" not in raw
    assert "internal_key" not in raw


def test_run_records_pollution_pair_and_threshold_rows(tmp_path: Path) -> None:
    dataset_path = write_dataset(tmp_path)
    outcome = run_with_handler(tmp_path, api_handler(), make_config(dataset_path), dataset_path)
    pollution_csv = (outcome.run_dir / writers.POLLUTION_CSV).read_text(encoding="utf-8")
    assert "poll-1" in pollution_csv
    assert "delta_keyword_coverage" in pollution_csv
    threshold_csv = (outcome.run_dir / writers.THRESHOLD_CSV).read_text(encoding="utf-8")
    assert threshold_csv.count("\n") == 4, "表头 + 3 个阈值"
    assert outcome.summary["pollution"]["usable_pair_count"] == 1


def test_run_variants_recorded(tmp_path: Path) -> None:
    dataset_path = write_dataset(tmp_path)
    outcome = run_with_handler(tmp_path, api_handler(), make_config(dataset_path), dataset_path)
    variants = [r.variant for r in outcome.records]
    assert variants.count("priming") == 2
    assert "fresh" in variants and "polluted" in variants


def test_run_does_not_overwrite_existing_dir(tmp_path: Path) -> None:
    dataset_path = write_dataset(tmp_path)
    config = make_config(dataset_path)
    run_with_handler(tmp_path, api_handler(), config, dataset_path)
    with pytest.raises(writers.ResultsError, match="--overwrite"):
        run_with_handler(tmp_path, api_handler(), config, dataset_path)


def test_partial_results_survive_mid_run_failure(tmp_path: Path) -> None:
    """第二个 case 抛出非 API 异常时,已完成的 case 仍留在 JSONL。"""
    dataset_path = write_dataset(tmp_path)
    calls = {"n": 0}
    base = api_handler()

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/chat":
            calls["n"] += 1
            if calls["n"] == 2:
                raise RuntimeError("模拟运行中崩溃")
        return base(request)

    with pytest.raises(RuntimeError, match="模拟运行中崩溃"):
        run_with_handler(tmp_path, handler, make_config(dataset_path), dataset_path)
    records = writers.read_cases(tmp_path / "results" / "testrun-local")
    assert [r.case_id for r in records] == ["fact-1"]


def test_summarize_rebuilds_offline_from_jsonl(tmp_path: Path) -> None:
    dataset_path = write_dataset(tmp_path)
    outcome = run_with_handler(tmp_path, api_handler(), make_config(dataset_path), dataset_path)
    records = writers.read_cases(outcome.run_dir)
    rebuilt = build_summary(records, load_dataset(dataset_path), [0.5, 0.75, 0.85])
    original = json.loads((outcome.run_dir / writers.SUMMARY_JSON).read_text(encoding="utf-8"))
    assert rebuilt["retrieval"] == original["retrieval"]
    assert rebuilt["threshold_curve"] == original["threshold_curve"]
    assert rebuilt["pollution"]["usable_pair_count"] == original["pollution"]["usable_pair_count"]


def test_aggregate_matches_written_summary(tmp_path: Path) -> None:
    dataset_path = write_dataset(tmp_path)
    outcome = run_with_handler(tmp_path, api_handler(), make_config(dataset_path), dataset_path)
    assert aggregate(outcome.records)["success_rate"] == outcome.summary["success_rate"]


def test_filter_leaving_no_cases_raises(tmp_path: Path) -> None:
    dataset_path = write_dataset(tmp_path)
    config = make_config(dataset_path).model_copy(update={"only_cases": ["missing"]})
    with pytest.raises(PrepareError, match="没有可执行的 case"):
        run_with_handler(tmp_path, api_handler(), config, dataset_path)


def test_make_run_id_format() -> None:
    run_id = make_run_id("local")
    assert run_id.endswith("-local")
    assert run_id[8] == "T" and run_id.split("-")[0].endswith("Z")


def test_health_failure_raises_prepare_error(credentials: Credentials) -> None:
    handler = lambda request: httpx.Response(200, json={"status": "DOWN"})  # noqa: E731
    client = RepoScoutClient(
        "http://test.local", credentials, transport=httpx.MockTransport(handler), sleep=lambda _s: None
    )
    with pytest.raises(PrepareError, match="status=DOWN"):
        check_health(client)
    client.close()


def test_require_index_without_auto_index_fails(credentials: Credentials) -> None:
    client = RepoScoutClient(
        "http://test.local",
        credentials,
        transport=httpx.MockTransport(api_handler(indexed=False)),
        sleep=lambda _s: None,
    )
    config = PrepareConfig(auto_index=False, require_index=True)
    with pytest.raises(PrepareError, match="未建索引"):
        prepare_repo(client, "owner/repo", config)
    client.close()


def test_auto_index_triggers_when_not_indexed(credentials: Credentials) -> None:
    seen: list[str] = []
    base = api_handler(indexed=False)

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request.url.path)
        if request.url.path.endswith("/index-status") and any(p.endswith("/index") for p in seen):
            return httpx.Response(
                200,
                json={
                    "repoId": 1,
                    "indexed": True,
                    "fileCount": 4,
                    "chunkCount": 63,
                    "indexedAt": "2026-07-27T12:00:00",
                },
            )
        return base(request)

    client = RepoScoutClient(
        "http://test.local", credentials, transport=httpx.MockTransport(handler), sleep=lambda _s: None
    )
    prepared = prepare_repo(client, "owner/repo", PrepareConfig())
    client.close()
    assert prepared.indexed_now is True
    assert prepared.status.indexed is True


def test_indexed_repo_not_reindexed_by_default(credentials: Credentials) -> None:
    seen: list[str] = []
    base = api_handler(indexed=True)

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(f"{request.method} {request.url.path}")
        return base(request)

    client = RepoScoutClient(
        "http://test.local", credentials, transport=httpx.MockTransport(handler), sleep=lambda _s: None
    )
    prepared = prepare_repo(client, "owner/repo", PrepareConfig())
    client.close()
    assert prepared.indexed_now is False
    assert not any(p == "POST /api/repos/1/index" for p in seen), "默认不重建已存在索引"
