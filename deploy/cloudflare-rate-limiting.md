# Cloudflare 限流规则配置说明

> 在 VPS 部署 + Vercel 前端均完成并验证后，执行本步骤。
> **需手动在 Cloudflare Dashboard 操作，本文档不自动配置。**
>
> Free 计划的 Rate Limiting Rules 上限为 1 条。当前套餐先配置 `global-api`；`chat-rate`、`index-rate`、`report-rate` 需要 Pro 套餐或在应用层另行实现。

---

## 操作入口

Cloudflare Dashboard → `chada010.me` → **Security → WAF → Rate limiting rules**

---

## 限流规则

Pro 计划可按顺序创建以下四条规则（顺序决定优先级，精确路由放前面）：

> 下面四条规则都必须限定 `http.host eq "repo-scout.chada010.me"`。同一 Cloudflare Zone 还承载其他域名，不能只按 URI path 限流，否则会波及现有服务。

### 规则 1：chat-rate（Pro / 应用层可选）

| 字段 | 值 |
|------|----|
| 规则名 | `chat-rate` |
| 匹配条件 | `http.host eq "repo-scout.chada010.me" and http.request.uri.path eq "/api/chat"` |
| 限制 | **5 req / IP / 60s** |
| 动作 | Block（返回 429） |
| 说明 | 对话接口消耗 DeepSeek token，严格限流 |

### 规则 2：index-rate（Pro / 应用层可选）

| 字段 | 值 |
|------|----|
| 规则名 | `index-rate` |
| 匹配条件 | `http.host eq "repo-scout.chada010.me" and http.request.uri.path matches "^/api/repos/[^/]+/index$"` |
| 限制 | **2 req / IP / 60s** |
| 动作 | Block（返回 429） |
| 说明 | 向量化索引拉取多个文件，高成本操作 |

### 规则 3：report-rate（Pro / 应用层可选）

| 字段 | 值 |
|------|----|
| 规则名 | `report-rate` |
| 匹配条件 | `http.host eq "repo-scout.chada010.me" and http.request.uri.path matches "^/api/repos/[^/]+/report$"` |
| 限制 | **2 req / IP / 60s** |
| 动作 | Block（返回 429） |
| 说明 | 导读报告调用 LLM，高成本操作 |

### 规则 4：global-api（Free 计划唯一可配置规则）

| 字段 | 值 |
|------|----|
| 规则名 | `global-api` |
| 匹配条件 | `http.host eq "repo-scout.chada010.me" and starts_with(http.request.uri.path, "/api/")` |
| 限制 | **5 req / IP / 10s**（Free 计划可用，约等于 30 req/min） |
| 动作 | Block（返回 429） |
| 说明 | 兜底全局限流，防止批量轮询 |

---

## 豁免

- `GET /api/health`（健康检查）不在 `/api/chat` 等精确规则内，由 global-api 兜底（30 req/60s 足够）；
  若需完全豁免，可在 WAF 自定义规则中添加 Skip 规则，优先级高于 rate-limit 规则。
- Host 条件是必需的，避免规则影响同一 Zone 下的 `api.chada010.me` 等现有服务。

---

## 验证

配置 `global-api` 后，优先通过 Cloudflare Security Events 确认规则命中。不要在生产环境批量调用会触发 LLM 的 `/api/chat`；Pro 规则上线后再用空消息校验 400/429 行为。

Pro 计划的 `chat-rate` 可用以下请求快速验证（5 次后应 429，空消息会在后端参数校验阶段结束）：

```bash
for i in {1..7}; do
  curl -s -o /dev/null -w "req $i: %{http_code}\n" \
    -X POST https://repo-scout.chada010.me/api/chat \
    -H 'Content-Type: application/json' \
    -d '{"message":"test"}'
done
# 前 5 次应为 401/200，第 6/7 次应为 429
```

---

## 注意事项

- Cloudflare 免费套餐的 Rate Limiting 每月有请求数配额；如流量大可升级套餐；
- 限流计数以 IP 为维度；Vercel serverless 代理会转发真实客户端 IP 到 `X-Forwarded-For`，Cloudflare 按外层 IP 计数，行为符合预期；
- 限流规则对 Cloudflare 直连生效；绕过 Cloudflare 直连后端需依赖 Nginx 层（可选扩充 `limit_req`）；
- 本版本不实现 Nginx 层限流，VPS 侧以 `INTERNAL_API_KEY` 门禁为主要防线。
