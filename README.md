# repo-scout

> GitHub 仓库导读 Agent:输入一个仓库地址,即可用自然语言提问「怎么运行、架构如何、最近在修什么 bug」,由 Agent 结合工具调用与 RAG 给出答案。

> **仓库迁移说明**:本仓库于 2026-07 从原账号迁移至当前账号,完整提交历史(含各功能分支与 PR 合并记录)已保留;
> 原账号下的 Issue / PR 讨论内容未随迁移保留,开发流程留痕可从提交历史中的分支名与 PR 编号追溯。

## 核心功能

- **仓库接入**:输入 GitHub 仓库地址,自动拉取目录树、README、issues、commits 等信息
- **自然语言问答**:围绕仓库提问,支持多轮对话与会话记忆
- **Agent 自主规划**:根据问题自动决定调用哪些 GitHub 工具获取上下文
- **RAG 检索增强**:仓库文档向量化后检索,回答带出处、更少幻觉
- **仓库分析报告**:一键生成仓库导读报告(定位、架构、上手指引)

## 技术栈

| 组件 | 选型 |
| --- | --- |
| 后端框架 | Spring Boot 3(REST API) |
| Agent / LLM 编排 | LangChain4j |
| 对话模型 | DeepSeek API |
| 向量化(Embedding) | LangChain4j 进程内 ONNX 模型 `bge-small-zh` |
| 业务数据 | MySQL |
| 会话记忆 | Redis |
| 效果评估 | Python 批量评测脚本 |

一期仅提供 REST API,不做前端。

## 本地运行

### 环境要求

- JDK 17
- Maven 3.9+(或直接使用项目自带的 `./mvnw`)
- **MySQL:运行必需**——应用启动时 Flyway 会执行数据库迁移(建 `repo` 表),MySQL 不可用则启动失败。仓库接入 `/api/repos` 与会话绑定也依赖它
- **Redis:运行必需**——对话接口 `/api/chat` 的会话记忆与仓库绑定依赖 Redis,使用该接口前需启动
- **构建与测试不需要 MySQL/Redis/外网**:测试用 H2(MySQL 兼容模式)执行同一迁移脚本,GitHub/DeepSeek 全程 mock;`./mvnw -B verify` 无需任何外部依赖

### 环境变量

除 `DEEPSEEK_API_KEY` **必填**(缺失时应用启动即失败并给出提示)外,其余均有默认值:

| 变量 | 说明 | 默认值 |
| --- | --- | --- |
| `DEEPSEEK_API_KEY` | **必填**,DeepSeek API Key | 无 |
| `DEEPSEEK_BASE_URL` | DeepSeek OpenAI 兼容端点 | `https://api.deepseek.com/v1` |
| `DEEPSEEK_MODEL` | 对话模型名(可选 `deepseek-v4-pro`) | `deepseek-v4-flash` |
| `DEEPSEEK_TIMEOUT` | 模型调用超时 | `60s` |
| `CHAT_MESSAGE_MAX_LENGTH` | 单条用户消息最大字符数 | `4000` |
| `CHAT_MEMORY_MAX_MESSAGES` | 会话记忆窗口(消息条数) | `20` |
| `CHAT_MEMORY_TTL` | 会话记忆与仓库绑定过期时间 | `24h` |
| `AGENT_MAX_TOOL_ROUNDS` | 单次问答工具调用轮数上限(防循环) | `5` |
| `RAG_MAX_FILES` | 单次向量化索引最多拉取的文档数(README 计入) | `30` |
| `RAG_MAX_FILE_BYTES` | 单个文档字节数上限,超出跳过不索引 | `100000` |
| `RAG_CHUNK_SIZE` | 文档切分块大小(字符) | `400` |
| `RAG_CHUNK_OVERLAP` | 相邻块重叠字符数 | `80` |
| `RAG_TOP_K` | 每次检索返回的文档块条数(对话注入与报告摘录共用) | `4` |
| `RAG_MIN_SCORE` | 检索命中的余弦相似度过滤阈值(低于则不注入) | `0.5` |
| `GITHUB_TOKEN` | GitHub API 鉴权 token(留空为匿名,限流阈值低) | 空 |
| `GITHUB_BASE_URL` | GitHub API 端点 | `https://api.github.com` |
| `GITHUB_TIMEOUT` | GitHub API 连接与读超时 | `10s` |
| `MYSQL_HOST` | MySQL 主机 | `localhost` |
| `MYSQL_PORT` | MySQL 端口 | `3306` |
| `MYSQL_DB` | MySQL 数据库名 | `repo_scout` |
| `MYSQL_USER` | MySQL 用户名 | `root` |
| `MYSQL_PASSWORD` | MySQL 密码 | 空 |
| `REDIS_HOST` | Redis 主机 | `localhost` |
| `REDIS_PORT` | Redis 端口 | `6379` |
| `REDIS_PASSWORD` | Redis 密码 | 空 |

### 启动与验证

```bash
# 启动(默认端口 8080)
mvn spring-boot:run

# 健康检查
curl http://localhost:8080/api/health
# → {"status":"UP","application":"repo-scout"}

# 对话(需已设置 DEEPSEEK_API_KEY 并启动 Redis,详见 docs/api.md)
curl -s -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message": "用一句话介绍你自己"}'

# 构建并运行测试(无需 MySQL/Redis/外网)
mvn -B verify
```

### 接入仓库并绑定会话提问(v0.2)

绑定一个已接入的仓库后,Agent 会自动调用 GitHub 工具基于仓库真实数据作答:

```bash
# 1) 接入仓库,记下返回的 id(下称 <repoId>)
curl -s -X POST http://localhost:8080/api/repos \
  -H 'Content-Type: application/json' \
  -d '{"repo": "spring-projects/spring-petclinic"}'
# → {"id":1,"owner":"spring-projects","name":"spring-petclinic",...}

# 2) 首次携带 repoId 绑定会话并提问(自动生成 sessionId,记下返回值)
curl -s -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message": "这个项目怎么在本地跑起来?", "repoId": 1}'
# → {"sessionId":"<uuid>","answer":"基于 README 的运行步骤……"}

# 3) 同一会话后续可省略 repoId,沿用绑定;追问可用指代
curl -s -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"sessionId": "<uuid>", "message": "项目的目录结构大致是怎样的?"}'
```

绑定语义(首绑校验存在性、冲突 400、过期需重绑)详见 [docs/api.md](docs/api.md)。

### 触发文档向量化索引(v0.3)

接入仓库后,可对其 README 与 `docs/` 文档做一次向量化入库(FR-3.1),供后续 RAG 检索使用。
首次触发会加载进程内 `bge-small-zh` 量化模型(约 24MB,无外网);索引同步执行,幂等重建。

```bash
# 对已接入仓库(<repoId> 为 POST /api/repos 返回的 id)触发向量化索引
curl -s -X POST http://localhost:8080/api/repos/1/index
# → {"repoId":1,"fileCount":8,"chunkCount":63,"costMs":2450}

# 重复调用为幂等重建:doc_chunk 总数不因重复触发而增长
```

拉取范围(README + `docs/` + 扩展名白名单)与上限、切分粒度见上方 `RAG_*` 环境变量;
接口契约与错误表详见 [docs/api.md](docs/api.md)。

### RAG 问答与仓库导读报告(v0.3)

**建议先对仓库执行上面的 `POST /api/repos/{id}/index` 再问答/生成报告,以获得最佳效果。**

绑定已索引仓库的会话中,服务端会按问题自动检索文档块注入上下文,回答附引用来源
(`sources` 为本轮注入的来源文件路径;未索引/无命中时自动退化为纯工具问答,`sources` 为 `[]`):

```bash
# RAG 问答:回答基于文档摘录并注明出处
curl -s -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message": "这个项目的统一错误码有哪些?", "repoId": 1}'
# → {"sessionId":"<uuid>","answer":"根据 docs/api.md,统一错误码包括……","sources":["docs/api.md"]}

# 一键生成仓库导读报告(五个固定小节:项目定位/技术栈/目录结构解读/上手指引/近期动向)
curl -s -X POST http://localhost:8080/api/repos/1/report
# → {"repoId":1,"generatedAt":"2026-07-26T12:00:00","costMs":12450,"report":"## 项目定位\n……"}
```

检索条数与相似度阈值由 `RAG_TOP_K` / `RAG_MIN_SCORE` 控制(见上方环境变量表)。

## Docker 一键启动

在干净环境用 Docker 一键拉起 **应用 + MySQL + Redis**,无需本地安装 JDK / Maven。

### 前置

- Docker Engine + Docker Compose V2(`docker compose version` 可用)
- 一个可用的 `DEEPSEEK_API_KEY`

### 启动

```bash
# 注入 Key 并后台构建启动三件套(app / mysql / redis)
DEEPSEEK_API_KEY=sk-xxxx docker compose up -d --build
```

- 未设置 `DEEPSEEK_API_KEY` 时,compose 直接报错退出(fail-fast),不会启动到一半。
- MySQL、Redis 仅在 compose 内部网络互通,**不映射宿主机端口**,不会与本机既有的 3306 / 6379 冲突。

### 验证

```bash
# 健康检查
curl http://localhost:8080/api/health
# → {"status":"UP","application":"repo-scout"}

# 对话(证明应用已连通容器内 Redis 会话记忆)
curl -s -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message": "用一句话介绍你自己"}'
```

### 端口覆盖

宿主 8080 被占用时,用 `APP_PORT` 换一个宿主端口(容器内仍是 8080):

```bash
APP_PORT=18080 DEEPSEEK_API_KEY=sk-xxxx docker compose up -d --build
curl http://localhost:18080/api/health
```

### 停止与清理

> compose 对所有子命令统一做 `DEEPSEEK_API_KEY` 校验,故 `down` / `ps` 等也需带上该变量(停机场景下值可随意)。

```bash
DEEPSEEK_API_KEY=x docker compose down      # 停止并删除容器,保留 MySQL 数据卷
DEEPSEEK_API_KEY=x docker compose down -v   # 额外删除数据卷:MySQL 业务数据会被清空
```

## 项目状态

🚧 **开发中(v0.1)**

| 版本 | 内容 | 状态 |
| --- | --- | --- |
| v0.1 | 项目骨架、DeepSeek 接入、基础对话 + 会话记忆 | 进行中 |
| v0.2 | GitHub 工具集(目录树/README/issues/commits),Agent 自主规划调用 | 规划中 |
| v0.3 | 文档向量化 + RAG 问答、仓库分析报告 | 规划中 |
| v0.4 | 效果评估、Docker 部署、文档收尾 | 规划中 |

详细需求见 [docs/requirements.md](docs/requirements.md),API 契约见 [docs/api.md](docs/api.md)。

## License

[MIT](LICENSE)
