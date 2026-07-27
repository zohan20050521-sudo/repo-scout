"""回答指标:关键词 coverage、forbidden claim、结构、长度与分位数。"""

from __future__ import annotations

import pytest

from repo_scout_eval.metrics.answer import (
    answer_metrics,
    forbidden_claim_hit,
    hit_forbidden_claims,
    keyword_coverage,
    mean,
    missing_keywords,
    percentile,
    section_coverage,
)
from repo_scout_eval.models import Expectation


def test_keyword_coverage_is_case_insensitive_substring() -> None:
    answer = "统一错误码包括 invalid_param 与 REPO_NOT_FOUND。"
    assert keyword_coverage(answer, ["INVALID_PARAM", "REPO_NOT_FOUND"]) == pytest.approx(1.0)
    assert keyword_coverage(answer, ["INVALID_PARAM", "LLM_UNAVAILABLE"]) == pytest.approx(0.5)
    assert keyword_coverage(answer, []) == 0.0


def test_keyword_coverage_handles_chinese_and_numbers() -> None:
    answer = "默认值是 0.5,控制余弦相似度过滤。"
    assert keyword_coverage(answer, ["0.5", "相似度"]) == pytest.approx(1.0)
    assert missing_keywords(answer, ["0.5", "24h"]) == ["24h"]


def test_forbidden_claim_hit() -> None:
    answer = "该项目使用 Seata 实现分布式事务。"
    assert forbidden_claim_hit(answer, ["使用 Seata", "采用 TCC"]) == 1.0
    assert hit_forbidden_claims(answer, ["使用 Seata", "采用 TCC"]) == ["使用 Seata"]
    assert forbidden_claim_hit("仓库文档中未找到相关实现。", ["使用 Seata"]) == 0.0
    assert forbidden_claim_hit("任意答案", []) == 0.0


def test_section_coverage_exact_heading_match() -> None:
    report = "## 项目定位\n内容\n## 技术栈\n内容"
    assert section_coverage(report, ["## 项目定位", "## 技术栈"]) == pytest.approx(1.0)
    assert section_coverage(report, ["## 项目定位", "## 近期动向"]) == pytest.approx(0.5)


def test_answer_metrics_flags_empty_and_short() -> None:
    expected = Expectation(answer_keywords=["a"])
    assert answer_metrics("", expected)["empty_answer"] == 1.0
    assert answer_metrics("   ", expected)["empty_answer"] == 1.0
    assert answer_metrics("短", expected)["short_answer"] == 1.0
    assert answer_metrics("这是一个足够长的答案" * 5, expected)["short_answer"] == 0.0


def test_answer_metrics_respects_case_min_chars() -> None:
    text = "刚好三十个字符左右的一个中文答案哦"
    lenient = answer_metrics(text, Expectation(answer_keywords=["a"], min_answer_chars=5))
    strict = answer_metrics(text, Expectation(answer_keywords=["a"], min_answer_chars=200))
    assert lenient["short_answer"] == 0.0
    assert strict["short_answer"] == 1.0


def test_answer_metrics_keyword_all_hit() -> None:
    expected = Expectation(answer_keywords=["a", "b"])
    assert answer_metrics("a and b", expected)["keyword_all_hit"] == 1.0
    assert answer_metrics("only a", expected)["keyword_all_hit"] == 0.0


def test_answer_chars_counts_unicode_characters() -> None:
    assert answer_metrics("中文四字", Expectation(answer_keywords=["中"]))["answer_chars"] == 4.0


def test_percentile_and_mean() -> None:
    assert percentile([], 0.5) == 0.0
    assert percentile([10.0], 0.95) == 10.0
    values = [10.0, 20.0, 30.0, 40.0]
    assert percentile(values, 0.5) == 20.0
    assert percentile(values, 0.95) == 40.0
    assert mean(values) == 25.0
    assert mean([]) == 0.0
