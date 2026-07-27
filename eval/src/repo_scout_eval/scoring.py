"""把一次 API 调用的结果与 case 预期组合成 CaseRecord。纯函数,不做 I/O。

metrics 里的 has_* / expects_no_citations 是**门控位**:标记该题参与哪些平均值,
让汇总阶段无需再读数据集即可复算(summarize 脱网重建的前提)。
"""

from __future__ import annotations

import hashlib

from .metrics.answer import answer_metrics, has_forbidden_expectation, has_keyword_expectation
from .metrics.retrieval import has_retrieval_expectation, retrieval_metrics
from .models import CaseRecord, Category, ChatResponse, Citation, Expectation, Variant

SESSION_REF_LEN = 12


def session_ref(session_id: str | None) -> str | None:
    """会话 id 只以散列前缀入库,不保存可复用的敏感状态。"""
    if not session_id:
        return None
    return hashlib.sha256(session_id.encode("utf-8")).hexdigest()[:SESSION_REF_LEN]


def gates(expected: Expectation) -> dict[str, float]:
    return {
        "has_retrieval_expectation": 1.0 if has_retrieval_expectation(expected) else 0.0,
        "has_keyword_expectation": 1.0 if has_keyword_expectation(expected) else 0.0,
        "has_forbidden_expectation": 1.0 if has_forbidden_expectation(expected) else 0.0,
        "expects_no_citations": 1.0 if expected.expect_no_citations else 0.0,
    }


def score_answer(answer: str, citations: list[Citation], expected: Expectation) -> dict[str, float]:
    metrics = retrieval_metrics(citations, expected)
    metrics.update(answer_metrics(answer, expected))
    metrics.update(gates(expected))
    return metrics


def score_chat(response: ChatResponse, expected: Expectation) -> dict[str, float]:
    return score_answer(response.answer, list(response.citations), expected)


def empty_metrics(expected: Expectation) -> dict[str, float]:
    """调用失败时仍记录门控位,便于统计「本该参与但失败」的题。"""
    return gates(expected)


def new_record(
    case_id: str,
    category: Category,
    variant: Variant,
    repetition: int,
    target_label: str,
    question: str,
    started_at: str,
) -> CaseRecord:
    """建一条只填公共字段的记录,调用方再补 latency / metrics 等。"""
    return CaseRecord(
        case_id=case_id,
        category=category,
        variant=variant,
        repetition=repetition,
        target_label=target_label,
        question=question,
        started_at=started_at,
    )
