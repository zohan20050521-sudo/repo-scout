# VPS 部署 SOP（dedirock-01）

> **安全提示**：以下步骤含不可逆操作（nginx reload、certbot、docker compose up），
> 务必逐步执行，每步验证通过后再进行下一步。
> **本文档在 PR merge 后由架构师逐步指导执行，不得自行运行。**

---

## 前置条件

| 项目 | 要求 |
|------|------|
| 服务器 | dedirock-01，IP `192.236.226.3` |
| 操作系统 | Linux，已安装 Docker Engine + Compose V2、Nginx、Certbot |
| 域名 | `api.repo-scout.chada010.me` 已在 Cloudflare 创建 A 记录（灰云，见步骤 1）|
| 端口 | `18080` 未被其他进程占用 |
| 密钥 | `INTERNAL_API_KEY` 已在本地用 `openssl rand -hex 32` 生成，与 Vercel 端一致 |

---

## 步骤 1：DNS 先行（Cloudflare 灰云）

在 Cloudflare Dashboard → `chada010.me` → DNS → Records 中新增：

| 类型 | 名称 | 内容 | 代理状态 |
|------|------|------|----------|
| A | `api.repo-scout` | `192.236.226.3` | 🔲 **仅 DNS（灰云）** |

> certbot 需要直连服务器验证，灰云确保验证期间请求不经过 Cloudflare 代理。
> 申请证书成功后在步骤 9 改回橙云。

验证 DNS 已生效：
```bash
dig api.repo-scout.chada010.me +short
# 应返回 192.236.226.3
```

---

## 步骤 2：克隆仓库

```bash
git clone https://github.com/zohan20050521-sudo/repo-scout.git /opt/repo-scout
cd /opt/repo-scout
```

---

## 步骤 3：生成并填写 .env

```bash
cp /opt/repo-scout/deploy/env-template.txt /opt/repo-scout/.env
# 用编辑器填写三个必填项：
#   DEEPSEEK_API_KEY=<你的 DeepSeek API Key>
#   INTERNAL_API_KEY=<与 Vercel REPO_SCOUT_INTERNAL_KEY 完全一致的密钥>
#   MYSQL_ROOT_PASSWORD=<随机密码，openssl rand -hex 16>
chmod 600 /opt/repo-scout/.env
```

检查无明文密钥被意外提交：
```bash
grep -r 'sk-' /opt/repo-scout/deploy/ /opt/repo-scout/web/api/
# 应无输出
```

---

## 步骤 4：部署 Nginx 配置并测试

```bash
cp /opt/repo-scout/deploy/nginx-api.conf /etc/nginx/conf.d/repo-scout-api.conf
nginx -t
# 应输出：configuration file /etc/nginx/nginx.conf test is successful
nginx -s reload
```

验证 HTTP 重定向（此时 HTTPS 尚无证书，443 会报错，属正常）：
```bash
curl -v http://api.repo-scout.chada010.me/api/health 2>&1 | grep -E 'Location|HTTP/'
# 应看到 301 到 https://
```

---

## 步骤 5：申请 SSL 证书

```bash
certbot --nginx -d api.repo-scout.chada010.me
# 按提示填写 email，同意 TOS
# certbot 完成后会自动修改 /etc/nginx/conf.d/repo-scout-api.conf，补充 ssl_certificate 等行
nginx -t && nginx -s reload
```

验证证书：
```bash
curl -s https://api.repo-scout.chada010.me/ -o /dev/null -w '%{http_code}\n'
# 此时后端还未启动，应返回 502；关键是 TLS 握手成功（非 curl SSL 错误）
```

---

## 步骤 6：启动服务

```bash
cd /opt/repo-scout
docker compose -f compose.yaml -f compose.prod.yaml --env-file .env up -d --build
```

等待启动（MySQL healthcheck 约 30s）：
```bash
docker compose -f compose.yaml -f compose.prod.yaml ps
# app、mysql、redis 均应为 healthy 或 running
```

---

## 步骤 7：健康检查

```bash
curl -s https://api.repo-scout.chada010.me/api/health
# → {"status":"UP","application":"repo-scout"}
```

---

## 步骤 8：门禁验证

```bash
# 无 key → 401
curl -s https://api.repo-scout.chada010.me/api/repos
# → {"code":"UNAUTHORIZED","message":"无权访问该接口"}

# 有 key → 200（将 <internal-key> 替换为实际值）
curl -s https://api.repo-scout.chada010.me/api/repos \
  -H 'X-Repo-Scout-Internal-Key: <internal-key>'
# → []（空列表，代表门禁通过、后端正常）
```

---

## 步骤 9：Cloudflare 改为橙云

在 Cloudflare Dashboard → `chada010.me` → DNS → Records，将 `api.repo-scout` A 记录的代理状态改为 🔶 **已代理（橙云）**。

验证经过 Cloudflare 代理：
```bash
curl -s -I https://api.repo-scout.chada010.me/api/health | grep -i cf-ray
# 应出现 cf-ray 响应头
```

---

## 步骤 10：配置 Cloudflare 限流规则

见 `deploy/cloudflare-rate-limiting.md`。

---

## 步骤 11：验收清单

- [ ] `GET /api/health` 公开可访问，返回 `{"status":"UP"}`
- [ ] `GET /api/repos`（无 key）返回 401
- [ ] `GET /api/repos`（有 key）返回 200
- [ ] TLS 证书有效，浏览器绿锁
- [ ] Cloudflare 已代理（橙云）
- [ ] Cloudflare 限流规则已配置（见 cloudflare-rate-limiting.md）

---

## 日常运维

```bash
# 查看应用日志
docker compose -f compose.yaml -f compose.prod.yaml logs -f app

# 更新代码后重启
cd /opt/repo-scout
git pull
docker compose -f compose.yaml -f compose.prod.yaml --env-file .env up -d --build

# 停止（保留数据卷）
docker compose -f compose.yaml -f compose.prod.yaml down

# 证书续期（certbot 自动续期，手动测试）
certbot renew --dry-run
```
