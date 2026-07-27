# Vercel 部署 SOP

> **本文档在 PR merge 后由操作者手动执行，PR 中不自动配置 Vercel。**

---

## 前置条件

| 项目 | 要求 |
|------|------|
| Vercel 账号 | 已有，可导入 GitHub 仓库 |
| 密钥 | `REPO_SCOUT_INTERNAL_KEY` 与 VPS 的 `INTERNAL_API_KEY` 完全一致 |
| 后端 | dedirock-01 已按 `vps-deploy-sop.md` 部署并验证通过 |

---

## 步骤 1：在 Vercel 创建项目

1. 进入 [vercel.com](https://vercel.com) → **Add New → Project**；
2. 导入 GitHub 仓库 `zohan20050521-sudo/repo-scout`；
3. 设置 **Root Directory** 为 `web`；
4. 设置：
   - **Build Command**：`npm run build`
   - **Output Directory**：`dist`
   - **Install Command**：`npm ci`（默认即可）
5. **暂不点 Deploy**，先完成步骤 2。

---

## 步骤 2：添加服务端环境变量

在项目 **Settings → Environment Variables** 中添加以下变量：

| 变量名 | 值 | 环境 |
|--------|-----|------|
| `REPO_SCOUT_BACKEND_URL` | `https://api.repo-scout.chada010.me` | **Production only**（不勾选 Preview / Development）|
| `REPO_SCOUT_INTERNAL_KEY` | `<与 VPS INTERNAL_API_KEY 相同的密钥>` | **Production only** |

> ⚠️ **安全**：两个变量均选 **Production only**，不勾选 Preview 和 Development；
> 不得使用 `VITE_` 前缀，否则会被打包进 bundle，暴露给浏览器。

---

## 步骤 3：部署

回到项目 Overview 点击 **Deploy**（或 git push main 自动触发）。

等待构建完成，验证：
```
https://<project-name>.vercel.app/api/health
# → {"status":"UP","application":"repo-scout"}
```

---

## 步骤 4：绑定自定义域名

1. 进入项目 **Settings → Domains**；
2. 添加 `repo-scout.chada010.me`；
3. Vercel 会提示需要添加 CNAME 记录，记下 Vercel 提供的目标值（形如 `cname.vercel-dns.com`）。

---

## 步骤 5：在 Cloudflare 配置 CNAME

在 Cloudflare Dashboard → `chada010.me` → DNS → Records 中新增：

| 类型 | 名称 | 内容 | 代理状态 |
|------|------|------|----------|
| CNAME | `repo-scout` | `<Vercel 提供的 CNAME 值>` | 🔶 **已代理（橙云）** |

---

## 步骤 6：验收

```bash
# 前端首页可访问
curl -s -o /dev/null -w '%{http_code}\n' https://repo-scout.chada010.me
# → 200

# 同源代理可访问后端（经过 serverless 注入 key）
curl -s https://repo-scout.chada010.me/api/health
# → {"status":"UP","application":"repo-scout"}

# 直接访问后端无 key 应 401（门禁生效）
curl -s https://api.repo-scout.chada010.me/api/repos
# → {"code":"UNAUTHORIZED","message":"无权访问该接口"}
```

---

## 注意事项

- Vercel 免费套餐 serverless function 有执行时间限制（默认 10s，Pro 300s）；
  索引（`/api/repos/{id}/index`）与报告（`/api/repos/{id}/report`）是同步长请求，建议升级 Pro 或在 Vercel 项目 Settings → Functions 中延长超时。
- Preview 部署不含后端环境变量，访问 `/api/**` 会返回 503；这是预期行为，不影响 Production。
