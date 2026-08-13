/**
 * SAATIRIL — Electron Main Process
 *
 * This is the entry point for the Electron desktop application.
 * It starts:
 * 1. Next.js static export server (HTTP on dynamic port)
 * 2. Socket.io relay server (on dynamic port)
 * 3. Splash loading screen (immediate visual feedback)
 * 4. Electron BrowserWindow loading the Next.js app
 *
 * IPC handlers exposed to the renderer:
 * - selectFolder: Open native folder picker dialog
 * - createFolder: Create a directory on disk
 * - savePhoto: Save base64 photo data to disk
 * - getLanInfo: Get LAN IP addresses and ports
 * - getLicenseStatus: Check current license status
 * - activateLicense: Activate with an activation code
 * - getMachineId: Get the Machine ID for this computer
 */

import { app, BrowserWindow, ipcMain, dialog, shell } from 'electron'
import * as path from 'path'
import * as https from 'https'
import * as fs from 'fs'
import * as os from 'os'
import { createServer } from 'http'
import { Server as SocketIOServer } from 'socket.io'
import { checkLicenseStatus, activateLicense, getMachineId, getDisplayMachineId, generateLicenseCode } from './license'

// ─── Configuration ─────────────────────────────────────────────────────────
const DEFAULT_HTTP_PORT = 3000
const DEFAULT_SOCKET_PORT = 3003
const MAX_HTTP_BUFFER = 20e6 // 20MB for large photo payloads
const isDev = process.env.SAATIRIL_DEV === '1'

// ─── GitHub Releases (for APK/Portable download) ───────────────────────────
const GITHUB_REPO = 'synclicen/Saatiril-Fullset'
const RELEASE_TAG = 'latest'

interface ReleaseAssetInfo {
  url: string
  browserUrl: string
  size: number
  sizeMB: string
  lastModified: string
  assetName: string
}

interface CachedRelease {
  apk: ReleaseAssetInfo | null
  portable: ReleaseAssetInfo | null
}

let cachedRelease: CachedRelease | null = null
let cacheTime = 0
const CACHE_TTL = 5 * 60 * 1000 // 5 minutes

async function getGitHubToken(): Promise<string> {
  // Try env variable first
  if (process.env.GITHUB_TOKEN) return process.env.GITHUB_TOKEN

  // Try to get token from git remote in the project directory
  try {
    const { execSync } = await import('child_process')
    // Try multiple possible project directories
    const possibleDirs = [
      path.join(__dirname, '..'),
      process.cwd(),
    ]
    for (const dir of possibleDirs) {
      try {
        const remoteUrl = execSync('git remote get-url origin', {
          encoding: 'utf-8',
          cwd: dir,
        }).trim()
        const match = remoteUrl.match(/:\/\/[^:]*:([^@]*)@/)
        if (match?.[1]) return match[1]
      } catch {
        // No git repo in this directory, try next
      }
    }
  } catch {
    // child_process not available
  }

  return ''
}

async function fetchLatestReleaseInfo(): Promise<CachedRelease> {
  const now = Date.now()
  if (cachedRelease && (now - cacheTime) < CACHE_TTL) {
    return cachedRelease
  }

  try {
    const token = await getGitHubToken()
    const headers: Record<string, string> = {
      Accept: 'application/vnd.github+json',
      'User-Agent': 'Saatiril-Electron-App',
    }
    if (token) {
      headers.Authorization = `Bearer ${token}`
    }

    const res = await fetch(`https://api.github.com/repos/${GITHUB_REPO}/releases/tags/${RELEASE_TAG}`, { headers })

    if (!res.ok) {
      throw new Error(`GitHub API returned ${res.status}`)
    }

    const release = await res.json() as { assets?: Array<{ name: string; url: string; browser_download_url: string; size: number; updated_at: string }>; published_at?: string }
    const assets = release.assets || []

    const apkAsset = assets.find((a: { name: string }) => a.name.endsWith('.apk'))
    const portableAsset = assets.find((a: { name: string }) =>
      a.name.endsWith('-portable.exe') || a.name === 'saatiril-portable.exe'
    )

    const toAssetInfo = (a: { url: string; browser_download_url: string; size: number; updated_at: string; name: string }): ReleaseAssetInfo => ({
      url: a.url,
      browserUrl: a.browser_download_url,
      size: a.size,
      sizeMB: (a.size / (1024 * 1024)).toFixed(1),
      lastModified: a.updated_at || release.published_at || '',
      assetName: a.name,
    })

    cachedRelease = {
      apk: apkAsset ? toAssetInfo(apkAsset) : null,
      portable: portableAsset ? toAssetInfo(portableAsset) : null,
    }
    cacheTime = now

    console.log(`[SAATIRIL] GitHub Releases: APK=${cachedRelease.apk ? cachedRelease.apk.assetName : 'none'}, Portable=${cachedRelease.portable ? cachedRelease.portable.assetName : 'none'}`)
  } catch (err: any) {
    console.error('[SAATIRIL] Failed to fetch GitHub Releases:', err.message)
    // Return empty cache if fetch fails
    if (!cachedRelease) {
      cachedRelease = { apk: null, portable: null }
    }
  }

  return cachedRelease
}

// Actual ports (may differ from defaults if ports are in use)
let httpPort = DEFAULT_HTTP_PORT
let socketPort = DEFAULT_SOCKET_PORT

// ─── API Route Handlers (for Electron portable build) ─────────────────────
// In the Electron portable version, the app is served as a static export.
// Next.js API routes don't exist in the static export, so we must handle
// them here in the Electron main process's HTTP server.

/**
 * Handle /api/apk-download requests
 * GET  → Returns release info (APK + Portable availability, size, etc.)
 * POST → Proxies the binary download from GitHub Releases
 */
function handleApkDownloadApi(
  req: import('http').IncomingMessage,
  res: import('http').ServerResponse,
  urlQuery: string,
) {
  if (req.method === 'GET') {
    // Return release info
    fetchLatestReleaseInfo()
      .then((info) => {
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({
          apk: info.apk ? {
            available: true,
            sizeMB: info.apk.sizeMB,
            assetName: info.apk.assetName,
            lastModified: info.apk.lastModified,
          } : {
            available: false,
            error: 'No APK asset found in latest release',
          },
          portable: info.portable ? {
            available: true,
            sizeMB: info.portable.sizeMB,
            assetName: info.portable.assetName,
            lastModified: info.portable.lastModified,
          } : {
            available: false,
            error: 'No Portable asset found in latest release',
          },
        }))
      })
      .catch((err: any) => {
        const message = err instanceof Error ? err.message : 'Unknown error'
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({
          apk: { available: false, error: message },
          portable: { available: false, error: message },
        }))
      })
    return
  }

  if (req.method === 'POST') {
    // Proxy binary download from GitHub Releases
    let body = ''
    req.on('data', (chunk: Buffer) => { body += chunk.toString() })
    req.on('end', () => {
      let type = 'apk'
      try {
        const parsed = JSON.parse(body)
        type = parsed.type || 'apk'
      } catch { /* default to apk */ }

      // Also check URL query param for type (e.g. ?type=portable)
      if (type === 'apk' && urlQuery.includes('type=portable')) {
        type = 'portable'
      }

      fetchLatestReleaseInfo()
        .then(async (info) => {
          const assetInfo = type === 'portable' ? info.portable : info.apk
          if (!assetInfo) {
            res.writeHead(404, { 'Content-Type': 'application/json' })
            res.end(JSON.stringify({
              error: `${type === 'portable' ? 'Portable' : 'APK'} not available in latest release`,
            }))
            return
          }

          const token = await getGitHubToken()
          const headers: Record<string, string> = {
            Accept: 'application/octet-stream',
            'User-Agent': 'Saatiril-Electron-App',
          }
          if (token) {
            headers.Authorization = `Bearer ${token}`
          }

          const response = await fetch(assetInfo.url, { headers })

          if (!response.ok) {
            throw new Error(`GitHub download returned ${response.status}`)
          }

          const buffer = await response.arrayBuffer()

          const contentType = type === 'portable'
            ? 'application/x-msdownload'
            : 'application/vnd.android.package-archive'
          const filename = type === 'portable'
            ? 'saatiril-portable.exe'
            : 'saatiril-operator.apk'

          res.writeHead(200, {
            'Content-Type': contentType,
            'Content-Disposition': `attachment; filename="${filename}"`,
            'Content-Length': buffer.byteLength.toString(),
          })
          res.end(Buffer.from(buffer))
        })
        .catch((err: any) => {
          const message = err instanceof Error ? err.message : 'Download failed'
          console.error('[SAATIRIL] APK download proxy error:', message)
          res.writeHead(500, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify({ error: message }))
        })
    })
    return
  }

  // Method not allowed
  res.writeHead(405, { 'Content-Type': 'text/plain' })
  res.end('Method Not Allowed')
}

/**
 * Handle /api/generate-license requests
 * POST → Generates a license activation code (same logic as Next.js API route)
 */
function handleGenerateLicenseApi(
  req: import('http').IncomingMessage,
  res: import('http').ServerResponse,
) {
  if (req.method !== 'POST') {
    res.writeHead(405, { 'Content-Type': 'text/plain' })
    res.end('Method Not Allowed')
    return
  }

  let body = ''
  req.on('data', (chunk: Buffer) => { body += chunk.toString() })
  req.on('end', () => {
    try {
      const { machineId, adminKey } = JSON.parse(body)
      const result = generateLicenseCode(machineId, adminKey)
      res.writeHead(result.success ? 200 : 403, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify(result))
    } catch (err: any) {
      res.writeHead(500, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify({ success: false, error: 'Invalid request body' }))
    }
  })
}

// ─── Resource path resolution ──────────────────────────────────────────────
// With asar:true, files are inside resources/app.asar
//   __dirname → /path/to/resources/app.asar/electron
//   path.join(__dirname, '..', 'out') → /path/to/resources/app.asar/out
//   Electron's fs module reads transparently from asar archives.
// With asar:false (fallback), files are in resources/app/
function getResourcePath(relativePath: string): string {
  // Primary: files inside app (asar or directory)
  const appPath = path.join(__dirname, '..', relativePath)
  if (fs.existsSync(appPath)) {
    return appPath
  }
  // Fallback: extraResources at process.resourcesPath
  const extraPath = path.join(process.resourcesPath, relativePath)
  if (fs.existsSync(extraPath)) {
    return extraPath
  }
  return appPath
}

// ─── Static file server for Next.js export ─────────────────────────────────
let httpServer: ReturnType<typeof createServer> | null = null
let socketServer: SocketIOServer | null = null

// In-memory path cache to avoid repeated fs.existsSync/fs.statSync calls
const filePathCache = new Map<string, string | null>()

function startStaticServer(outDir: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const mimeTypes: Record<string, string> = {
      '.html': 'text/html',
      '.js': 'text/javascript',
      '.mjs': 'text/javascript',
      '.css': 'text/css',
      '.json': 'application/json',
      '.png': 'image/png',
      '.jpg': 'image/jpeg',
      '.jpeg': 'image/jpeg',
      '.gif': 'image/gif',
      '.svg': 'image/svg+xml',
      '.ico': 'image/x-icon',
      '.woff': 'font/woff',
      '.woff2': 'font/woff2',
      '.ttf': 'font/ttf',
      '.webp': 'image/webp',
      '.map': 'application/json',
    }

    // Pre-warm the cache: scan the outDir for all files at startup
    try {
      const scanDir = (dir: string, base: string) => {
        try {
          const entries = fs.readdirSync(dir, { withFileTypes: true })
          for (const entry of entries) {
            const fullPath = path.join(dir, entry.name)
            const relPath = '/' + path.relative(outDir, fullPath).replace(/\\/g, '/')
            if (entry.isDirectory()) {
              scanDir(fullPath, base)
            } else {
              filePathCache.set(relPath, fullPath)
              // Also cache without leading slash
              filePathCache.set(relPath.slice(1), fullPath)
            }
          }
        } catch { /* ignore scan errors for individual dirs */ }
      }
      scanDir(outDir, outDir)
      console.log(`[SAATIRIL] File path cache: ${filePathCache.size} entries`)
    } catch (err: any) {
      console.warn('[SAATIRIL] Failed to pre-cache file paths:', err.message)
    }

    // ── GitHub Release asset type ──────────────────────────────────
    interface GhAsset {
      name: string
      url: string
      size: number
      updated_at: string
      browser_download_url: string
    }
    interface GhRelease {
      assets: GhAsset[]
      published_at: string
    }

    httpServer = createServer((req, res) => {
      // ── CORS headers for all responses ──────────────────────────────────
      res.setHeader('Access-Control-Allow-Origin', '*')
      res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
      res.setHeader('Access-Control-Allow-Headers', 'Content-Type')

      // Handle OPTIONS preflight for API routes
      if (req.method === 'OPTIONS') {
        res.writeHead(200)
        res.end()
        return
      }

      let urlPath = req.url?.split('?')[0] || '/'
      const urlQuery = req.url?.split('?')[1] || ''

      // ── API Routes (dynamic — NOT in static export) ─────────────────────
      // These must be handled here because the static export doesn't include
      // Next.js API routes. The Electron portable version runs a simple
      // HTTP file server, so we intercept API requests and handle them directly.
      if (urlPath === '/api/apk-download') {
        handleApkDownloadApi(req, res, urlQuery)
        return
      }

      if (urlPath === '/api/generate-license') {
        handleGenerateLicenseApi(req, res)
        return
      }

      if (urlPath === '/') urlPath = '/index.html'

      // ── MC web page — served at /mc?channel=1 ─────────────────────────
      // MC scans QR code → browser opens this page → connects via Socket.io
      if (urlPath === '/mc') {
        const mcHtmlPath = getResourcePath('public/mc.html')
        if (fs.existsSync(mcHtmlPath)) {
          res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
          fs.createReadStream(mcHtmlPath).pipe(res)
          return
        }
      }

      // ── Operator web page — served at /operator?channel=1 ────────────
      // Operator scans QR code → browser opens this page → camera + shutter
      if (urlPath === '/operator') {
        const opHtmlPath = getResourcePath('public/operator.html')
        if (fs.existsSync(opHtmlPath)) {
          res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
          fs.createReadStream(opHtmlPath).pipe(res)
          return
        }
      }

      // ── MC BLE Remote web page — uses Web Bluetooth API ──────────────
      if (urlPath === '/mc-ble') {
        const mcBleHtmlPath = getResourcePath('public/mc-ble.html')
        if (fs.existsSync(mcBleHtmlPath)) {
          res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
          fs.createReadStream(mcBleHtmlPath).pipe(res)
          return
        }
      }

      // ── Admin BLE Client web page — Electron connects to MC-Only APK ──
      // Admin opens this → scans for MC HP (BLE Server) → connects
      if (urlPath === '/admin-ble') {
        const adminBleHtmlPath = getResourcePath('public/admin-ble.html')
        if (fs.existsSync(adminBleHtmlPath)) {
          res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
          fs.createReadStream(adminBleHtmlPath).pipe(res)
          return
        }
      }

      // ── API: BLE trigger from MC (via /admin-ble page) ──────────────
      // The admin-ble.html page forwards MC's PANGGIL/NEXT/RESET triggers
      // to this endpoint. We emit a 'BLE_TRIGGER' lan-message that the
      // admin dashboard handles by looking up the next student and
      // emitting a proper MC_CALL with the correct {student, channel} format.
      if (urlPath === '/api/ble-trigger' && req.method === 'POST') {
        let body = ''
        req.on('data', (chunk: Buffer) => { body += chunk.toString() })
        req.on('end', () => {
          try {
            const data = JSON.parse(body)
            console.log(`[SAATIRIL BLE] Trigger from MC: ${data.action} ${data.studentId || ''}`)
            // Emit BLE_TRIGGER event — admin dashboard handles this
            if (socketServer) {
              socketServer.emit('lan-message', {
                event: 'BLE_TRIGGER',
                data: { action: data.action, studentId: data.studentId }
              })
            }
            res.writeHead(200, { 'Content-Type': 'application/json' })
            res.end(JSON.stringify({ ok: true }))
          } catch (err: any) {
            res.writeHead(400, { 'Content-Type': 'application/json' })
            res.end(JSON.stringify({ error: err.message }))
          }
        })
        return
      }

      // ── API route: /api/apk-download ──────────────────────────────
      // Proxies APK/Portable downloads from GitHub Releases so LAN
      // operators can download even without direct internet access.
      if (urlPath === '/api/apk-download') {
        res.setHeader('Access-Control-Allow-Origin', '*')
        res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
        res.setHeader('Access-Control-Allow-Headers', 'Content-Type')

        if (req.method === 'OPTIONS') {
          res.writeHead(200)
          res.end()
          return
        }

        // GET: return release info (APK + Portable availability)
        if (req.method === 'GET') {
          const fetchReleaseInfo = async () => {
            try {
              const ghRes = await fetch(`https://api.github.com/repos/synclicen/Saatiril-Fullset/releases/tags/latest`, {
                headers: { Accept: 'application/vnd.github+json' },
              })
              if (!ghRes.ok) throw new Error(`GitHub API returned ${ghRes.status}`)
              const release = await ghRes.json() as GhRelease
              const assets: GhAsset[] = release.assets || []
              const apkAsset = assets.find((a) => a.name.endsWith('.apk'))
              const portableAsset = assets.find((a) => a.name.endsWith('-portable.exe') || a.name === 'saatiril-portable.exe')

              const toInfo = (a: GhAsset | undefined) =>
                a ? { available: true, sizeMB: (a.size / (1024 * 1024)).toFixed(1), assetName: a.name, lastModified: a.updated_at, downloadUrl: a.browser_download_url }
                  : { available: false, error: 'Not found in latest release' }

              res.writeHead(200, { 'Content-Type': 'application/json' })
              res.end(JSON.stringify({ apk: toInfo(apkAsset), portable: toInfo(portableAsset) }))
            } catch (err: any) {
              res.writeHead(200, { 'Content-Type': 'application/json' })
              res.end(JSON.stringify({ apk: { available: false, error: err.message }, portable: { available: false, error: err.message } }))
            }
          }
          fetchReleaseInfo()
          return
        }

        // POST: proxy download from GitHub Releases
        if (req.method === 'POST') {
          const proxyDownload = async () => {
            try {
              let body: any = {}
              try { body = await new Promise((resolve) => { let d = ''; req.on('data', (c: Buffer) => d += c); req.on('end', () => resolve(JSON.parse(d || '{}'))) }) } catch { body = {} }
              const type = body.type || 'apk'

              const ghRes = await fetch(`https://api.github.com/repos/synclicen/Saatiril-Fullset/releases/tags/latest`, {
                headers: { Accept: 'application/vnd.github+json' },
              })
              if (!ghRes.ok) throw new Error(`GitHub API returned ${ghRes.status}`)
              const release = await ghRes.json() as GhRelease
              const assets: GhAsset[] = release.assets || []
              const asset = type === 'portable'
                ? assets.find((a) => a.name.endsWith('-portable.exe') || a.name === 'saatiril-portable.exe')
                : assets.find((a) => a.name.endsWith('.apk'))

              if (!asset) {
                res.writeHead(404, { 'Content-Type': 'application/json' })
                res.end(JSON.stringify({ error: `${type} not available in latest release` }))
                return
              }

              // Download from GitHub and pipe to client
              const downloadRes = await fetch(asset.url, {
                headers: { Accept: 'application/octet-stream' },
                redirect: 'follow',
              })
              if (!downloadRes.ok) throw new Error(`GitHub download returned ${downloadRes.status}`)

              const buffer = Buffer.from(await downloadRes.arrayBuffer())
              const contentType = type === 'portable' ? 'application/x-msdownload' : 'application/vnd.android.package-archive'
              const filename = type === 'portable' ? 'saatiril-portable.exe' : 'saatiril-operator.apk'

              res.writeHead(200, {
                'Content-Type': contentType,
                'Content-Disposition': `attachment; filename="${filename}"`,
                'Content-Length': buffer.length.toString(),
              })
              res.end(buffer)
            } catch (err: any) {
              res.writeHead(500, { 'Content-Type': 'application/json' })
              res.end(JSON.stringify({ error: err.message }))
            }
          }
          proxyDownload()
          return
        }
      }

      // Check cache first (O(1) lookup instead of 3× fs.existsSync)
      const cachedPath = filePathCache.get(urlPath)
      if (cachedPath) {
        const ext = path.extname(cachedPath).toLowerCase()
        const contentType = mimeTypes[ext] || 'application/octet-stream'
        res.writeHead(200, { 'Content-Type': contentType })
        fs.createReadStream(cachedPath).pipe(res)
        return
      }

      // Cache miss: try with .html extension and /index.html for SPA routing
      const tryPaths = [
        urlPath + '.html',
        urlPath.endsWith('/') ? urlPath + 'index.html' : urlPath + '/index.html',
      ]

      for (const tryPath of tryPaths) {
        const cachedTry = filePathCache.get(tryPath)
        if (cachedTry) {
          const ext = path.extname(cachedTry).toLowerCase()
          const contentType = mimeTypes[ext] || 'application/octet-stream'
          res.writeHead(200, { 'Content-Type': contentType })
          fs.createReadStream(cachedTry).pipe(res)
          return
        }
      }

      // Final fallback to index.html for SPA routing
      const indexCached = filePathCache.get('/index.html')
      if (indexCached) {
        res.writeHead(200, { 'Content-Type': 'text/html' })
        fs.createReadStream(indexCached).pipe(res)
        return
      }

      res.writeHead(404, { 'Content-Type': 'text/plain' })
      res.end('Not Found')
    })

    httpServer.on('error', (err: any) => {
      if (err.code === 'EADDRINUSE') {
        console.warn(`[SAATIRIL] Port ${httpPort} in use, trying ${httpPort + 1}...`)
        httpPort++
        httpServer!.close()
        httpServer!.listen(httpPort, () => {
          console.log(`[SAATIRIL] Static file server running on http://localhost:${httpPort}`)
          resolve()
        })
      } else {
        console.error('[SAATIRIL] HTTP server error:', err.message)
        reject(err)
      }
    })

    httpServer.listen(httpPort, () => {
      console.log(`[SAATIRIL] Static file server running on http://localhost:${httpPort}`)
      resolve()
    })
  })
}

// ─── Socket.io Relay Server ────────────────────────────────────────────────
// Session password hash storage (shared with embedded server)
let sessionPasswordHash: string | null = null

/**
 * Broadcast auth-requirement to ALL connected clients.
 * Called when the session password is set or cleared by admin.
 * This ensures existing clients are notified about the auth requirement change.
 */
function broadcastAuthRequirement(server: SocketIOServer) {
  const payload = { passwordRequired: sessionPasswordHash !== null }
  server.emit('auth-requirement', payload)
  console.log(`[SAATIRIL] Broadcast auth-requirement: passwordRequired=${payload.passwordRequired}`)
}

function startSocketServer(): Promise<void> {
  return new Promise((resolve, reject) => {
    const httpForSocket = createServer()

    socketServer = new SocketIOServer(httpForSocket, {
      // Use default Socket.io path '/socket.io/' instead of '/'
      // This prevents conflict with HTTP routes and is more reliable
      path: '/socket.io/',
      cors: { origin: '*', methods: ['GET', 'POST'] },
      pingInterval: 5000,
      pingTimeout: 15000,
      maxHttpBufferSize: MAX_HTTP_BUFFER,
      connectionStateRecovery: { maxDisconnectionDuration: 5 * 60 * 1000 },
      transports: ['websocket', 'polling'],
      allowUpgrades: true,
      // CRITICAL: Allow Engine.IO v3 clients (Android APK + vanilla JS web pages)
      // Without this, the APK's raw WebSocket EIO3 clients CANNOT connect.
      allowEIO3: true,
    })

    // Connection limit
    const MAX_CONNECTIONS = 10
    const IDENTIFICATION_TIMEOUT_MS = 15000
    socketServer.use((socket, next) => {
      const current = socketServer!.sockets.sockets.size
      if (current >= MAX_CONNECTIONS) {
        next(new Error('Connection limit reached'))
        return
      }
      next()
    })

    // Client tracking — role can be 'unknown' | 'admin' | 'mc' | 'operator' | 'pending_auth'
    const clientRegistry = new Map<string, { role: string; channel: number }>()

    socketServer.on('connection', (socket) => {
      clientRegistry.set(socket.id, { role: 'unknown', channel: 0 })

      // Send auth requirement on connect
      socket.emit('auth-requirement', { passwordRequired: sessionPasswordHash !== null })

      // Identification timeout — disconnect if not identified within timeout
      // CRITICAL: 'pending_auth' clients are NOT disconnected — they're waiting
      // for the user to enter the session password. Only truly 'unknown' clients
      // (that never sent an identify at all) are disconnected.
      const identificationTimeout = setTimeout(() => {
        const info = clientRegistry.get(socket.id)
        if (info && info.role === 'unknown') {
          console.warn(`[SAATIRIL] Client ${socket.id} disconnected: failed to identify within ${IDENTIFICATION_TIMEOUT_MS}ms`)
          socket.disconnect(true)
        }
      }, IDENTIFICATION_TIMEOUT_MS)

      // Admin sets session password
      socket.on('SET_SESSION_PASSWORD', (data: { passwordHash: string }) => {
        const info = clientRegistry.get(socket.id)
        if (info && info.role === 'admin') {
          sessionPasswordHash = data.passwordHash
          console.log('[SAATIRIL] Session password set by admin — broadcasting to all clients')
          // Broadcast auth-requirement to ALL clients so they know password is now required
          broadcastAuthRequirement(socketServer!)
        }
      })

      // Admin clears session password
      socket.on('CLEAR_SESSION_PASSWORD', () => {
        const info = clientRegistry.get(socket.id)
        if (info && info.role === 'admin') {
          sessionPasswordHash = null
          console.log('[SAATIRIL] Session password cleared — broadcasting to all clients')
          // Broadcast auth-requirement to ALL clients so they know password is no longer required
          broadcastAuthRequirement(socketServer!)
        }
      })

      // Client identification with session password validation
      socket.on('identify', (data: { role: string; channel: number; sessionPasswordHash?: string }) => {
        const info = clientRegistry.get(socket.id)
        if (!info) return

        // Validate session password for non-admin when password is set
        if (data.role !== 'admin' && sessionPasswordHash) {
          if (!data.sessionPasswordHash || data.sessionPasswordHash !== sessionPasswordHash) {
            console.warn(`[SAATIRIL] Client ${socket.id} rejected: invalid session password (role: ${data.role})`)
            // CRITICAL FIX: Set role to 'pending_auth' instead of leaving as 'unknown'
            // This prevents the identification timeout from disconnecting the client.
            // The client stays connected and can retry with the correct password.
            info.role = 'pending_auth'
            info.channel = data.channel
            socket.emit('auth-failed', { reason: 'session_password_required' })
            return
          }
        }

        info.role = data.role
        info.channel = data.channel
        console.log(`[SAATIRIL] Client: ${socket.id} → ${data.role} Ch.${data.channel}`)
        socket.emit('auth-success', { role: data.role, channel: data.channel })
      })

      socket.on('saatiril-ping', (timestamp: number) => {
        socket.emit('saatiril-pong', timestamp)
      })

      socket.on('lan-message', (payload: { event: string; data: any }) => {
        const info = clientRegistry.get(socket.id)
        if (!info || info.role === 'unknown' || info.role === 'pending_auth') {
          // Only fully authenticated clients can relay messages
          if (info?.role === 'pending_auth') {
            console.warn(`[SAATIRIL] Pending-auth client ${socket.id} tried to relay message — ignoring (needs password)`)
          }
          return
        }
        socket.broadcast.emit('lan-message', payload)
      })

      socket.on('server-stats', (callback: (stats: any) => void) => {
        if (typeof callback === 'function') {
          callback({
            connectedClients: socketServer!.sockets.sockets.size,
            clients: Array.from(clientRegistry.values()),
            sessionPasswordActive: sessionPasswordHash !== null,
          })
        }
      })

      socket.on('disconnect', () => {
        clearTimeout(identificationTimeout)
        clientRegistry.delete(socket.id)
      })
    })

    httpForSocket.on('error', (err: any) => {
      if (err.code === 'EADDRINUSE') {
        console.warn(`[SAATIRIL] Socket port ${socketPort} in use, trying ${socketPort + 1}...`)
        socketPort++
        httpForSocket.close()
        httpForSocket.listen(socketPort, () => {
          console.log(`[SAATIRIL] Socket.io relay server running on port ${socketPort}`)
          resolve()
        })
      } else {
        console.error('[SAATIRIL] Socket server error:', err.message)
        reject(err)
      }
    })

    httpForSocket.listen(socketPort, () => {
      console.log(`[SAATIRIL] Socket.io relay server running on port ${socketPort}`)
      resolve()
    })
  })
}

// ─── Windows Firewall: allow incoming on HTTP + Socket ports ──────────────
// Without this, Windows Firewall blocks LAN clients from connecting to
// the HTTP server (port 3000) and Socket.io server (port 3003).
// We use `netsh advfirewall` to add a rule — requires admin privileges
// (Electron portable runs with user privileges by default, but netsh
// firewall add works without elevation for per-user rules in Windows 10+).
function addFirewallRule(port: number): void {
  if (process.platform !== 'win32') return
  const ruleName = `Saatiril Port ${port}`
  try {
    const { execSync } = require('child_process')
    // Check if rule already exists
    try {
      execSync(`netsh advfirewall firewall show rule name="${ruleName}"`, { stdio: 'pipe' })
      // Rule exists — skip
      return
    } catch {
      // Rule doesn't exist — create it
    }
    // Add firewall rule to allow inbound TCP on this port
    execSync(`netsh advfirewall firewall add rule name="${ruleName}" dir=in action=allow protocol=TCP localport=${port}`, { stdio: 'pipe' })
    console.log(`[SAATIRIL] Firewall rule added: ${ruleName} (TCP port ${port})`)
  } catch (err: any) {
    console.warn(`[SAATIRIL] Could not add firewall rule for port ${port}: ${err.message}`)
    console.warn(`[SAATIRIL] Manual fix: Run as admin, or add firewall rule manually:`)
    console.warn(`[SAATIRIL]   netsh advfirewall firewall add rule name="Saatiril Port ${port}" dir=in action=allow protocol=TCP localport=${port}`)
  }
}

// ─── Get LAN IP addresses ─────────────────────────────────────────────────
function getLanIPs(): { name: string; address: string }[] {
  const interfaces = os.networkInterfaces()
  const results: { name: string; address: string }[] = []

  for (const [name, nets] of Object.entries(interfaces)) {
    if (!nets) continue
    for (const net of nets) {
      // Skip internal and non-IPv4
      if (net.family === 'IPv4' && !net.internal) {
        results.push({ name, address: net.address })
      }
    }
  }

  return results
}

// ─── IPC Handlers ──────────────────────────────────────────────────────────
function registerIpcHandlers() {
  // Select folder dialog
  ipcMain.handle('select-folder', async (_event, defaultPath: string) => {
    const result = await dialog.showOpenDialog({
      defaultPath: defaultPath || undefined,
      properties: ['openDirectory', 'createDirectory'],
    })
    if (result.canceled) return null
    return result.filePaths[0] || null
  })

  // Create folder
  ipcMain.handle('create-folder', async (_event, folderPath: string) => {
    try {
      fs.mkdirSync(folderPath, { recursive: true })
      return { success: true, path: folderPath }
    } catch (err: any) {
      return { success: false, error: err.message }
    }
  })

  // Save photo to disk
  ipcMain.handle('save-photo', async (_event, data: { base64Data: string; filename: string; targetFolder: string }) => {
    try {
      const { base64Data, filename, targetFolder } = data

      // Ensure target folder exists
      fs.mkdirSync(targetFolder, { recursive: true })

      // Strip data URL prefix if present
      const base64 = base64Data.replace(/^data:image\/\w+;base64,/, '')
      const buffer = Buffer.from(base64, 'base64')

      const filePath = path.join(targetFolder, filename)
      fs.writeFileSync(filePath, buffer)

      console.log(`[SAATIRIL] Photo saved: ${filePath} (${(buffer.length / 1024).toFixed(1)}KB)`)

      // ── Google Drive backup (if configured) ──
      // Copies the saved photo to the backup folder (e.g. Google Drive desktop
      // folder at G:\My Drive\Saatiril\). Google Drive for Desktop auto-syncs
      // the file to the cloud. If the folder is not accessible (e.g. Drive
      // not running), the backup silently fails — the local copy is still safe.
      try {
        const backupFolder = getBackupFolder()
        if (backupFolder) {
          const backupPath = path.join(backupFolder, filename)
          fs.writeFileSync(backupPath, buffer)
          console.log(`[SAATIRIL] Photo backup: ${backupPath}`)
        }
      } catch (backupErr: any) {
        // Backup failure is non-fatal — local photo is already saved
        console.warn(`[SAATIRIL] Photo backup failed (non-fatal): ${backupErr.message}`)
      }

      return filePath
    } catch (err: any) {
      console.error('[SAATIRIL] Failed to save photo:', err.message)
      return null
    }
  })

  // ── Google Drive / cloud backup folder ────────────────────────────────
  // Admin picks a folder (e.g. G:\My Drive\Saatiril\ via Google Drive for
  // Desktop, or any cloud-synced folder). Photos are copied there after
  // saving locally. Cloud app handles the actual upload + retry.

  // Config file path for persisting backup folder setting
  const backupConfigPath = path.join(app.getPath('userData'), 'backup-config.json')

  function getBackupFolder(): string | null {
    try {
      if (!fs.existsSync(backupConfigPath)) return null
      const config = JSON.parse(fs.readFileSync(backupConfigPath, 'utf-8'))
      const folder = config.backupFolder
      if (!folder || !fs.existsSync(folder)) return null
      return folder
    } catch {
      return null
    }
  }

  function setBackupFolderInternal(folder: string | null) {
    try {
      const config = { backupFolder: folder }
      fs.writeFileSync(backupConfigPath, JSON.stringify(config, null, 2))
    } catch (err: any) {
      console.error('[SAATIRIL] Failed to save backup config:', err.message)
    }
  }

  // Select backup folder (native folder picker)
  ipcMain.handle('select-backup-folder', async () => {
    const result = await dialog.showOpenDialog({
      title: 'Pilih Folder Google Drive / Cloud Backup',
      properties: ['openDirectory', 'createDirectory'],
    })
    if (result.canceled) return null
    const folder = result.filePaths[0] || null
    if (folder) {
      setBackupFolderInternal(folder)
      console.log(`[SAATIRIL] Backup folder set: ${folder}`)
    }
    return folder
  })

  // Get current backup folder (or null if not set)
  ipcMain.handle('get-backup-folder', async () => {
    return getBackupFolder()
  })

  // Clear backup folder (disable backup)
  ipcMain.handle('clear-backup-folder', async () => {
    setBackupFolderInternal(null)
    console.log('[SAATIRIL] Backup folder cleared')
    return true
  })

  // Get backup stats: count .jpg files in backup folder
  ipcMain.handle('get-backup-stats', async () => {
    const folder = getBackupFolder()
    if (!folder) return { connected: false, totalFiles: 0 }
    try {
      const files = fs.readdirSync(folder).filter(f => f.endsWith('.jpg') || f.endsWith('.jpeg'))
      return { connected: true, totalFiles: files.length }
    } catch {
      return { connected: false, totalFiles: 0 }
    }
  })

  // Get LAN info
  ipcMain.handle('get-lan-info', async () => {
    return {
      httpPort,
      socketPort,
      ips: getLanIPs(),
    }
  })

  // ── License IPC handlers ──────────────────────────────────────────────

  // Get current license status
  ipcMain.handle('get-license-status', async () => {
    return checkLicenseStatus()
  })

  // Activate with an activation code
  ipcMain.handle('activate-license', async (_event, activationCode: string) => {
    return activateLicense(activationCode)
  })

  // Get Machine ID (full + display)
  ipcMain.handle('get-machine-id', async () => {
    const machineId = getMachineId()
    return {
      machineId,
      displayMachineId: getDisplayMachineId(machineId),
    }
  })

  // Generate license code (for admin/developer use)
  ipcMain.handle('generate-license-code', async (_event, machineId: string, adminKey: string) => {
    return generateLicenseCode(machineId, adminKey)
  })

  // ── Release info IPC ──────────────────────────────────────────────────
  // Fetches GitHub Release info from the main process (Node.js) to avoid
  // CORS / network issues when the renderer tries to call api.github.com
  // directly (which fails in portable Electron builds on some networks).
  ipcMain.handle('get-release-info', async () => {
    try {
      const ghRes = await fetch('https://api.github.com/repos/synclicen/Saatiril-Fullset/releases/tags/latest', {
        headers: { Accept: 'application/vnd.github+json' },
      })
      if (!ghRes.ok) throw new Error(`GitHub API returned ${ghRes.status}`)
      const release: any = await ghRes.json()
      const assets: any[] = release.assets || []

      const apkAsset = assets.find((a: any) => a.name.endsWith('.apk'))
      const portableAsset = assets.find((a: any) => a.name.endsWith('-portable.exe') || a.name === 'saatiril-portable.exe')

      const toInfo = (a: any) =>
        a ? { available: true, sizeMB: (a.size / (1024 * 1024)).toFixed(1), assetName: a.name, lastModified: a.updated_at, downloadUrl: a.browser_download_url }
          : { available: false, error: 'Not found in latest release' }

      return { apk: toInfo(apkAsset), portable: toInfo(portableAsset) }
    } catch (err: any) {
      return { apk: { available: false, error: err.message || 'GitHub API error' }, portable: { available: false, error: err.message || 'GitHub API error' } }
    }
  })
}

// ─── Splash/Loading Window ────────────────────────────────────────────────
let splashWindow: BrowserWindow | null = null

function createSplashWindow() {
  splashWindow = new BrowserWindow({
    width: 500,
    height: 350,
    frame: false,
    transparent: true,
    resizable: false,
    center: true,
    backgroundColor: '#00000000',
    alwaysOnTop: true,
    skipTaskbar: true,
    show: true,
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
    },
  })

  // Load a simple splash screen HTML
  splashWindow.loadURL(`data:text/html;charset=utf-8,${encodeURIComponent(splashHTML)}`)

  splashWindow.on('closed', () => {
    splashWindow = null
  })
}

const splashHTML = `
<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8">
  <style>
    * { margin: 0; padding: 0; box-sizing: border-box; }
    body {
      width: 500px;
      height: 350px;
      background: linear-gradient(135deg, #1a0b2e 0%, #2d1b69 50%, #1a0b2e 100%);
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      font-family: 'Segoe UI', system-ui, -apple-system, sans-serif;
      color: white;
      border-radius: 16px;
      overflow: hidden;
    }
    .logo {
      font-size: 42px;
      font-weight: 800;
      letter-spacing: 6px;
      margin-bottom: 8px;
      background: linear-gradient(90deg, #a78bfa, #7c3aed, #a78bfa);
      -webkit-background-clip: text;
      -webkit-text-fill-color: transparent;
      background-size: 200% auto;
      animation: shimmer 2s linear infinite;
    }
    .subtitle {
      font-size: 13px;
      color: rgba(255,255,255,0.6);
      letter-spacing: 2px;
      margin-bottom: 40px;
    }
    .loader {
      width: 40px;
      height: 40px;
      border: 3px solid rgba(255,255,255,0.15);
      border-top-color: #7c3aed;
      border-radius: 50%;
      animation: spin 0.8s linear infinite;
    }
    .status {
      margin-top: 20px;
      font-size: 12px;
      color: rgba(255,255,255,0.5);
      letter-spacing: 1px;
    }
    @keyframes spin { to { transform: rotate(360deg); } }
    @keyframes shimmer { to { background-position: 200% center; } }
  </style>
</head>
<body>
  <div class="logo">SAATIRIL</div>
  <div class="subtitle">MANAJEMEN ACARA FOTO</div>
  <div class="loader"></div>
  <div class="status">Mempersiapkan aplikasi...</div>
</body>
</html>
`

// ─── Create Electron Window ────────────────────────────────────────────────
let mainWindow: BrowserWindow | null = null
const MAX_LOAD_RETRIES = 5
const LOAD_RETRY_DELAY = 1500 // ms
let currentRetryCount = 0

function createWindow() {
  const iconPath = getResourcePath('public/logo.svg')
  // Fallback to .ico on Windows
  const finalIconPath = process.platform === 'win32'
    ? (fs.existsSync(getResourcePath('public/icon.ico')) ? getResourcePath('public/icon.ico') : iconPath)
    : iconPath

  mainWindow = new BrowserWindow({
    width: 1400,
    height: 900,
    minWidth: 800,
    minHeight: 600,
    title: 'SAATIRIL — Sistem Auto Track Input, Raw Into Live',
    icon: finalIconPath,
    backgroundColor: '#1a0b2e',
    show: false, // Show when ready
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
      webSecurity: false, // Allow camera access on HTTP LAN
    },
  })

  // Load Next.js app
  const socketPortParam = `socketPort=${socketPort}`
  const loadUrl = isDev
    ? `http://localhost:3000/?${socketPortParam}`
    : `http://localhost:${httpPort}/?${socketPortParam}`

  console.log(`[SAATIRIL] Loading URL: ${loadUrl}`)
  currentRetryCount = 0
  mainWindow.loadURL(loadUrl)

  // Show window when ready — close splash first
  mainWindow.once('ready-to-show', () => {
    // Close splash and show main window
    if (splashWindow && !splashWindow.isDestroyed()) {
      splashWindow.close()
    }
    mainWindow?.show()
    mainWindow?.focus()
  })

  // Open DevTools in dev mode
  if (isDev) {
    mainWindow.webContents.openDevTools()
  }

  // Handle page load failure with retry logic
  mainWindow.webContents.on('did-fail-load', (_event, errorCode, errorDesc, validatedURL) => {
    console.error(`[SAATIRIL] Page load failed: ${errorCode} - ${errorDesc} (URL: ${validatedURL})`)
    // Only retry if it's a connection error (server not ready yet)
    if (errorCode === -102 || errorCode === -101 || errorCode === -105) {
      // ERR_CONNECTION_REFUSED, ERR_CONNECTION_RESET, ERR_NAME_NOT_RESOLVED
      currentRetryCount++
      if (currentRetryCount <= MAX_LOAD_RETRIES) {
        console.log(`[SAATIRIL] Retrying page load in ${LOAD_RETRY_DELAY}ms (attempt ${currentRetryCount}/${MAX_LOAD_RETRIES})...`)
        setTimeout(() => {
          if (mainWindow && !mainWindow.isDestroyed()) {
            mainWindow.loadURL(loadUrl)
          }
        }, LOAD_RETRY_DELAY)
      } else {
        console.error(`[SAATIRIL] Failed to load app after ${MAX_LOAD_RETRIES} retries. Giving up.`)
      }
    }
  })

  // Open external links in system browser
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url)
    return { action: 'deny' }
  })

  mainWindow.on('closed', () => {
    mainWindow = null
  })
}

// ─── App lifecycle ─────────────────────────────────────────────────────────
app.whenReady().then(async () => {
  const startTime = Date.now()
  console.log('[SAATIRIL] ═══════════════════════════════════════════════════════════')
  console.log('[SAATIRIL]  SAATIRIL Electron App Starting...')
  console.log(`[SAATIRIL]  Version: ${app.getVersion()}`)
  console.log(`[SAATIRIL]  isDev: ${isDev}`)
  console.log(`[SAATIRIL]  isPackaged: ${app.isPackaged}`)
  console.log(`[SAATIRIL]  __dirname: ${__dirname}`)
  console.log(`[SAATIRIL]  resourcesPath: ${process.resourcesPath}`)
  console.log('[SAATIRIL] ═══════════════════════════════════════════════════════════')

  // Register IPC handlers
  registerIpcHandlers()

  // ── Add Windows Firewall rules for ports 3000 and 3003 ──
  // This allows LAN clients (MC/Operator) to connect from other devices.
  // Without this, Windows Firewall blocks incoming connections by default.
  addFirewallRule(DEFAULT_HTTP_PORT)
  addFirewallRule(DEFAULT_SOCKET_PORT)

  // Show splash screen immediately — gives visual feedback during startup
  createSplashWindow()

  // Start servers (only in production; in dev they run separately)
  if (!isDev) {
    const outDir = getResourcePath('out')
    console.log(`[SAATIRIL] Looking for static export at: ${outDir}`)

    if (fs.existsSync(outDir)) {
      const fileCount = fs.readdirSync(outDir).length
      console.log(`[SAATIRIL] Static export found (${fileCount} top-level items)`)
    } else {
      console.error('[SAATIRIL] ❌ No static export found at', outDir)
    }

    // Start both servers IN PARALLEL instead of sequentially
    // This saves 2-5 seconds of startup time
    // Also pre-warm GitHub Releases cache in the background so APK info
    // is available by the time the admin dashboard loads
    try {
      await Promise.all([
        fs.existsSync(outDir)
          ? startStaticServer(outDir).then(() => { console.log('[SAATIRIL] HTTP server is ready.') })
          : Promise.reject(new Error('No out/ directory')),
        startSocketServer().then(() => { console.log('[SAATIRIL] Socket server is ready.') }),
      ])
      // Pre-warm GitHub Releases cache (non-blocking — don't wait for it)
      fetchLatestReleaseInfo().then(() => {
        console.log('[SAATIRIL] GitHub Releases cache pre-warmed.')
      }).catch(() => {
        console.warn('[SAATIRIL] GitHub Releases cache pre-warm failed (will retry on first request).')
      })
    } catch (err: any) {
      console.error('[SAATIRIL] Server startup error:', err.message)
    }
  }

  // Create main window (servers are now ready, page will load successfully)
  createWindow()

  const elapsed = Date.now() - startTime
  console.log(`[SAATIRIL] Startup completed in ${elapsed}ms`)

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow()
    }
  })
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit()
  }
})

app.on('before-quit', () => {
  console.log('[SAATIRIL] App shutting down...')
  httpServer?.close()
  socketServer?.close()
})
