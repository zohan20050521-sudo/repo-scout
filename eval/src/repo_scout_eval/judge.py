"""可选 LLM judge:默认关闭,不是通过门槛,也不覆盖确定性指标。

provider / URL / model / key 全从环境变量读取,走 OpenAI 兼容 chat completions 协议,
不绑定任何 SDK。输出必须是严格 JSON,校验失败只记 judge_error,不让整批失败。
成本、波动与自偏差见 eval/README.md——不得作为 CI gate。
"""

from __future__ import annotations

import json
import logging
import os
from typing import Any

import httpx
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from .config import JudgeConfig

log = logging.getLogger("repo_scout_eval.judge")

PROMPT_VERSION = "judge-v1"
SYSTEM_PROMPT = (
    "你是代码仓库问答的评审员。只依据给定问题与回答打分,不要臆测未提供的信息。"
    '严格输出 JSON,不要 Markdown 代码块,结构为:{"relevance":0-1 的小数,'
    '"groundedness":0-1 的小数,"completeness":0-1 的小数,"reason":"一句中文理由"}。'
)


class JudgeError(Exception):
    """judge 配置缺失或不可用(初始化期)。"""


class JudgeVerdict(BaseModel):
    """judge 的结构化输出,超出 [0,1] 直接判为非法。"""

    model_config = ConfigDict(extra="ignore")

    relevance: float = Field(ge=0.0, le=1.0)
    groundedness: float = Field(ge=0.0, le=1.0)
    completeness: float = Field(ge=0.0, le=1.0)
    reason: str = ""


class LlmJudge:
    """一个极薄的 OpenAI 兼容 judge 客户端。"""

    def __init__(self, base_url: str, api_key: str, model: str, timeout_s: float, client: Any = None) -> None:
        self.prompt_version = PROMPT_VERSION
        self._model = model
        self._client = client or httpx.Client(
            base_url=base_url.rstrip("/"),
            timeout=timeout_s,
            headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
        )

    @classmethod
    def from_env(cls, config: JudgeConfig, env: dict[str, str] | None = None) -> LlmJudge:
        source = os.environ if env is None else env
        base_url = (source.get(config.base_url_env) or "").strip()
        api_key = (source.get(config.api_key_env) or "").strip()
        model = (source.get(config.model_env) or "").strip()
        missing = [
            name
            for name, value in (
                (config.base_url_env, base_url),
                (config.api_key_env, api_key),
                (config.model_env, model),
            )
            if not value
        ]
        if missing:
            raise JudgeError(f"judge 已启用但缺少环境变量: {', '.join(missing)}")
        return cls(base_url, api_key, model, config.timeout_s)

    def close(self) -> None:
        self._client.close()

    def evaluate(self, question: str, answer: str) -> dict[str, Any]:
        """返回 judge 结果字典;任何失败都以 judge_error 形式记录,不抛出。"""
        payload = {
            "model": self._model,
            "temperature": 0,
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": f"问题:\n{question}\n\n回答:\n{answer}"},
            ],
        }
        try:
            response = self._client.post("/chat/completions", json=payload)
        except httpx.HTTPError as exc:
            return self._error(f"judge 调用网络错误: {type(exc).__name__}")
        if response.status_code != 200:
            return self._error(f"judge 调用返回 HTTP {response.status_code}")
        try:
            content = response.json()["choices"][0]["message"]["content"]
        except (ValueError, KeyError, IndexError, TypeError) as exc:
            return self._error(f"judge 响应结构异常: {type(exc).__name__}")
        return self._parse(content)

    def _parse(self, content: str) -> dict[str, Any]:
        text = content.strip().removeprefix("```json").removeprefix("```").removesuffix("```").strip()
        try:
            data = json.loads(text)
        except json.JSONDecodeError:
            return self._error("judge 输出不是合法 JSON")
        try:
            verdict = JudgeVerdict.model_validate(data)
        except ValidationError as exc:
            return self._error(f"judge 输出字段非法: {exc.error_count()} 处")
        result = verdict.model_dump()
        result["prompt_version"] = self.prompt_version
        result["judge_error"] = None
        return result

    def _error(self, message: str) -> dict[str, Any]:
        log.warning("%s", message)
        return {"prompt_version": self.prompt_version, "judge_error": message}
