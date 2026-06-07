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
 */

import { app, BrowserWindow, ipcMain, dialog, shell } from 'electron'
import * as path from 'path'
import * as fs from 'fs'
import * as os from 'os'
import { createServer } from 'http'
import { Server as SocketIOServer } from 'socket.io'

// ─── Configuration ─────────────────────────────────────────────────────────
const DEFAULT_HTTP_PORT = 3000
const DEFAULT_SOCKET_PORT = 3003
const MAX_HTTP_BUFFER = 20e6 // 20MB for large photo payloads
const isDev = process.env.SAATIRIL_DEV === '1'

// Actual ports (may differ from defaults if ports are in use)
let httpPort = DEFAULT_HTTP_PORT
let socketPort = DEFAULT_SOCKET_PORT

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

    httpServer = createServer((req, res) => {
      let urlPath = req.url?.split('?')[0] || '/'
      if (urlPath === '/') urlPath = '/index.html'

      // Check cache first (O(1) lookup instead of 3× fs.existsSync)
      const cachedPath = filePathCache.get(urlPath)
      if (cachedPath) {
        const ext = path.extname(cachedPath).toLowerCase()
        const contentType = mimeTypes[ext] || 'application/octet-stream'

        res.setHeader('Access-Control-Allow-Origin', '*')
        res.setHeader('Access-Control-Allow-Methods', 'GET, OPTIONS')
        res.setHeader('Access-Control-Allow-Headers', 'Content-Type')

        if (req.method === 'OPTIONS') {
          res.writeHead(200)
          res.end()
          return
        }

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

          res.setHeader('Access-Control-Allow-Origin', '*')
          res.setHeader('Access-Control-Allow-Methods', 'GET, OPTIONS')
          res.setHeader('Access-Control-Allow-Headers', 'Content-Type')

          if (req.method === 'OPTIONS') {
            res.writeHead(200)
            res.end()
            return
          }

          res.writeHead(200, { 'Content-Type': contentType })
          fs.createReadStream(cachedTry).pipe(res)
          return
        }
      }

      // Final fallback to index.html for SPA routing
      const indexCached = filePathCache.get('/index.html')
      if (indexCached) {
        res.setHeader('Access-Control-Allow-Origin', '*')
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
function startSocketServer(): Promise<void> {
  return new Promise((resolve, reject) => {
    const httpForSocket = createServer()

    socketServer = new SocketIOServer(httpForSocket, {
      path: '/',
      cors: { origin: '*', methods: ['GET', 'POST'] },
      pingInterval: 10000,
      pingTimeout: 20000,
      maxHttpBufferSize: MAX_HTTP_BUFFER,
      connectionStateRecovery: { maxDisconnectionDuration: 2 * 60 * 1000 },
      transports: ['websocket', 'polling'],
      allowUpgrades: true,
    })

    // Connection limit
    const MAX_CONNECTIONS = 10
    socketServer.use((socket, next) => {
      const current = socketServer!.sockets.sockets.size
      if (current >= MAX_CONNECTIONS) {
        next(new Error('Connection limit reached'))
        return
      }
      next()
    })

    // Client tracking
    const clientRegistry = new Map<string, { role: string; channel: number }>()

    socketServer.on('connection', (socket) => {
      clientRegistry.set(socket.id, { role: 'unknown', channel: 0 })

      socket.on('identify', (data: { role: string; channel: number }) => {
        const info = clientRegistry.get(socket.id)
        if (info) {
          info.role = data.role
          info.channel = data.channel
        }
        console.log(`[SAATIRIL] Client: ${socket.id} → ${data.role} Ch.${data.channel}`)
      })

      socket.on('saatiril-ping', (timestamp: number) => {
        socket.emit('saatiril-pong', timestamp)
      })

      socket.on('lan-message', (payload: { event: string; data: any }) => {
        socket.broadcast.emit('lan-message', payload)
      })

      socket.on('server-stats', (callback: (stats: any) => void) => {
        if (typeof callback === 'function') {
          callback({
            connectedClients: socketServer!.sockets.sockets.size,
            clients: Array.from(clientRegistry.values()),
          })
        }
      })

      socket.on('disconnect', () => {
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
      return filePath
    } catch (err: any) {
      console.error('[SAATIRIL] Failed to save photo:', err.message)
      return null
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
    title: 'SAATIRIL — Manajemen Acara Foto',
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
    try {
      await Promise.all([
        fs.existsSync(outDir)
          ? startStaticServer(outDir).then(() => { console.log('[SAATIRIL] HTTP server is ready.') })
          : Promise.reject(new Error('No out/ directory')),
        startSocketServer().then(() => { console.log('[SAATIRIL] Socket server is ready.') }),
      ])
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
