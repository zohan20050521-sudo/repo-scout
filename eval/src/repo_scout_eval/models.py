"""领域模型:数据集 case、API 响应、逐题结果与运行清单。

只做数据建模与字段级校验,不含 HTTP、文件 I/O 或指标计算。
"""

from __future__ import annotations

from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

SCHEMA_VERSION = "1"
"""结果产物 schema 版本;字段不兼容变更时递增。"""

Category = Literal[
    "rag_fact",
    "rag_multi_source",
    "tool_live",
    "no_evidence",
    "conversation",
    "pollution_pair",
    "report_structure",
]

CATEGORIES: tuple[Category, ...] = (
    "rag_fact",
    "rag_multi_source",
    "tool_live",
    "no_evidence",
    "conversation",
    "pollution_pair",
    "report_structure",
)

Variant = Literal["single", "fresh", "polluted", "priming", "turn"]

NonEmptyStr = Annotated[str, Field(min_length=1)]


class Expectation(BaseModel):
    """一题(或一轮)的确定性预期。全部字段可选,缺省即不参与对应指标。"""

    model_config = ConfigDict(extra="forbid")

    source_paths: list[NonEmptyStr] = Field(default_factory=list)
    """期望被 citations 命中的仓库文件路径,用于 recall / MRR。"""

    allowed_source_paths: list[NonEmptyStr] = Field(default_factory=list)
    """precision proxy 的分子白名单;缺省时退化为 source_paths。"""

    answer_keywords: list[NonEmptyStr] = Field(default_factory=list)
    """答案中期望出现的关键词(大小写不敏感子串匹配)。"""

    forbidden_claims: list[NonEmptyStr] = Field(default_factory=list)
    """答案中不应出现的断言片段。"""

    expect_no_citations: bool = False
    """no_evidence 类:期望不返回任何 citation。"""

    min_answer_chars: int = Field(default=0, ge=0)
    """低于该长度视为过短答案;0 表示不检查。"""

    require_markdown_sections: list[NonEmptyStr] = Field(default_factory=list)
    """答案/报告中必须出现的 Markdown 小节标题。"""

    def precision_whitelist(self) -> list[str]:
        return list(self.allowed_source_paths) if self.allowed_source_paths else list(self.source_paths)

    def is_empty(self) -> bool:
        return not (
            self.source_paths
            or self.answer_keywords
            or self.forbidden_claims
            or self.require_markdown_sections
            or self.expect_no_citations
            or self.min_answer_chars
        )


class Turn(BaseModel):
    """conversation 类的一轮提问。"""

    model_config = ConfigDict(extra="forbid")

    question: NonEmptyStr
    expected: Expectation = Field(default_factory=Expectation)


class EvalCase(BaseModel):
    """数据集中的一个 case。答案判定逻辑全部来自本模型,runner 不硬编码。"""

    model_config = ConfigDict(extra="forbid")

    id: NonEmptyStr
    category: Category
    question: str | None = None
    turns: list[Turn] = Field(default_factory=list)
    priming_questions: list[NonEmptyStr] = Field(default_factory=list)
    expected: Expectation = Field(default_factory=Expectation)
    notes: str | None = None
    tags: list[NonEmptyStr] = Field(default_factory=list)

    @model_validator(mode="after")
    def _check_shape(self) -> EvalCase:
        if self.category == "conversation":
            if self.question is not None:
                raise ValueError("conversation case 用 turns 表达多轮,不应带 question")
            if len(self.turns) < 2:
                raise ValueError("conversation case 至少需要 2 轮 turns")
            if all(turn.expected.is_empty() for turn in self.turns):
                raise ValueError("conversation case 至少一轮需要非空 expected")
            return self
        if self.turns:
            raise ValueError(f"category={self.category} 不支持 turns")
        if not self.question:
            raise ValueError(f"category={self.category} 必须提供非空 question")
        if self.category == "pollution_pair":
            if not 2 <= len(self.priming_questions) <= 4:
                raise ValueError("pollution_pair 需要 2–4 个 priming_questions")
        elif self.priming_questions:
            raise ValueError("priming_questions 仅用于 pollution_pair")
        if self.category == "no_evidence" and not self.expected.forbidden_claims:
            raise ValueError("no_evidence case 需要至少一条 forbidden_claims")
        if self.category == "report_structure" and not self.expected.require_markdown_sections:
            raise ValueError("report_structure case 需要 require_markdown_sections")
        if self.expected.is_empty():
            raise ValueError("expected 不能为空,否则该 case 无法判定")
        return self


class Dataset(BaseModel):
    """版本化数据集。"""

    model_config = ConfigDict(extra="forbid")

    version: NonEmptyStr
    repo: NonEmptyStr
    description: str | None = None
    cases: list[EvalCase]

    @model_validator(mode="after")
    def _unique_ids(self) -> Dataset:
        if not self.cases:
            raise ValueError("数据集不能为空")
        seen: set[str] = set()
        dupes: set[str] = set()
        for case in self.cases:
            if case.id in seen:
                dupes.add(case.id)
            seen.add(case.id)
        if dupes:
            raise ValueError(f"case id 重复: {', '.join(sorted(dupes))}")
        return self


class Citation(BaseModel):
    """`POST /api/chat` 响应中的结构化引用(契约见 docs/api.md)。"""

    model_config = ConfigDict(extra="allow")

    filePath: str
    chunkIndex: int = 0
    excerpt: str = ""
    score: float = 0.0
    url: str | None = None


class ChatResponse(BaseModel):
    model_config = ConfigDict(extra="allow")

    sessionId: str = ""
    answer: str = ""
    sources: list[str] = Field(default_factory=list)
    citations: list[Citation] = Field(default_factory=list)


class ReportResponse(BaseModel):
    model_config = ConfigDict(extra="allow")

    repoId: int = 0
    generatedAt: str | None = None
    costMs: int = 0
    report: str = ""


class RepoResponse(BaseModel):
    model_config = ConfigDict(extra="allow")

    id: int
    owner: str = ""
    name: str = ""
    defaultBranch: str = ""


class IndexStatusResponse(BaseModel):
    model_config = ConfigDict(extra="allow")

    repoId: int = 0
    indexed: bool = False
    fileCount: int = 0
    chunkCount: int = 0
    indexedAt: str | None = None


class CaseRecord(BaseModel):
    """逐题结果记录,写入 cases.jsonl 的一行。"""

    model_config = ConfigDict(extra="forbid")

    schema_version: str = SCHEMA_VERSION
    case_id: str
    category: Category
    variant: Variant = "single"
    repetition: int = 1
    turn_index: int | None = None
    target_label: str = "default"
    question: str = ""
    started_at: str = ""
    latency_ms: int = 0
    http_status: int | None = None
    error_code: str | None = None
    error_message: str | None = None
    retry_count: int = 0
    session_ref: str | None = None
    answer: str = ""
    sources: list[str] = Field(default_factory=list)
    citations: list[Citation] = Field(default_factory=list)
    metrics: dict[str, float] = Field(default_factory=dict)
    judge: dict[str, object] | None = None

    @property
    def ok(self) -> bool:
        return self.http_status == 200 and self.error_code is None

    @property
    def scored(self) -> bool:
        """是否计入普通题指标:成功且非 polluted/priming 变体。"""
        return self.ok and self.variant in ("single", "fresh", "turn")


class RunManifest(BaseModel):
    """一次运行的可复现清单,不含任何凭据。"""

    model_config = ConfigDict(extra="forbid")

    schema_version: str = SCHEMA_VERSION
    tool_version: str
    run_id: str
    started_at: str
    finished_at: str | None = None
    python_version: str
    dataset_path: str
    dataset_version: str
    dataset_sha256: str
    dataset_case_count: int
    target_label: str
    target_host: str
    min_score_label: float | None = None
    min_score_label_verified: bool = False
    repo: str
    repo_id: int | None = None
    repo_default_branch: str | None = None
    repo_head_commit: str = "unknown"
    indexed: bool | None = None
    index_file_count: int | None = None
    index_chunk_count: int | None = None
    judge_enabled: bool = False
    judge_prompt_version: str | None = None
    run_params: dict[str, object] = Field(default_factory=dict)
    case_count: int = 0
    failed_case_count: int = 0
