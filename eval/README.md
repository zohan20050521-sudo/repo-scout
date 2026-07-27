# repo-scout 黑盒评测工具

以**外部观察者**身份评测 repo-scout:只调用公开 REST API,量化 RAG 检索命中与 citation 质量、
回答任务完成度、多轮历史摘录累积的影响、`minScore` 阈值曲线,以及延迟与错误率。

不读取 MySQL、Redis、服务端日志或 Java 类,也**不解析 Agent 工具调用轨迹**(当前无该 API)。

## 安装

需要 Python 3.11+:

```bash
cd eval
python -m venv .venv && source .venv/bin/activate
python -m pip install -e '.[dev]'
```

运行依赖 `httpx` / `pydantic` / `PyYAML` / `rich`;测试与质量工具 `pytest` / `respx` / `ruff` / `mypy`。
不依赖 Jupyter,不引入 LangChain、pandas、数据库客户端或重量级评测框架。

## 四个命令

```bash
repo-scout-eval validate --dataset datasets/v1.yaml     # 离线校验数据集,不访问网络
repo-scout-eval run --config configs/local.yaml         # 执行数据集,生成全部产物
repo-scout-eval summarize results/<run-id>              # 从已有 JSONL 重建汇总(脱网)
repo-scout-eval compare results/<run-a> results/<run-b> # 对比两个 run 的主要指标
```

等价入口:`python -m repo_scout_eval <subcommand> ...`。
配置里的相对路径按**当前工作目录**解析,即上面的 `cd eval`。

`run` 常用开关:`--overwrite`(允许覆盖同名结果目录)、`--only-case` / `--only-category`(可重复)、
`--repetitions`、`--concurrency`、`--seed`、`--fail-fast`、`--dataset`。

### 退出码

| 码 | 含义 |
| --- | --- |
| 0 | 全部成功 |
| 2 | 配置或数据集非法、文件缺失 |
| 3 | 前置健康检查 / 仓库准备失败(含 401 fail-fast) |
| 4 | 流程跑完,但存在失败 case |
| 5 | 程序内部错误 |
| 130 | 用户中断(已完成 case 保留在 `cases.jsonl`) |

## 凭据与安全

配置文件里**不写任何密钥**,凭据只来自环境变量:

| 变量 | 说明 | 默认 |
| --- | --- | --- |
| `REPO_SCOUT_BASE_URL` | 后端地址 | `http://localhost:8080` |
| `REPO_SCOUT_INTERNAL_KEY` | 内部门禁共享密钥,非空时注入 `X-Repo-Scout-Internal-Key` | 空 |
| `REPO_SCOUT_REQUEST_TIMEOUT` | 单请求超时(秒) | 120 |

- key 用 `SecretStr` 承载,不出现在 repr、日志、`manifest.json` 或任何结果文件里;日志只记 `configured` / `absent`。
- 直连开启门禁的后端需配置该 key;经公开同源代理访问时可不配置。
- 后端返回 401 时**立即失败**并提示「检查评测客户端与服务端的内部门禁配置」,不打印 key、不重试。

## 黑盒观测边界

只消费这些端点:`GET /api/health`、`POST /api/repos`、`GET /api/repos/{id}/index-status`、
`POST /api/repos/{id}/index`、`POST /api/chat`,以及可选的 `POST /api/repos/{id}/report`
(只用于结构/耗时 case,不进主 RAG 指标)。

因此有三条硬边界,报告中同样标注:

1. **看不到被阈值过滤掉的候选**。`citations` 只含已通过服务端 `RAG_MIN_SCORE` 的块 → 阈值曲线是左截断的。
2. **看不到工具调用轨迹**。`tool_live` 类只评价答案任务完成度,不声称验证了某次具体工具调用。
3. **看不到 Redis 记忆与 prompt token**。Issue #3 实验测的是**输出行为差异**,不是 memory 内容本身。

工具不修改后端参数、不清缓存、不删数据、不启停服务,也不在 CI 里访问真实 GitHub / DeepSeek / repo-scout。

## 数据集

版本化 YAML(`datasets/v1.yaml`),判定逻辑全部在数据里,runner 不硬编码答案。

```yaml
version: "1"
repo: zohan20050521-sudo/repo-scout
cases:
  - id: rag-fact-error-codes      # 全局唯一
    category: rag_fact
    question: 这个项目的统一错误码有哪些?
    expected:
      source_paths: [docs/api.md] # 期望被 citations 命中的路径
      answer_keywords: [INVALID_PARAM, REPO_NOT_FOUND]
      min_answer_chars: 40
    notes: 标注依据,写清答案在源文件的哪一节
```

`expected` 全部字段:

| 字段 | 作用 |
| --- | --- |
| `source_paths` | 期望命中的路径,驱动 `citation_hit` / `source_recall` / `mrr`;为空则该题不参与这些均值 |
| `allowed_source_paths` | precision proxy 的白名单;缺省时退化为 `source_paths` |
| `answer_keywords` | 期望出现的关键词(大小写不敏感子串) |
| `forbidden_claims` | 不应出现的断言片段 |
| `expect_no_citations` | no-evidence 题:期望不返回任何 citation |
| `min_answer_chars` | 低于该长度算过短答案(缺省兜底 20) |
| `require_markdown_sections` | 必须出现的 Markdown 小节标题 |

### 七个类别

| category | 含义 | v1 数量 | 建议下限 |
| --- | --- | --- | --- |
| `rag_fact` | 答案明确写在 README/docs,要求 citation 命中预期路径 | 10 | 8 |
| `rag_multi_source` | 答案需综合两个及以上文档来源 | 4 | 3 |
| `tool_live` | issues / commits / 目录结构等实时数据,只评答案完成度 | 5 | 4 |
| `no_evidence` | 仓库无依据或明显仓库外问题,查高分误召回与是否乱编 | 6 | 5 |
| `conversation` | 同一 session 的指代追问(`turns`,串行执行) | 3 组 | 2 |
| `pollution_pair` | Issue #3 配对实验(`priming_questions` 2–4 个) | 4 组 | 3 |
| `report_structure` | 报告五节齐全与耗时,不做主质量分 | 1 | — |

`validate` 会打印各类别数量与下限对照。动态信息(open issues、latest commits)**不写死**完整答案,
只用形状约束(如答案含 issue 编号)与 `forbidden_claims`;评测时间、目标 repo 与索引状态记在 manifest。

`source_paths` 只允许 `README.md` 与 `docs/**` —— 服务端索引范围就这些(见 `docs/design.md` 3.5),
测试 `test_repo_dataset_source_paths_limited_to_indexed_scope` 守住这条。

### 新增 case

在 `datasets/v1.yaml` 追加一条,给唯一 `id`、选 category、按上表填 `expected`,在 `notes` 写清标注依据,
然后 `repo-scout-eval validate` 检查。改动数据集会改变 `dataset_sha256`,`compare` 会因此给出警告——这是有意的。

## 指标定义

**全部指标都是基于人工标注的 deterministic proxy,不是事实正确率或语义相关性真值。**
命名与输出中都如实标注(`_proxy` 后缀、`proxy_note` 字段)。

### 逐题基础记录(`cases.jsonl` 每行)

case id / category / variant / repetition、HTTP 状态与统一错误码、`latency_ms`、`retry_count`、
`session_ref`(sessionId 的 SHA-256 前 12 位,不保存可复用会话状态)、`answer`、`sources`、`citations`
(含 score,不含 embedding——契约本来也没有)、`metrics`、可选 `judge`。运行配置与目标标识在 `manifest.json`。

### 检索指标

设 E = 该题标注的 `source_paths`(归一化:去 `./`、`/` 前缀,小写),C = 本轮返回的 citations 路径序列。

| 指标 | 公式 | 说明 |
| --- | --- | --- |
| `citation_hit` | `1 if E ∩ C ≠ ∅ else 0` | 至少命中一个预期来源 |
| `source_recall` | `|E ∩ C| / |E|` | 预期路径命中比例 |
| `citation_precision_proxy` | `|{c ∈ C : c ∈ W}| / |C|` | W = `allowed_source_paths` 或 `source_paths`。**proxy**:白名单外的 citation 也可能相关,只是标注未覆盖 |
| `mrr` | `1 / rank(首个 E 命中)` | rank 按 citations 顺序(即检索得分降序),未命中为 0 |
| `top_score` | `max(score)` | 本轮最高相似度 |
| `expected_source_max_score` | 命中 E 的 citation 中最高 score | 未命中为 0 |
| `citation_count` / `unique_source_count` | 条数 / 去重文件数 | |
| `empty_citations` | `1 if C = ∅` | 汇总为 `empty_citation_rate` |
| `false_positive_retrieval` | 仅 `expect_no_citations` 题:`1 if C ≠ ∅` | 汇总为 no-evidence 误召回率,并给出 topScore 分布 |

`source_paths` 为空的题不进 hit / recall / MRR 均值(靠记录里的 `has_retrieval_expectation` 门控位),
所以 `summarize` 无需重读数据集也能复算。

### 回答指标

| 指标 | 公式 |
| --- | --- |
| `keyword_coverage` | 命中关键词数 / 关键词总数(大小写不敏感子串) |
| `keyword_all_hit` | 全部关键词命中记 1 |
| `forbidden_claim_hit` | 命中任一 `forbidden_claims` 记 1(越低越好) |
| `empty_answer` / `short_answer` | 空白答案 / 长度低于 `min_answer_chars`(缺省 20) |
| `section_coverage` | 命中的 `require_markdown_sections` 占比 |
| `answer_chars` | 答案字符数 |
| 成功率 / P50 / P95 | 成功 = HTTP 200 且无错误码;分位数用最近秩法,不引入 numpy |

多轮追问的指代目标关键词覆盖 = 第二轮及以后 turn 的 `keyword_coverage`,按 `turn_index` 分行记录。

### composite

`summary.json` 有一个**透明**加权分,同时把全部分项摆出来:

```text
composite = 0.3×citation_hit + 0.4×keyword_coverage + 0.2×(1 - no_evidence_fp_rate) + 0.1×success_rate
```

权重是人为设定的,判断效果请看原始分项;不存在只给一个来源不明总分的输出。

## Issue #3:历史摘录累积的配对实验

不修改服务端 memory,只做**配对观测**。每组 `pollution_pair` 跑两条路径:

- `fresh`:新 session 直接问目标问题 B;
- `polluted`:另一个新 session 先依次问 2–4 个与 B 无关、但会召回其他文档的问题 A1…An,再问**逐字相同**的 B。

约束:同一 `repoId`、B 文本完全一致、两条路径各自独立 session、相邻执行(顺序 fresh → priming×N → polluted)、
支持 `--repetitions` 重复并保留每次原始结果。priming 轮以 `variant=priming` 单独记录,**不进普通题平均**。

比较 `delta = polluted - fresh`:`keyword_coverage`、`forbidden_claim_hit`、`citation_hit`、
`source_recall`、`citation_precision_proxy`、`mrr`、`top_score`、`citation_count`、`answer_chars`、latency。
可选 judge 的 groundedness 差值在启用 judge 时一并记录。

产物为独立的 `pollution_pairs.csv` 与 `summary.md` 的 `pollution_comparison` 表,不混入普通题平均值。
`summarize_pairs` 只统计双侧成功、B 一致的配对,并把 session 不独立 / B 不一致的配对列进 `integrity_violations`。

**边界**:`citations` 只反映 B 本轮检索,无法显示 Redis 中的旧摘录 → 这测的是**输出行为影响**,
不能声称直接测到了 prompt token 或 memory 内容。差值无显著性检验;若无显著差异也如实输出,
不预设 Issue #3 一定造成可见退化。本工具不修 Issue #3。

## Issue #4:minScore 阈值曲线

### A. 离线阈值重放(默认执行)

从一次 live run 的 citations 原始 score 重放候选阈值(默认 `0.50 … 0.85`,步长 0.05)。
`retained(threshold)` = `score >= threshold` 的 citations(**等于阈值视为保留**)。每个阈值输出:

| 列 | 含义 |
| --- | --- |
| `evidence_retained_hit` | 标注了预期路径的题中,retained 里仍命中预期来源的比例 |
| `expected_source_recall_proxy` | 同上题集的 `source_recall` 均值(基于 retained) |
| `no_evidence_false_positive_rate` | no-evidence 题中 retained 非空的比例 |
| `avg_retained_citations` | 平均保留引用数 |
| `empty_retrieval_rate` | retained 为空的题占比 |
| `balanced_f1` | `2PR/(P+R)`,`P = 1 - no_evidence_fp_rate`,`R = evidence_retained_hit` |

**左截断限制(报告中显著标注)**:后端只返回已通过运行时 `RAG_MIN_SCORE` 的候选,更低分候选对评测不可见。
所以曲线只能回答「在已返回 citations 上继续提高阈值会怎样」,**不能**推断低于服务端当前阈值的完整曲线。
低于本次 run 观测最低 score 的阈值点会额外标注「等价于不额外过滤」。

要得到更完整的曲线,操作者可另起一个评测专用后端实例、把 `RAG_MIN_SCORE` 调低(如 0.0)——
那属于评测环境配置,**runner 不会自行改 Java、数据库或环境变量**。

### B. 多目标 live 对比

`configs/multi-target.example.yaml` 可定义多个 target,各带 `min_score_label`,对同一数据集分别跑并横向比较。
`min_score_label` 是**操作者声明的实验标签,REST API 无法验证**,报告与 `compare` 警告都注明「未由服务端证明」。

阈值数据同时输出 `threshold_curve.csv`(tidy CSV,便于自行绘图)、`summary.json` 的 `threshold_curve`
与 `summary.md` 表格。不引入绘图库。

**本工具只给数据和建议区间,不修改默认 `RAG_MIN_SCORE=0.5`,不关闭 Issue #4。**

## 可选 LLM Judge

默认关闭,**不是通过门槛,也不能作为 CI gate**。启用需在 `configs/*.yaml` 设 `judge.enabled: true`,
并提供 `EVAL_JUDGE_BASE_URL` / `EVAL_JUDGE_API_KEY` / `EVAL_JUDGE_MODEL`(OpenAI 兼容协议,不绑定 SDK)。

judge 只补充「回答相关性 / 有据性 / 完整性」,**不覆盖确定性事实指标**。输出必须是严格 JSON 并逐字段校验,
失败只在该题记 `judge_error`,不让整批失败。prompt 固定版本(`judge-v1`)并写进 manifest。
注意:LLM judge 有 token 成本、跨次波动和自偏差(同族模型倾向给自己高分),结论不应据此下定论。
测试完全 mock,CI 不调用任何模型。

## 结果产物

每次运行写入独立目录 `results/<YYYYMMDDTHHMMSSZ>-<target-label>/`:

```text
manifest.json        运行清单:工具版本、数据集 hash、target label/host、时间窗口、Python 版本、
                     运行参数、repoId/默认分支/索引状态(head commit 无公开 API 可靠获取则记 unknown)
cases.jsonl          逐题完整原始记录(答案与 citation 全文),执行中逐条追加
summary.json         全部汇总指标 + threshold_curve + pollution
summary.md           人读摘要(长文本截断,按 case id 索引回 JSONL)
metrics.csv          逐题 tidy 指标(不含长文本)
threshold_curve.csv  阈值重放曲线
pollution_pairs.csv  fresh vs polluted 配对与差值
```

- `results/**` 默认 gitignore;仓库只提交一个小型脱敏示例 `examples/sample-results/`。
- 所有 JSON/CSV 带 `schema_version`(当前 `1`),字段稳定;写文件用 UTF-8 + 同目录临时文件 + `os.replace` 原子替换。
- `cases.jsonl` 边跑边追加,**中断或中途异常时已完成的 case 仍保留**。
- 不覆盖已有非空结果目录,除非显式 `--overwrite`。
- `--seed` 固定 case 执行顺序;**不承诺模型输出完全确定**。

## CI 与 live run 的差异

| | CI | 本地 live run |
| --- | --- | --- |
| 网络 | 无 | 需要 GitHub + DeepSeek |
| MySQL / Redis | 不需要 | 需要(后端依赖) |
| 真实 key | 无 | 需要 `DEEPSEEK_API_KEY` |
| 跑什么 | `ruff` / `mypy` / `pytest` / `validate` / 对 sample 做 `summarize` | 整套数据集 + pollution 配对 + 阈值重放 |

CI 只跑确定性、可离线复现的部分。**live 指标波动大,绝不设为 CI 硬阈值。**

```bash
cd eval
python -m pip install -e '.[dev]'
ruff check .
ruff format --check .
mypy src
pytest
repo-scout-eval validate --dataset datasets/v1.yaml
repo-scout-eval summarize examples/sample-results/20260727T000000Z-sample
```

## 本地 live run

需要一个已运行的 repo-scout(测试/本地环境,不碰生产):

```bash
cd eval
cp configs/local.example.yaml configs/local.yaml     # 按需改 base_url / min_score_label
export REPO_SCOUT_BASE_URL=http://localhost:8080
# 若后端开了门禁:export REPO_SCOUT_INTERNAL_KEY=...   # 只用环境变量,不要写进命令行字面量或 YAML
repo-scout-eval run --config configs/local.yaml
```

默认并发 1、相邻请求间隔 1s、只对未索引仓库触发一次索引(`allow_reindex: false`),
对 429/502/503/504 与网络错误做有限退避重试(默认最多 3 次尝试),`INVALID_PARAM` 等确定性 4xx 不重试,
重试次数写进逐题结果。这样不会无限打 GitHub / DeepSeek。

一次完整 run 约 40 次 chat 调用(33 case,其中 3 组多轮 + 4 组配对含 priming 轮)。

## 代码结构

```text
src/repo_scout_eval/
  cli.py          参数解析、日志、退出码映射
  commands.py     四个子命令的实现与 Rich 输出
  config.py       配置模型、环境变量凭据(SecretStr)、阈值等默认常量
  models.py       数据集 case、API 响应、CaseRecord、RunManifest
  datasets.py     数据集加载/校验/过滤/哈希(纯文件 I/O)
  client.py       唯一 HTTP 出口:重试、401 fail-fast、错误解码
  prepare.py      health → 接入 → index-status → 按需索引
  execution.py    按 category 分派的调用序列
  scoring.py      API 结果 × 预期 → CaseRecord(门控位、session 散列)
  runner.py       一次 run 的编排与产物落盘
  judge.py        可选 LLM judge
  metrics/        retrieval / answer / threshold / pollution / summary —— 全部纯函数
  reports/        writers(读写、原子替换)/ markdown(渲染)
```

HTTP、文件 I/O 与纯指标函数分离:`metrics/**` 不 import httpx 或 pathlib,所以指标可脱网复算——
`summarize` 就是这条路径的证明。Python 单文件 ≤300 非空非注释行,公共函数有完整类型注解,
`mypy --strict` 通过,不用裸 `except`,日志带 case id / target 且不输出 header 或 key。
