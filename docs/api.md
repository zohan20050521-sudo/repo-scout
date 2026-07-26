# repo-scout API 文档

- 版本:v0.2
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

### 请求体

| 字段 | 类型 | 必填 | 约束 |
| --- | --- | --- | --- |
| `sessionId` | string | 否 | 标准 UUID 格式(8-4-4-4-12)。为空/缺省时服务端生成新会话 |
| `message` | string | 是 | 非空,长度 ≤ 4000 字符(可通过 `CHAT_MESSAGE_MAX_LENGTH` 调整) |

说明:v0.1 没有会话注册表,传入**合法 UUID 但无对应历史**时不报错,按空历史开始新会话继续。

### 响应 `200 OK`

```json
{
  "sessionId": "0f14d0ab-9605-4a62-a9e4-5ed26688389b",
  "answer": "模型回答文本"
}
```

### 错误

| 场景 | 状态码 | code |
| --- | --- | --- |
| `message` 为空/缺省/全空白 | 400 | `INVALID_PARAM` |
| `message` 超长 | 400 | `INVALID_PARAM` |
| `sessionId` 非 UUID 格式 | 400 | `INVALID_PARAM` |
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
