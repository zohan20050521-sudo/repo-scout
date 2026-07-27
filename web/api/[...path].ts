// web/api/[...path].ts
// Vercel Serverless 同源代理：从服务端环境变量注入内部密钥，浏览器永不持有。
// 所有 /api/** 请求经由此函数转发到 dedirock-01 后端，并自动附加 X-Repo-Scout-Internal-Key。
import type { VercelRequest, VercelResponse } from '@vercel/node'
import https from 'node:https'
import http from 'node:http'

const BACKEND = (process.env['REPO_SCOUT_BACKEND_URL'] ?? '').replace(/\/$/, '')
const INTERNAL_KEY = process.env['REPO_SCOUT_INTERNAL_KEY'] ?? ''
// 索引与报告是同步长请求，超时需宽松（Vercel Free 10s，Pro 可调至 300s）
const UPSTREAM_TIMEOUT_MS = 180_000 // 3min

export default async function handler(req: VercelRequest, res: VercelResponse): Promise<void> {
  if (!BACKEND) {
    res.status(503).json({ code: 'INTERNAL_ERROR', message: '后端地址未配置' })
    return
  }

  // req.query.path 形如 ['repos', '1', 'index']（来自 /api/repos/1/index）
  const rawPath = req.query['path']
  const pathSegments = Array.isArray(rawPath) ? rawPath : [rawPath ?? '']
  const targetPath = '/api/' + pathSegments.join('/')

  // 透传 query string（去掉 Vercel 注入的 path 参数）
  const search = new URLSearchParams()
  for (const [k, v] of Object.entries(req.query)) {
    if (k === 'path') continue
    if (Array.isArray(v)) {
      for (const item of v) search.append(k, item)
    } else if (v != null) {
      search.append(k, v)
    }
  }
  const qs = search.toString()
  const url = BACKEND + targetPath + (qs ? `?${qs}` : '')

  // 透传安全相关 header，注入内部密钥
  const xff = req.headers['x-forwarded-for']
  const xffStr = Array.isArray(xff) ? xff.join(', ') : (xff ?? req.socket?.remoteAddress ?? '')
  const upHeaders: Record<string, string> = {
    'content-type': String(req.headers['content-type'] ?? 'application/json'),
    'accept': String(req.headers['accept'] ?? 'application/json'),
    'x-forwarded-for': xffStr,
  }
  if (INTERNAL_KEY) upHeaders['x-repo-scout-internal-key'] = INTERNAL_KEY

  // GET/HEAD 不含 body；其余请求序列化 req.body（Vercel 默认已解析 JSON）
  const body: Buffer | string | undefined =
    req.method !== 'GET' && req.method !== 'HEAD'
      ? Buffer.isBuffer(req.body)
        ? req.body
        : JSON.stringify(req.body)
      : undefined

  const lib = url.startsWith('https') ? https : http

  try {
    const upstream = await new Promise<{ status: number; headers: Record<string, string>; body: Buffer }>(
      (resolve, reject) => {
        const r = lib.request(url, { method: req.method ?? 'GET', headers: upHeaders, timeout: UPSTREAM_TIMEOUT_MS }, (u) => {
          const chunks: Buffer[] = []
          u.on('data', (c: Buffer) => chunks.push(c))
          u.on('end', () =>
            resolve({
              status: u.statusCode ?? 502,
              headers: u.headers as Record<string, string>,
              body: Buffer.concat(chunks),
            }),
          )
        })
        r.on('error', reject)
        r.on('timeout', () => {
          r.destroy()
          reject(new Error('upstream timeout'))
        })
        if (body) r.write(body)
        r.end()
      },
    )

    res.status(upstream.status)
    const ct = upstream.headers['content-type']
    if (ct) res.setHeader('content-type', ct)
    res.send(upstream.body)
  } catch {
    // 不向浏览器暴露后端地址或错误细节
    res.status(502).json({ code: 'INTERNAL_ERROR', message: '请求后端失败，请稍后重试' })
  }
}
