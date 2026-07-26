# repo-scout API 文档

- 版本:v0.1
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
