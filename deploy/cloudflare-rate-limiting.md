# Cloudflare 限流规则配置说明

> 在 VPS 部署 + Vercel 前端均完成并验证后，执行本步骤。
> **需手动在 Cloudflare Dashboard 操作，本文档不自动配置。**

---

## 操作入口

Cloudflare Dashboard → `chada010.me` → **Security → WAF → Rate limiting rules**

---

## 限流规则

按顺序创建以下四条规则（顺序决定优先级，精确路由放前面）：

### 规则 1：chat-rate

| 字段 | 值 |
|------|----|
| 规则名 | `chat-rate` |
| 匹配条件 | URI path **等于** `/api/chat` |
| 限制 | **5 req / IP / 60s** |
| 动作 | Block（返回 429） |
| 说明 | 对话接口消耗 DeepSeek token，严格限流 |

### 规则 2：index-rate

| 字段 | 值 |
|------|----|
| 规则名 | `index-rate` |
| 匹配条件 | URI path **匹配正则** `^/api/repos/[^/]+/index$` |
| 限制 | **2 req / IP / 60s** |
| 动作 | Block（返回 429） |
| 说明 | 向量化索引拉取多个文件，高成本操作 |

### 规则 3：report-rate

| 字段 | 值 |
|------|----|
| 规则名 | `report-rate` |
| 匹配条件 | URI path **匹配正则** `^/api/repos/[^/]+/report$` |
| 限制 | **2 req / IP / 60s** |
| 动作 | Block（返回 429） |
| 说明 | 导读报告调用 LLM，高成本操作 |

### 规则 4：global-api

| 字段 | 值 |
|------|----|
| 规则名 | `global-api` |
| 匹配条件 | URI path **以** `/api/` **开头** |
| 限制 | **30 req / IP / 60s** |
| 动作 | Block（返回 429） |
| 说明 | 兜底全局限流，防止批量轮询 |

---

## 豁免

- `GET /api/health`（健康检查）不在 `/api/chat` 等精确规则内，由 global-api 兜底（30 req/60s 足够）；
  若需完全豁免，可在 WAF 自定义规则中添加 Skip 规则，优先级高于 rate-limit 规则。

---

## 验证

配置完成后，用 curl 快速验证 chat 限流（5次后应 429）：

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
