"""回答侧确定性指标:关键词 coverage、forbidden claim、结构与长度。

全部为 deterministic proxy——关键词命中不等于事实正确,命名与文档均如实标注。
"""

from __future__ import annotations

from ..models import Expectation

SHORT_ANSWER_CHARS = 20
"""低于该字符数视为过短答案的兜底阈值(case 未声明 min_answer_chars 时使用)。"""


def keyword_coverage(answer: str, keywords: list[str]) -> float:
    """命中关键词占比(大小写不敏感子串匹配);无关键词返回 0.0 且不参与平均。"""
    if not keywords:
        return 0.0
    lowered = answer.lower()
    hits = sum(1 for kw in keywords if kw.lower() in lowered)
    return hits / len(keywords)


def missing_keywords(answer: str, keywords: list[str]) -> list[str]:
    lowered = answer.lower()
    return [kw for kw in keywords if kw.lower() not in lowered]


def forbidden_claim_hit(answer: str, forbidden: list[str]) -> float:
    """命中任一禁止断言记 1.0(越低越好)。"""
    if not forbidden:
        return 0.0
    lowered = answer.lower()
    return 1.0 if any(claim.lower() in lowered for claim in forbidden) else 0.0


def hit_forbidden_claims(answer: str, forbidden: list[str]) -> list[str]:
    lowered = answer.lower()
    return [claim for claim in forbidden if claim.lower() in lowered]


def section_coverage(answer: str, sections: list[str]) -> float:
    """要求的 Markdown 小节标题命中占比。"""
    if not sections:
        return 0.0
    hits = sum(1 for section in sections if section in answer)
    return hits / len(sections)


def answer_metrics(answer: str, expected: Expectation) -> dict[str, float]:
    text = answer or ""
    min_chars = expected.min_answer_chars or SHORT_ANSWER_CHARS
    return {
        "answer_chars": float(len(text)),
        "empty_answer": 1.0 if not text.strip() else 0.0,
        "short_answer": 1.0 if len(text.strip()) < min_chars else 0.0,
        "keyword_coverage": keyword_coverage(text, expected.answer_keywords),
        "keyword_all_hit": (
            1.0 if expected.answer_keywords and not missing_keywords(text, expected.answer_keywords) else 0.0
        ),
        "forbidden_claim_hit": forbidden_claim_hit(text, expected.forbidden_claims),
        "section_coverage": section_coverage(text, expected.require_markdown_sections),
    }


def has_keyword_expectation(expected: Expectation) -> bool:
    return bool(expected.answer_keywords)


def has_forbidden_expectation(expected: Expectation) -> bool:
    return bool(expected.forbidden_claims)


def percentile(values: list[float], fraction: float) -> float:
    """最近秩百分位(nearest-rank),空输入返回 0.0。避免引入 numpy/pandas。"""
    if not values:
        return 0.0
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    rank = max(1, min(len(ordered), round(fraction * len(ordered) + 0.5)))
    return ordered[rank - 1]


def mean(values: list[float]) -> float:
    return sum(values) / len(values) if values else 0.0
