"""配置模型与加载:YAML 提供非敏感参数,凭据只来自环境变量。

阈值、重试、并发等魔法数字集中在此,默认来源写在 README。
"""

from __future__ import annotations

import os
from pathlib import Path
from typing import Any

import yaml
from pydantic import BaseModel, ConfigDict, Field, SecretStr, model_validator

ENV_BASE_URL = "REPO_SCOUT_BASE_URL"
ENV_INTERNAL_KEY = "REPO_SCOUT_INTERNAL_KEY"
ENV_TIMEOUT = "REPO_SCOUT_REQUEST_TIMEOUT"

DEFAULT_BASE_URL = "http://localhost:8080"
DEFAULT_TIMEOUT_S = 120.0
DEFAULT_THRESHOLDS: tuple[float, ...] = (0.50, 0.55, 0.60, 0.65, 0.70, 0.75, 0.80, 0.85)
"""离线阈值重放的候选阈值;首值与后端默认 RAG_MIN_SCORE=0.5 对齐。"""

INTERNAL_KEY_HEADER = "X-Repo-Scout-Internal-Key"
MASK = "***"


class ConfigError(Exception):
    """配置文件缺失、格式错误或字段非法。"""


class RetryPolicy(BaseModel):
    """有限重试:仅对 429/502/网络错误生效,确定性 4xx 不重试。"""

    model_config = ConfigDict(extra="forbid")

    max_attempts: int = Field(default=3, ge=1, le=6)
    backoff_s: float = Field(default=2.0, ge=0.0, le=30.0)
    jitter_s: float = Field(default=0.5, ge=0.0, le=10.0)
    retry_statuses: list[int] = Field(default_factory=lambda: [429, 502, 503, 504])


class TargetConfig(BaseModel):
    """一个被测后端。base_url 可留空由环境变量提供。"""

    model_config = ConfigDict(extra="forbid")

    label: str = "default"
    base_url: str | None = None
    min_score_label: float | None = None
    """操作者声明的实验标签;REST API 无法验证,报告中标注未由服务端证明。"""

    def resolved_base_url(self) -> str:
        return (self.base_url or os.environ.get(ENV_BASE_URL) or DEFAULT_BASE_URL).rstrip("/")

    def host(self) -> str:
        """用于 manifest 的目标标识,去掉可能的 query/凭据部分。"""
        url = self.resolved_base_url()
        return url.split("?", 1)[0]


class PrepareConfig(BaseModel):
    """运行前置:接入/索引策略。重建默认关闭,避免每次运行重复花成本。"""

    model_config = ConfigDict(extra="forbid")

    auto_register: bool = True
    auto_index: bool = True
    allow_reindex: bool = False
    require_index: bool = True
    """要求目标仓库已索引;未索引且不允许索引时前置失败。"""


class JudgeConfig(BaseModel):
    """可选 LLM judge:默认关闭,不是通过门槛。"""

    model_config = ConfigDict(extra="forbid")

    enabled: bool = False
    base_url_env: str = "EVAL_JUDGE_BASE_URL"
    api_key_env: str = "EVAL_JUDGE_API_KEY"
    model_env: str = "EVAL_JUDGE_MODEL"
    timeout_s: float = Field(default=60.0, gt=0)
    max_cases: int = Field(default=50, ge=1)


class RunConfig(BaseModel):
    """一次 run 的完整参数。凭据不在此模型内,不会进 repr/日志/manifest。"""

    model_config = ConfigDict(extra="forbid")

    dataset: str = "datasets/v1.yaml"
    repo: str = "zohan20050521-sudo/repo-scout"
    output_dir: str = "results"
    targets: list[TargetConfig] = Field(default_factory=lambda: [TargetConfig()])
    prepare: PrepareConfig = Field(default_factory=PrepareConfig)
    retry: RetryPolicy = Field(default_factory=RetryPolicy)
    judge: JudgeConfig = Field(default_factory=JudgeConfig)
    concurrency: int = Field(default=1, ge=1, le=4)
    repetitions: int = Field(default=1, ge=1, le=10)
    only_cases: list[str] = Field(default_factory=list)
    only_categories: list[str] = Field(default_factory=list)
    fail_fast: bool = False
    seed: int | None = None
    thresholds: list[float] = Field(default_factory=lambda: list(DEFAULT_THRESHOLDS))
    request_pause_s: float = Field(default=1.0, ge=0.0, le=30.0)
    """相邻请求之间的固定间隔,降低触发 GitHub/模型限流的概率。"""

    @model_validator(mode="after")
    def _check(self) -> RunConfig:
        if not self.targets:
            raise ValueError("targets 不能为空")
        labels = [t.label for t in self.targets]
        if len(set(labels)) != len(labels):
            raise ValueError("target label 必须唯一")
        if not self.thresholds:
            raise ValueError("thresholds 不能为空")
        if any(not 0.0 <= t <= 1.0 for t in self.thresholds):
            raise ValueError("thresholds 必须在 [0, 1] 区间")
        self.thresholds = sorted(set(self.thresholds))
        return self


class Credentials(BaseModel):
    """从环境变量读取的凭据。SecretStr 保证 repr/日志/JSON 不泄漏原值。"""

    model_config = ConfigDict(extra="forbid")

    internal_key: SecretStr | None = None
    timeout_s: float = DEFAULT_TIMEOUT_S

    @classmethod
    def from_env(cls, env: dict[str, str] | None = None) -> Credentials:
        source = os.environ if env is None else env
        raw_key = (source.get(ENV_INTERNAL_KEY) or "").strip()
        raw_timeout = (source.get(ENV_TIMEOUT) or "").strip()
        timeout = DEFAULT_TIMEOUT_S
        if raw_timeout:
            try:
                timeout = float(raw_timeout)
            except ValueError as exc:
                raise ConfigError(f"{ENV_TIMEOUT} 必须是秒数,当前值不是合法数字") from exc
            if timeout <= 0:
                raise ConfigError(f"{ENV_TIMEOUT} 必须为正数")
        return cls(internal_key=SecretStr(raw_key) if raw_key else None, timeout_s=timeout)

    def headers(self) -> dict[str, str]:
        if self.internal_key is None:
            return {}
        return {INTERNAL_KEY_HEADER: self.internal_key.get_secret_value()}

    def key_state(self) -> str:
        """给日志用的脱敏状态,永不输出 key 本体。"""
        return "configured" if self.internal_key is not None else "absent"


def load_run_config(path: str | Path) -> RunConfig:
    """读取 YAML 运行配置。空文件视为全默认。"""
    config_path = Path(path)
    if not config_path.is_file():
        raise ConfigError(f"配置文件不存在: {config_path}")
    try:
        raw: Any = yaml.safe_load(config_path.read_text(encoding="utf-8"))
    except yaml.YAMLError as exc:
        raise ConfigError(f"配置文件 YAML 解析失败: {config_path}: {exc}") from exc
    if raw is None:
        raw = {}
    if not isinstance(raw, dict):
        raise ConfigError(f"配置文件根节点必须是映射: {config_path}")
    try:
        return RunConfig.model_validate(raw)
    except Exception as exc:  # pydantic ValidationError 及字段校验错误
        raise ConfigError(f"配置文件字段非法: {config_path}: {exc}") from exc


def resolve_relative(base: Path, value: str) -> Path:
    """相对路径按配置文件所在目录解析,绝对路径原样返回。"""
    candidate = Path(value)
    return candidate if candidate.is_absolute() else (base / candidate)
