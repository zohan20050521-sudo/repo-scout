# repo-scout API 文档

- 版本:v0.3
- Base URL:`http://localhost:8080`
- 认证:一期不做鉴权,仅限本地/内网环境使用

## 设计约定

- 成功响应**直接返回资源 JSON**,不使用 `{code, data, msg}` 包装;语义由 HTTP 状态码表达。
- 错误响应统一结构:

```json
{
  "code": "INVALID_PARAM",
  "message": "对用户可读、可行动的错误说明"
}
```

### 错误码表

| code | HTTP 状态码 | 含义 |
| --- | --- | --- |
| `INVALID_PARAM` | 400 | 请求参数不合法(缺字段、超长、格式错误、JSON 不合法) |
| `LLM_UNAVAILABLE` | 502 | 上游模型(DeepSeek)不可用:超时、限流、鉴权失败等 |
| `INTERNAL_ERROR` | 500 | 服务内部错误 |
| `REPO_NOT_FOUND` | 404 | 仓库不存在:GitHub 查无此公开仓库(私有仓库同样 404),或按 id 查询的记录未接入 |
| `GITHUB_UNAVAILABLE` | 502 | GitHub API 不可用:网络错误、超时、GitHub 5xx 或限流(限流时 message 明确提示) |

错误 `message` 不包含堆栈、密钥或内部实现细节。

---

## GET /api/health

健康检查。只报告服务在线,不探测 MySQL/Redis。

### 响应 `200 OK`

```json
{
  "status": "UP",
  "application": "repo-scout"
}
```

### 示例

```bash
curl http://localhost:8080/api/health
```

---

## POST /api/chat

发送一条用户消息,返回模型回答。同一 `sessionId` 下多轮调用共享会话记忆
(存 Redis,窗口截断 + 过期时间,见 README 配置项)。

v0.2 起支持**绑定仓库**(FR-2.3):会话携带已接入仓库的 `repoId` 后,Agent 可自主调用
GitHub 工具(目录树、README、issues、最近提交)取仓库实时数据作答;未绑定的会话保持
v0.1 纯对话行为(不挂工具)。

v0.3 起绑定仓库的会话支持 **RAG 检索注入**(FR-3.2):若该仓库已建文档索引
(见 `POST /api/repos/{id}/index`),服务端按用户问题自动检索相关文档块注入上下文,
回答附引用来源(响应新增 `sources` 字段);未建索引时自动退化为纯工具模式,不报错。
**建议先 `POST /api/repos/{id}/index` 再问答,以获得带出处的最佳效果。**

### 请求体

| 字段 | 类型 | 必填 | 约束 |
| --- | --- | --- | --- |
| `sessionId` | string | 否 | 标准 UUID 格式(8-4-4-4-12)。为空/缺省时服务端生成新会话 |
| `message` | string | 是 | 非空,长度 ≤ 4000 字符(可通过 `CHAT_MESSAGE_MAX_LENGTH` 调整) |
| `repoId` | number | 否 | 已接入仓库的 id(见 `POST /api/repos`)。语义见下方「仓库绑定」 |

说明:v0.1 没有会话注册表,传入**合法 UUID 但无对应历史**时不报错,按空历史开始新会话继续。

### 仓库绑定(`repoId`)

- **首次绑定**:未绑定会话首次携带 `repoId` → 校验该仓库已接入(否则 404 `REPO_NOT_FOUND`),
  校验通过即将本会话绑定到该仓库,后续本会话的问答挂载该仓库的 GitHub 工具。
- **沿用绑定**:已绑定会话再传**相同** `repoId` 或**不传**,均沿用原绑定。
- **绑定冲突**:已绑定会话再传**不同** `repoId` → 400 `INVALID_PARAM`,提示新开会话切换。
- **不带 `repoId` 的未绑定会话** = v0.1 纯对话,不挂工具。
- `repoId` 类型非法(如传字符串)→ 400 `INVALID_PARAM`(JSON 解析阶段拦截)。
- **过期**:绑定关系与会话记忆**同 TTL**(默认 24h,`CHAT_MEMORY_TTL`),每轮对话刷新;
  过期后需重新携带 `repoId` 绑定。

### 响应 `200 OK`

```json
{
  "sessionId": "0f14d0ab-9605-4a62-a9e4-5ed26688389b",
  "answer": "模型回答文本",
  "sources": ["docs/api.md", "README.md"]
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `sessionId` | string | 会话 ID(请求未携带时为服务端新生成) |
| `answer` | string | 模型回答文本 |
| `sources` | string[] | 本轮**实际注入**的检索来源文件路径,去重、按检索得分降序;未绑定/未索引/无命中为 `[]`(永不为 null)。答案还可能来自工具调用,`sources` 只反映检索注入 |

### 错误

| 场景 | 状态码 | code |
| --- | --- | --- |
| `message` 为空/缺省/全空白 | 400 | `INVALID_PARAM` |
| `message` 超长 | 400 | `INVALID_PARAM` |
| `sessionId` 非 UUID 格式 | 400 | `INVALID_PARAM` |
| `repoId` 类型非法(非数字) | 400 | `INVALID_PARAM` |
| 已绑定会话再传不同 `repoId`(绑定冲突) | 400 | `INVALID_PARAM` |
| `repoId` 指向未接入/不存在的仓库 | 404 | `REPO_NOT_FOUND` |
| DeepSeek 超时/限流/鉴权失败 | 502 | `LLM_UNAVAILABLE` |
| 其他内部错误(如 Redis 不可用) | 500 | `INTERNAL_ERROR` |

### 示例

```bash
# 首轮:不带 sessionId,服务端生成
curl -s -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message": "用一句话介绍你自己"}'
# → {"sessionId":"<uuid>","answer":"..."}

# 多轮:带上一轮返回的 sessionId
curl -s -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"sessionId": "<uuid>", "message": "我上一个问题是什么?"}'

# 绑定仓库并提问(repoId 为 POST /api/repos 返回的 id):
# 首次携带 repoId 完成绑定,Agent 自动调用工具基于该仓库真实数据作答
curl -s -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"sessionId": "<uuid>", "message": "这个项目怎么在本地跑起来?", "repoId": 1}'

# 同一会话后续可省略 repoId,沿用绑定;追问可用指代
curl -s -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"sessionId": "<uuid>", "message": "刚才说的那个入口类在哪个目录?"}'

# 绑定冲突示例:已绑定会话再传不同 repoId
curl -s -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"sessionId": "<uuid>", "message": "换一个", "repoId": 2}'
# → 400 {"code":"INVALID_PARAM","message":"会话已绑定仓库 1,如需切换请新开会话"}

# 参数错误示例
curl -s -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"sessionId": "not-a-uuid", "message": "你好"}'
# → 400 {"code":"INVALID_PARAM","message":"sessionId 必须是 UUID 格式,或留空由服务端生成"}
```

---

## POST /api/repos

接入一个 GitHub 仓库:调 GitHub API 校验该仓库存在并拉取元信息(默认分支、描述等)落库。
一期仅支持**公开仓库**,私有仓库返回 404。

- **幂等**:同一仓库重复接入返回同一条记录(同 `id`),并刷新
  `defaultBranch`/`description`/`htmlUrl`/`updatedAt`。
- **大小写归一化**:GitHub 对 owner/repo 大小写不敏感,服务端以 GitHub 返回的规范
  `full_name` 拆出的 owner/name 入库与查重。
- 服务端默认匿名调用 GitHub API(限流阈值较低);设置环境变量 `GITHUB_TOKEN`
  后携带鉴权,限流阈值更高。

### 请求体

| 字段 | 类型 | 必填 | 约束 |
| --- | --- | --- | --- |
| `repo` | string | 是 | `owner/repo`,或 `https://github.com/owner/repo`(URL 允许尾部 `/` 或 `.git`)。owner 匹配 `[A-Za-z0-9-]+`,仓库名匹配 `[A-Za-z0-9._-]+`。其余形态一律 400:`http://`、`git@`、`www.github.com`、非 github.com 域名、多余路径段等 |

### 响应 `200 OK`(创建与重复接入均返回 200)

```json
{
  "id": 1,
  "owner": "octocat",
  "name": "Hello-World",
  "defaultBranch": "master",
  "description": "My first repository on GitHub!",
  "htmlUrl": "https://github.com/octocat/Hello-World",
  "createdAt": "2026-07-26T12:00:00",
  "updatedAt": "2026-07-26T12:00:00"
}
```

`description` 可能为 `null`(仓库无描述)。

### 错误

| 场景 | 状态码 | code |
| --- | --- | --- |
| `repo` 缺失/空/格式非法 | 400 | `INVALID_PARAM` |
| GitHub 查无此公开仓库(私有仓库同,message 注明「私有仓库暂不支持」) | 404 | `REPO_NOT_FOUND` |
| 网络错误/超时/GitHub 5xx | 502 | `GITHUB_UNAVAILABLE` |
| GitHub API 限流(message 明确为「GitHub API 限流,请稍后重试」) | 502 | `GITHUB_UNAVAILABLE` |

### 示例

```bash
# 裸 owner/repo 形态
curl -s -X POST http://localhost:8080/api/repos \
  -H 'Content-Type: application/json' \
  -d '{"repo": "octocat/Hello-World"}'

# 完整 URL 形态(允许尾部 / 或 .git),重复接入幂等返回同 id
curl -s -X POST http://localhost:8080/api/repos \
  -H 'Content-Type: application/json' \
  -d '{"repo": "https://github.com/octocat/Hello-World.git"}'

# 非法地址示例
curl -s -X POST http://localhost:8080/api/repos \
  -H 'Content-Type: application/json' \
  -d '{"repo": "https://gitlab.com/a/b"}'
# → 400 {"code":"INVALID_PARAM","message":"repo 格式不合法:仅支持 owner/repo 或 https://github.com/owner/repo(URL 允许尾部 / 或 .git)"}
```

---

## POST /api/repos/{id}/index

触发对已接入仓库的**文档向量化入库**(FR-3.1):拉取该仓库 README 与 `docs/` 目录下的文本文档
(扩展名白名单 `.md/.markdown/.txt/.adoc/.rst`),切分、进程内 `bge-small-zh` 向量化后存入 `doc_chunk` 表。

- **同步执行**:请求在索引完成后才返回;耗时受拉取上限(默认最多 30 个文件、单文件 100KB)约束。
- **幂等重建**:重复调用会先删该仓库旧块再整体重建,`doc_chunk` 总数不因重复调用增长。
- 服务端默认匿名调用 GitHub API(限流阈值较低,索引会拉多个文件);设置 `GITHUB_TOKEN` 阈值更高。
- 拉取范围与上限由服务端配置(`RAG_*`,见 README),不经请求参数。

### 路径参数

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `id` | number | 已接入仓库的 id(见 `POST /api/repos`) |

### 响应 `200 OK`

```json
{
  "repoId": 1,
  "fileCount": 8,
  "chunkCount": 63,
  "costMs": 2450
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `repoId` | number | 被索引的仓库 id |
| `fileCount` | number | 实际拉取并索引的文档数(README 计入) |
| `chunkCount` | number | 切分并入库的文档块总数 |
| `costMs` | number | 本次索引总耗时(毫秒) |

### 错误

| 场景 | 状态码 | code |
| --- | --- | --- |
| `id` 非数字 | 400 | `INVALID_PARAM` |
| 仓库未接入/不存在(message「仓库未接入或不存在」) | 404 | `REPO_NOT_FOUND` |
| 拉取目录树时 GitHub 网络错误/超时/5xx | 502 | `GITHUB_UNAVAILABLE` |
| 拉取目录树时 GitHub 限流(message 明确提示限流) | 502 | `GITHUB_UNAVAILABLE` |

说明:README 或单个 docs 文件拉取失败会被跳过(不致命),不影响整体成功;只有**目录树整体**拉取失败才返回 502。

### 示例

```bash
# 先接入仓库拿到 id,再触发索引
curl -s -X POST http://localhost:8080/api/repos/1/index
# → {"repoId":1,"fileCount":8,"chunkCount":63,"costMs":2450}

# 未接入的 id
curl -s -X POST http://localhost:8080/api/repos/999999/index
# → 404 {"code":"REPO_NOT_FOUND","message":"仓库未接入或不存在:id=999999"}
```

---

## POST /api/repos/{id}/report

生成该仓库的**结构化导读报告**(FR-3.3):服务端确定性取数(四个 GitHub 工具按默认参数各调一次
+ 对已索引文档按固定查询集检索摘录),单次 LLM 调用生成 Markdown 报告,**不经过会话/记忆**。

- **同步执行**:请求在报告生成完成后才返回;耗时主要在 LLM 生成(参考非功能预期 30 秒内)。
- 报告固定包含五个二级小节,顺序固定:`## 项目定位`、`## 技术栈`、`## 目录结构解读`、
  `## 上手指引`、`## 近期动向`。服务端校验五节齐全且非空,不合规会追加纠正指令重试一次,
  仍不合规照常返回(记服务端 WARN)。
- 该仓库未建文档索引时报告仍可生成(摘录区标注未索引);**建议先
  `POST /api/repos/{id}/index`** 让「上手指引」等小节能引用文档摘录并注明来源路径。

### 路径参数

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `id` | number | 已接入仓库的 id(见 `POST /api/repos`) |

### 响应 `200 OK`

```json
{
  "repoId": 1,
  "generatedAt": "2026-07-26T12:00:00",
  "costMs": 12450,
  "report": "## 项目定位\n……\n## 技术栈\n……\n## 目录结构解读\n……\n## 上手指引\n……\n## 近期动向\n……"
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `repoId` | number | 仓库 id |
| `generatedAt` | string | 报告生成时间(截断到秒) |
| `costMs` | number | 本次生成总耗时(毫秒,含取数与 LLM 调用) |
| `report` | string | 报告 Markdown 全文(五个固定小节) |

### 错误

| 场景 | 状态码 | code |
| --- | --- | --- |
| `id` 非数字 | 400 | `INVALID_PARAM` |
| 仓库未接入/不存在(message「仓库未接入或不存在」) | 404 | `REPO_NOT_FOUND` |
| DeepSeek 超时/限流/鉴权失败 | 502 | `LLM_UNAVAILABLE` |

说明:**GitHub 故障不返回 502**——四个工具的数据获取失败时降级为一行可读文本进入提示词,
报告仍会生成,并在对应小节如实说明数据缺失。

### 示例

```bash
# 建议先索引(摘录区才有内容),再生成报告
curl -s -X POST http://localhost:8080/api/repos/1/index
curl -s -X POST http://localhost:8080/api/repos/1/report
# → {"repoId":1,"generatedAt":"2026-07-26T12:00:00","costMs":12450,"report":"## 项目定位\n……"}

# 未接入 / id 非数字
curl -s -X POST http://localhost:8080/api/repos/999999/report
# → 404 {"code":"REPO_NOT_FOUND","message":"仓库未接入或不存在:id=999999"}
curl -s -X POST http://localhost:8080/api/repos/abc/report
# → 400 {"code":"INVALID_PARAM","message":"参数 id 类型不合法"}
```

---

## GET /api/repos

已接入仓库列表,按 `id` 倒序。一期不分页。

### 响应 `200 OK`

JSON 数组,元素结构同 `POST /api/repos` 响应:

```json
[
  {"id": 2, "owner": "...", "name": "...", "defaultBranch": "...", "description": null, "htmlUrl": "...", "createdAt": "...", "updatedAt": "..."},
  {"id": 1, "owner": "...", "name": "...", "defaultBranch": "...", "description": "...", "htmlUrl": "...", "createdAt": "...", "updatedAt": "..."}
]
```

### 示例

```bash
curl -s http://localhost:8080/api/repos
```

---

## GET /api/repos/{id}

按 `id` 查询单个已接入仓库。

### 响应 `200 OK`

结构同 `POST /api/repos` 响应。

### 错误

| 场景 | 状态码 | code |
| --- | --- | --- |
| `id` 非数字 | 400 | `INVALID_PARAM` |
| 仓库未接入或不存在(message「仓库未接入或不存在」) | 404 | `REPO_NOT_FOUND` |

### 示例

```bash
curl -s http://localhost:8080/api/repos/1
curl -s http://localhost:8080/api/repos/999999   # → 404 REPO_NOT_FOUND
curl -s http://localhost:8080/api/repos/abc      # → 400 INVALID_PARAM
```
