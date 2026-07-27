"""单个 case 的执行逻辑:按 category 决定调用序列,产出 CaseRecord 列表。

只消费公开契约(chat / report),不读取数据库、Redis 或工具轨迹。
"""

from __future__ import annotations

import logging
import time
from collections.abc import Callable
from datetime import UTC, datetime

from .client import ApiCallError, ApiResult, RepoScoutClient
from .models import CaseRecord, ChatResponse, EvalCase, Expectation, Variant
from .scoring import empty_metrics, new_record, score_answer, score_chat, session_ref

log = logging.getLogger("repo_scout_eval.execution")

Sleeper = Callable[[float], None]


def _now() -> str:
    return datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


def _blank(
    case: EvalCase, variant: Variant, repetition: int, target_label: str, question: str, started_at: str
) -> CaseRecord:
    return new_record(case.id, case.category, variant, repetition, target_label, question, started_at)


def _fill_failure(
    record: CaseRecord,
    expected: Expectation,
    error: ApiCallError | None,
    result: ApiResult | None,
    latency_ms: int,
) -> CaseRecord:
    """失败记录:状态优先取响应,其次取异常;metrics 只保留门控位。"""
    record.latency_ms = latency_ms
    record.http_status = result.status if result else (error.status if error else None)
    code = result.error_code if result else (error.code if error else None)
    record.error_code = code or ("CALL_FAILED" if error else "UNKNOWN_ERROR")
    record.error_message = (
        result.error_message if result else (str(error) if error else None)
    ) or "调用失败,无可用错误消息"
    record.retry_count = result.retry_count if result else (error.retries if error else 0)
    record.metrics = empty_metrics(expected)
    return record


def _chat_record(
    case: EvalCase,
    variant: Variant,
    repetition: int,
    target_label: str,
    question: str,
    expected: Expectation,
    client: RepoScoutClient,
    session_id: str | None,
    repo_id: int | None,
    turn_index: int | None = None,
) -> tuple[CaseRecord, ChatResponse | None]:
    started_at = _now()
    record = _blank(case, variant, repetition, target_label, question, started_at)
    record.turn_index = turn_index
    began = time.perf_counter()
    try:
        result, response = client.chat(question, session_id=session_id, repo_id=repo_id, label=case.id)
    except ApiCallError as exc:
        latency = int((time.perf_counter() - began) * 1000)
        return _fill_failure(record, expected, exc, None, latency), None
    if response is None:
        return _fill_failure(record, expected, None, result, result.latency_ms), None
    record.latency_ms = result.latency_ms
    record.http_status = result.status
    record.retry_count = result.retry_count
    record.session_ref = session_ref(response.sessionId)
    record.answer = response.answer
    record.sources = list(response.sources)
    record.citations = list(response.citations)
    record.metrics = score_chat(response, expected)
    return record, response


def run_single(
    case: EvalCase,
    client: RepoScoutClient,
    repo_id: int,
    repetition: int,
    target_label: str,
) -> list[CaseRecord]:
    """rag_fact / rag_multi_source / tool_live / no_evidence:单轮新会话提问。"""
    question = case.question or ""
    record, _ = _chat_record(
        case, "single", repetition, target_label, question, case.expected, client, None, repo_id
    )
    return [record]


def run_conversation(
    case: EvalCase,
    client: RepoScoutClient,
    repo_id: int,
    repetition: int,
    target_label: str,
    pause_s: float,
    sleep: Sleeper,
) -> list[CaseRecord]:
    """conversation:同一 session 串行多轮,后续轮靠指代追问,必须顺序执行。"""
    records: list[CaseRecord] = []
    session_id: str | None = None
    for index, turn in enumerate(case.turns):
        record, response = _chat_record(
            case,
            "turn",
            repetition,
            target_label,
            turn.question,
            turn.expected,
            client,
            session_id,
            repo_id if session_id is None else None,
            turn_index=index,
        )
        records.append(record)
        if response is None:
            log.warning("case=%s 第 %s 轮失败,终止该会话剩余轮次", case.id, index)
            break
        session_id = response.sessionId
        if index + 1 < len(case.turns) and pause_s:
            sleep(pause_s)
    return records


def run_pollution_pair(
    case: EvalCase,
    client: RepoScoutClient,
    repo_id: int,
    repetition: int,
    target_label: str,
    pause_s: float,
    sleep: Sleeper,
) -> list[CaseRecord]:
    """pollution_pair(Issue #3):fresh 与 polluted 两条独立 session 路径,B 文本逐字一致。

    顺序为 fresh → priming×N → polluted(相邻执行,降低仓库动态变化影响)。
    priming 轮以 variant=priming 单独记录,不进普通题平均。
    """
    question = case.question or ""
    records: list[CaseRecord] = []

    fresh, _ = _chat_record(
        case, "fresh", repetition, target_label, question, case.expected, client, None, repo_id
    )
    records.append(fresh)
    if pause_s:
        sleep(pause_s)

    session_id: str | None = None
    for index, priming in enumerate(case.priming_questions):
        record, response = _chat_record(
            case,
            "priming",
            repetition,
            target_label,
            priming,
            Expectation(),
            client,
            session_id,
            repo_id if session_id is None else None,
            turn_index=index,
        )
        records.append(record)
        if response is None:
            log.warning("case=%s priming 第 %s 轮失败,polluted 路径不完整", case.id, index)
            return records
        session_id = response.sessionId
        if pause_s:
            sleep(pause_s)

    polluted, _ = _chat_record(
        case, "polluted", repetition, target_label, question, case.expected, client, session_id, None
    )
    records.append(polluted)
    return records


def run_report(
    case: EvalCase,
    client: RepoScoutClient,
    repo_id: int,
    repetition: int,
    target_label: str,
) -> list[CaseRecord]:
    """report_structure:只校验五节标题齐全与耗时,不作为主 RAG 质量分。"""
    started_at = _now()
    record = _blank(case, "single", repetition, target_label, case.question or "", started_at)
    began = time.perf_counter()
    try:
        result, response = client.report(repo_id, label=case.id)
    except ApiCallError as exc:
        latency = int((time.perf_counter() - began) * 1000)
        return [_fill_failure(record, case.expected, exc, None, latency)]
    if response is None:
        return [_fill_failure(record, case.expected, None, result, result.latency_ms)]
    metrics = score_answer(response.report, [], case.expected)
    metrics["report_cost_ms"] = float(response.costMs)
    record.latency_ms = result.latency_ms
    record.http_status = result.status
    record.retry_count = result.retry_count
    record.answer = response.report
    record.metrics = metrics
    return [record]


def execute_case(
    case: EvalCase,
    client: RepoScoutClient,
    repo_id: int,
    repetition: int,
    target_label: str,
    pause_s: float,
    sleep: Sleeper = time.sleep,
) -> list[CaseRecord]:
    """按 category 分派执行。"""
    if case.category == "conversation":
        return run_conversation(case, client, repo_id, repetition, target_label, pause_s, sleep)
    if case.category == "pollution_pair":
        return run_pollution_pair(case, client, repo_id, repetition, target_label, pause_s, sleep)
    if case.category == "report_structure":
        return run_report(case, client, repo_id, repetition, target_label)
    return run_single(case, client, repo_id, repetition, target_label)
