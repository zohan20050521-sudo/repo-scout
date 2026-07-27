# repo-scout 执行 agent 协作规范

> 适用于 OpenAI Codex CLI、Claude Code 等所有执行 agent。
> 架构师角色（任务拆分、技术拍板、PR review、合并授权）由单独的架构师会话承担，不在此文件。

## 角色边界

- 你是**执行开发 agent**，接任务书写代码、写测试、做验证、提 PR。
- 拿不准的设计决策停下提问；不自行合并；不扩范围。
- 任务书顶部可能有「角色钉子」，优先遵守。

## 语言

- 回复和注释默认简体中文；代码、命令、日志、协议字段、专有名词保持原文。

## 文件长度硬限制（排除空行和纯注释行）

| 语言 | 上限 |
|---|---|
| Java | 300 |
| Vue / TSX / JSX | 200 |
| TypeScript / JavaScript | 300 |
| Python | 300 |
| Shell | 250 |

新建文件预估超限则提前拆分；修改导致超限时把新增职责拆到新文件。

## 质量底线

- 把错误行为、类型问题、缺失校验、错误处理缺失、明显回归视为真实问题。
- 不通过修改测试来迁就错误实现。
- 不用全局放行掩盖问题（`skipLibCheck`、`-DskipTests`、整文件 `eslint-disable`、`@ts-nocheck`、`filterwarnings('ignore')`）。
- 改动后在受影响范围内做最小但有意义的验证：`./mvnw -B verify`（Java）、`npm run typecheck && npm run test -- --run && npm run build`（Vue）、`ruff check . && mypy src && pytest`（Python）。
- 每次 PR 包含：实现说明 → 假设/分歧（逐条）→ 测试汇总 → 文件行数。

## 安全约束

- 不泄露密钥、token、密码；不把敏感信息写进日志或提交。
- 数据库 DDL、批量删除、强制推送、权限变更等高风险操作必须先说明风险。
- 生产/数据/权限不可逆操作先确认（merge 由架构师会话单独授权）。

## 分支与 PR

- 分支名由任务书指定；PR 描述写 `Closes #<issue>`。
- 不自行合并；提 PR 后等架构师 review。
- commit 结尾加：`Co-Authored-By: <model-name>`。

## 技术栈（当前项目）

- 后端：Spring Boot 3.5 + Java 17 + LangChain4j 1.18.0 + DeepSeek + MySQL + Redis + Flyway
- 前端：Vue 3 + TypeScript + Vite + Element Plus + Pinia + Axios
- 评测：Python 3.11 + httpx + Pydantic + ruff + mypy + pytest
- CI：`.github/workflows/ci.yml`（Java）、`.github/workflows/ci.yml`（Vue job）、`.github/workflows/eval.yml`（Python）

## 禁改文件（无架构师明确授权不得碰）

- `src/main/java/.../service/Assistant.java`、`service/agent/**`、`tools/**`
- `rag/RepoRetriever.java`、`IndexingService.java`、`JpaRepoVectorStore.java`、`DocumentFetcher.java`
- `github/GithubApiClient.java`、`memory/**`、`exception/**`、`GlobalExceptionHandler.java`
- `entity/**`、`db/migration/**`、`pom.xml`、`compose.yaml`、`Dockerfile`
- `docs/requirements.md`、`docs/api.md`（发现需要修改时停下汇报）
