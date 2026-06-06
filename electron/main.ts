/**
 * SAATIRIL — Electron Main Process
 *
 * This is the entry point for the Electron desktop application.
 * It starts:
 * 1. Next.js static export server (HTTP on port 3000)
 * 2. Socket.io relay server (on port 3003)
 * 3. Electron BrowserWindow loading the Next.js app
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
const HTTP_PORT = 3000
const SOCKET_PORT = 3003
const MAX_HTTP_BUFFER = 20e6 // 20MB for large photo payloads
const isDev = process.env.SAATIRIL_DEV === '1'

// ─── Static file server for Next.js export ─────────────────────────────────
let httpServer: ReturnType<typeof createServer> | null = null
let socketServer: SocketIOServer | null = null

function startStaticServer(outDir: string) {
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
  }

  httpServer = createServer((req, res) => {
    let urlPath = req.url?.split('?')[0] || '/'
    if (urlPath === '/') urlPath = '/index.html'

    // Try exact path first, then with .html, then /index.html for SPA-like routing
    const tryPaths = [
      path.join(outDir, urlPath),
      path.join(outDir, urlPath + '.html'),
      path.join(outDir, urlPath, 'index.html'),
    ]

    for (const filePath of tryPaths) {
      if (fs.existsSync(filePath) && fs.statSync(filePath).isFile()) {
        const ext = path.extname(filePath).toLowerCase()
        const contentType = mimeTypes[ext] || 'application/octet-stream'

        // Set CORS headers for LAN access
        res.setHeader('Access-Control-Allow-Origin', '*')
        res.setHeader('Access-Control-Allow-Methods', 'GET, OPTIONS')
        res.setHeader('Access-Control-Allow-Headers', 'Content-Type')

        if (req.method === 'OPTIONS') {
          res.writeHead(200)
          res.end()
          return
        }

        res.writeHead(200, { 'Content-Type': contentType })
        fs.createReadStream(filePath).pipe(res)
        return
      }
    }

    // Fallback to index.html for SPA routing
    const indexPath = path.join(outDir, 'index.html')
    if (fs.existsSync(indexPath)) {
      res.writeHead(200, { 'Content-Type': 'text/html' })
      fs.createReadStream(indexPath).pipe(res)
      return
    }

    res.writeHead(404, { 'Content-Type': 'text/plain' })
    res.end('Not Found')
  })

  httpServer.listen(HTTP_PORT, () => {
    console.log(`[SAATIRIL] Static file server running on http://localhost:${HTTP_PORT}`)
  })
}

// ─── Socket.io Relay Server ────────────────────────────────────────────────
function startSocketServer() {
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

  httpForSocket.listen(SOCKET_PORT, () => {
    console.log(`[SAATIRIL] Socket.io relay server running on port ${SOCKET_PORT}`)
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
      httpPort: HTTP_PORT,
      socketPort: SOCKET_PORT,
      ips: getLanIPs(),
    }
  })
}

// ─── Create Electron Window ────────────────────────────────────────────────
let mainWindow: BrowserWindow | null = null

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1400,
    height: 900,
    minWidth: 800,
    minHeight: 600,
    title: 'SAATIRIL — Manajemen Acara Foto',
    icon: path.join(__dirname, '../public/logo.svg'),
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
  const socketPortParam = `socketPort=${SOCKET_PORT}`
  const loadUrl = isDev
    ? `http://localhost:3000/?${socketPortParam}`
    : `http://localhost:${HTTP_PORT}/?${socketPortParam}`

  mainWindow.loadURL(loadUrl)

  // Show window when ready
  mainWindow.once('ready-to-show', () => {
    mainWindow?.show()
    mainWindow?.focus()
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
app.whenReady().then(() => {
  console.log('[SAATIRIL] ═══════════════════════════════════════════════════════════')
  console.log('[SAATIRIL]  SAATIRIL Electron App Starting...')
  console.log('[SAATIRIL] ═══════════════════════════════════════════════════════════')

  // Register IPC handlers
  registerIpcHandlers()

  // Start servers (only in production; in dev they run separately)
  if (!isDev) {
    const outDir = path.join(__dirname, '../out')
    if (fs.existsSync(outDir)) {
      startStaticServer(outDir)
    } else {
      console.warn('[SAATIRIL] No static export found at', outDir)
    }
    startSocketServer()
  }

  createWindow()

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
