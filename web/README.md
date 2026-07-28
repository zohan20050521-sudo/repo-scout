# repo-scout · 前端

Vue 3 + TypeScript 产品前端，形成「接入仓库 → 建立索引 → 多轮问答（带 citations）→ 导读报告」完整闭环。

## 环境要求

| 工具 | 版本 |
| --- | --- |
| Node.js | ≥ 20.19.0 |
| npm | ≥ 10 |

## 命令

```bash
# 安装依赖（提交了 package-lock.json，使用 ci 保证可复现）
npm ci

# 本地开发服务（热更新，Vite proxy 转发 /api → 本地后端）
npm run dev

# 代码格式与静态质量检查
npm run lint
npm run typecheck

# 单元测试
npm run test          # watch 模式
npm run test -- --run # 单次运行（CI 用）

# 生产构建
npm run build
npm run preview       # 本地预览构建产物
```

## Vite proxy 配置

开发期浏览器只请求同源 `/api`，由 Vite dev server 通过 proxy 转发到本地后端：

```bash
# 复制示例环境变量（非敏感，只是代理目标地址）
cp .env.example .env.local
# 默认目标：http://localhost:8080
```

`VITE_DEV_PROXY_TARGET` 只改转发目标，不影响浏览器侧路径。

**⚠️ 安全边界**：浏览器代码、Vite 变量和构建产物中**绝不出现** `INTERNAL_API_KEY` 或
`X-Repo-Scout-Internal-Key`。这两个凭据只允许放在同源服务端代理（如 Vercel Serverless）的环境变量里，由代理注入后端请求，浏览器永远感知不到。

## 当前能力与已知限制

| 功能 | 说明 |
| --- | --- |
| 仓库接入与列表 | `GET /api/repos`、`POST /api/repos`、`GET /api/repos/{id}` |
| 文档索引 | `GET /api/repos/{id}/index-status`、`POST /api/repos/{id}/index`（异步任务 + 状态轮询） |
| 多轮问答 | `POST /api/chat`，首轮绑定 repoId，后续复用 sessionId |
| 结构化 citations | 每轮独立展示 filePath / chunkIndex / score / excerpt，可展开全文、跳转 GitHub |
| 导读报告 | `POST /api/repos/{id}/report`（同步长请求），五节 Markdown，可复制、下载 |

**不含**以下能力（后端 v0.3.5 未实现）：
- SSE 流式输出 / 工具调用轨迹
- 用户登录与身份系统
- 服务端会话列表或历史恢复
- 异步索引进度
- 报告历史存储

## 目录结构

```text
web/
  src/
    api/          # axios client、repos/chat/report API 函数
    components/   # 通用与业务组件
    composables/  # markdown、download、scroll、format 复用逻辑
    router/
    stores/       # repo / chat / report（仅跨页面状态）
    styles/       # design tokens、base reset、Element Plus 覆盖、Markdown 排版
    types/        # 与 docs/api.md 一一对应的类型
    views/        # HomeView / RepoWorkspaceView / NotFoundView
  tests/          # Vitest 单元测试
  docs/screenshots/  # 浏览器验收截图
  .env.example    # 非敏感的本地代理目标示例
```
