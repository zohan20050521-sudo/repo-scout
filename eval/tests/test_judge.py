"""可选 LLM judge:完全 mock,不访问真实模型,失败只记 judge_error。"""

from __future__ import annotations

import httpx
import pytest

from repo_scout_eval.config import JudgeConfig
from repo_scout_eval.judge import PROMPT_VERSION, JudgeError, LlmJudge


def make_judge(handler: object) -> LlmJudge:
    client = httpx.Client(
        base_url="http://judge.local",
        transport=httpx.MockTransport(handler),  # type: ignore[arg-type]
        headers={"Authorization": "Bearer test"},
    )
    return LlmJudge("http://judge.local", "test", "test-model", 5.0, client=client)


def completion(content: str) -> httpx.Response:
    return httpx.Response(200, json={"choices": [{"message": {"content": content}}]})


def test_from_env_requires_all_three_variables() -> None:
    config = JudgeConfig(enabled=True)
    with pytest.raises(JudgeError, match="缺少环境变量"):
        LlmJudge.from_env(config, env={})
    with pytest.raises(JudgeError, match="EVAL_JUDGE_MODEL"):
        LlmJudge.from_env(config, env={"EVAL_JUDGE_BASE_URL": "http://x", "EVAL_JUDGE_API_KEY": "k"})


def test_valid_json_verdict_parsed() -> None:
    payload = '{"relevance":0.9,"groundedness":0.8,"completeness":0.7,"reason":"有据"}'
    judge = make_judge(lambda request: completion(payload))
    result = judge.evaluate("问题", "回答")
    judge.close()
    assert result["relevance"] == 0.9
    assert result["prompt_version"] == PROMPT_VERSION
    assert result["judge_error"] is None


def test_code_fenced_json_accepted() -> None:
    payload = '```json\n{"relevance":1,"groundedness":1,"completeness":1,"reason":"ok"}\n```'
    judge = make_judge(lambda request: completion(payload))
    result = judge.evaluate("问题", "回答")
    judge.close()
    assert result["judge_error"] is None


def test_non_json_output_records_error_only() -> None:
    judge = make_judge(lambda request: completion("这个回答挺好的"))
    result = judge.evaluate("问题", "回答")
    judge.close()
    assert result["judge_error"] == "judge 输出不是合法 JSON"
    assert "relevance" not in result


def test_out_of_range_score_rejected() -> None:
    payload = '{"relevance":1.5,"groundedness":0.5,"completeness":0.5,"reason":"x"}'
    judge = make_judge(lambda request: completion(payload))
    result = judge.evaluate("问题", "回答")
    judge.close()
    assert result["judge_error"] is not None
    assert "字段非法" in result["judge_error"]


def test_missing_field_rejected() -> None:
    judge = make_judge(lambda request: completion('{"relevance":0.5}'))
    result = judge.evaluate("问题", "回答")
    judge.close()
    assert result["judge_error"] is not None


def test_http_error_recorded_not_raised() -> None:
    judge = make_judge(lambda request: httpx.Response(500, text="boom"))
    result = judge.evaluate("问题", "回答")
    judge.close()
    assert result["judge_error"] == "judge 调用返回 HTTP 500"


def test_network_error_recorded_not_raised() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("down")

    judge = make_judge(handler)
    result = judge.evaluate("问题", "回答")
    judge.close()
    assert result["judge_error"] is not None
    assert "网络错误" in result["judge_error"]


def test_unexpected_response_shape_recorded() -> None:
    judge = make_judge(lambda request: httpx.Response(200, json={"unexpected": True}))
    result = judge.evaluate("问题", "回答")
    judge.close()
    assert result["judge_error"] is not None
    assert "响应结构异常" in result["judge_error"]


def test_judge_disabled_by_default() -> None:
    assert JudgeConfig().enabled is False
