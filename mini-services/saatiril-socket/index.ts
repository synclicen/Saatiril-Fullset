/**
 * SAATIRIL — Socket.io Relay Server (Production-Grade)
 *
 * Designed for stability during graduation ceremonies with thousands of participants.
 *
 * Key design decisions:
 * 1. Only 3-5 clients total (Admin, MC×1-2, Operator×1-2) — NOT thousands
 * 2. Photo payloads are large (base64 JPEG ~1-3MB each) but infrequent (~10 seconds per pair)
 * 3. LAN-only network — low latency, high reliability
 * 4. Server is stateless relay — all state lives in Zustand stores on clients
 * 5. Admin is the source of truth — others sync from Admin via REQUEST_STATE/SYNC_DB
 * 6. Session password enforcement — non-admin clients must provide correct password hash
 */

import { createServer } from 'http'
import { Server, Socket } from 'socket.io'
import crypto from 'crypto'

// ─── Configuration ─────────────────────────────────────────────────────────
const PORT = 3003
const MAX_HTTP_BUFFER = 20e6 // 20MB — supports dual-channel photo bursts (4 × ~3MB base64)
const PING_INTERVAL = 10000  // 10s — faster detection of disconnected clients (was 15s)
const PING_TIMEOUT = 20000   // 20s — generous timeout for LAN (was 30s)
const MAX_CONNECTIONS = 10   // Max concurrent clients (admin + 2×MC + 2×OP + buffer)
const IDENTIFICATION_TIMEOUT_MS = 10000 // 10s — clients must identify within this time

// ─── Health tracking ───────────────────────────────────────────────────────
let totalMessagesRelayed = 0
let totalConnections = 0
let startTime = Date.now()

// ─── Session password storage ──────────────────────────────────────────────
let sessionPasswordHash: string | null = null  // SHA-256 hash of the session password

function getUptime(): string {
  const seconds = Math.floor((Date.now() - startTime) / 1000)
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = seconds % 60
  return `${h}h ${m}m ${s}s`
}

// ─── HTTP server + Socket.io ───────────────────────────────────────────────
const httpServer = createServer()

// Health check endpoint for monitoring
httpServer.on('request', (req, res) => {
  // Only handle GET /health — everything else goes to Socket.io
  if (req.method === 'GET' && req.url === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify({
      status: 'ok',
      uptime: getUptime(),
      connectedClients: io.sockets.sockets.size,
      totalConnections,
      totalMessagesRelayed,
      maxConnections: MAX_CONNECTIONS,
      sessionPasswordActive: sessionPasswordHash !== null,
    }))
    return
  }
  // Let Socket.io handle everything else
})

const io = new Server(httpServer, {
  path: '/',
  cors: {
    origin: '*',
    methods: ['GET', 'POST'],
  },
  pingInterval: PING_INTERVAL,
  pingTimeout: PING_TIMEOUT,
  maxHttpBufferSize: MAX_HTTP_BUFFER,
  // CRITICAL: Allow Engine.IO v3 clients (Android socket.io-client 2.x)
  // Without this, the Android APK CANNOT connect because
  // socket.io-client:2.1.0 uses Engine.IO v3 protocol while
  // socket.io v4 server defaults to Engine.IO v4.
  allowEIO3: true,
  // Connection state recovery — if client reconnects within 2min,
  // it gets missed events automatically
  connectionStateRecovery: {
    maxDisconnectionDuration: 2 * 60 * 1000, // 2 minutes
  },
  // Transport order: websocket first (lower latency), polling as fallback
  transports: ['websocket', 'polling'],
  // Allow upgrading from polling to websocket
  allowUpgrades: true,
})

// ─── Connection limit middleware ────────────────────────────────────────────
io.use((socket, next) => {
  const currentConnections = io.sockets.sockets.size
  if (currentConnections >= MAX_CONNECTIONS) {
    console.warn(`[SAATIRIL] Connection rejected: limit reached (${currentConnections}/${MAX_CONNECTIONS}) — socket ${socket.id}`)
    next(new Error('Connection limit reached'))
    return
  }
  next()
})

// ─── Client tracking ───────────────────────────────────────────────────────
interface ClientInfo {
  id: string
  role: string
  channel: number
  connectedAt: number
  lastActivity: number
  messagesRelayed: number
}

const clientRegistry = new Map<string, ClientInfo>()

// ─── Connection handler ────────────────────────────────────────────────────
io.on('connection', (socket: Socket) => {
  totalConnections++
  const connectedAt = Date.now()

  // Register client
  clientRegistry.set(socket.id, {
    id: socket.id,
    role: 'unknown',
    channel: 0,
    connectedAt,
    lastActivity: connectedAt,
    messagesRelayed: 0,
  })

  console.log(`[SAATIRIL] Client connected: ${socket.id} (total: ${io.sockets.sockets.size}, all-time: ${totalConnections})`)

  // ── Send auth requirement on connect ──────────────────────────────────
  socket.emit('auth-requirement', {
    passwordRequired: sessionPasswordHash !== null,
  })

  // ── Identification timeout — disconnect if not identified within 30s ───
  // Extended from 10s to 30s to give non-admin clients time to enter
  // the session password before being disconnected.
  const identificationTimeout = setTimeout(() => {
    const info = clientRegistry.get(socket.id)
    if (info && info.role === 'unknown') {
      console.warn(`[SAATIRIL] Client ${socket.id} disconnected: failed to identify within 30s`)
      socket.disconnect(true)
    }
  }, 30000)

  // ── SET_SESSION_PASSWORD — admin sets the session password ─────────────
  socket.on('SET_SESSION_PASSWORD', (data: { passwordHash: string }) => {
    const info = clientRegistry.get(socket.id)
    if (info && info.role === 'admin') {
      sessionPasswordHash = data.passwordHash
      console.log('[SAATIRIL] Session password set by admin — broadcasting to all clients')
      // Notify ALL clients (including admin) that a password is now required.
      // Using io.emit instead of socket.broadcast.emit ensures every client
      // receives the notification, including clients that may have connected
      // before the password was set.
      io.emit('auth-requirement', { passwordRequired: true })
    }
  })

  // ── CLEAR_SESSION_PASSWORD — admin clears the session password ─────────
  socket.on('CLEAR_SESSION_PASSWORD', () => {
    const info = clientRegistry.get(socket.id)
    if (info && info.role === 'admin') {
      sessionPasswordHash = null
      console.log('[SAATIRIL] Session password cleared — broadcasting to all clients')
      // Notify ALL clients that password is no longer required
      io.emit('auth-requirement', { passwordRequired: false })
    }
  })

  // ── Client identification with session password validation ────────────
  socket.on('identify', (data: { role: string; channel: number; sessionPasswordHash?: string }) => {
    const info = clientRegistry.get(socket.id)
    if (!info) return

    // Validate session password for non-admin when password is set
    if (data.role !== 'admin' && sessionPasswordHash) {
      if (!data.sessionPasswordHash || data.sessionPasswordHash !== sessionPasswordHash) {
        console.warn(`[SAATIRIL] Client ${socket.id} rejected: invalid session password (role: ${data.role})`)
        // Mark as 'auth-pending' so the identification timeout doesn't disconnect them
        // while they're entering the password in the prompt
        info.role = 'auth-pending'
        info.channel = data.channel
        // Send BOTH auth-failed AND auth-requirement to ensure the client
        // shows the password prompt. The auth-requirement event is critical
        // because the client may have missed the initial one sent on connection
        // (e.g., if the React component mounted after the event was dispatched).
        socket.emit('auth-failed', { reason: 'session_password_required' })
        socket.emit('auth-requirement', { passwordRequired: true })
        return  // Don't fully register the client
      }
    }

    info.role = data.role
    info.channel = data.channel
    console.log(`[SAATIRIL] Client identified: ${socket.id} → ${data.role} Ch.${data.channel}`)

    // Notify client that auth succeeded
    socket.emit('auth-success', { role: data.role, channel: data.channel })
  })

  // ── Ping/pong for latency measurement ────────────────────────────────────
  socket.on('saatiril-ping', (timestamp: number) => {
    socket.emit('saatiril-pong', timestamp)
  })

  // ── Relay LAN messages between clients ──────────────────────────────────
  socket.on('lan-message', (payload: { event: string; data: any }) => {
    const info = clientRegistry.get(socket.id)
    if (!info || info.role === 'unknown' || info.role === 'auth-pending') {
      console.warn(`[SAATIRIL] Unidentified/unauthenticated client ${socket.id} (role: ${info?.role}) tried to relay message — ignoring`)
      return
    }

    // Update activity tracking
    info.lastActivity = Date.now()
    info.messagesRelayed++
    totalMessagesRelayed++

    // Broadcast to all OTHER clients (not back to sender)
    socket.broadcast.emit('lan-message', payload)

    // Log critical events for debugging
    const criticalEvents = ['PHOTOS_SAVED', 'MC_CALL', 'SYNC_DB', 'STUDENT_DONE']
    if (criticalEvents.includes(payload.event)) {
      console.log(`[SAATIRIL] Relay: ${payload.event} from ${socket.id} to ${io.sockets.sockets.size - 1} clients`)
    }
  })

  // ── Disconnect ──────────────────────────────────────────────────────────
  socket.on('disconnect', (reason) => {
    clearTimeout(identificationTimeout)
    const info = clientRegistry.get(socket.id)
    const duration = info ? Math.round((Date.now() - info.connectedAt) / 1000) : 0
    console.log(
      `[SAATIRIL] Client disconnected: ${socket.id} (role: ${info?.role ?? 'unknown'}, ` +
      `duration: ${duration}s, reason: ${reason}, remaining: ${io.sockets.sockets.size - 1})`
    )
    clientRegistry.delete(socket.id)
  })

  // ── Error handling ──────────────────────────────────────────────────────
  socket.on('error', (error: Error) => {
    console.error(`[SAATIRIL] Socket error (${socket.id}):`, error.message)
  })

  // ── Send server stats on request ────────────────────────────────────────
  socket.on('server-stats', (callback: (stats: any) => void) => {
    if (typeof callback === 'function') {
      callback({
        uptime: getUptime(),
        connectedClients: io.sockets.sockets.size,
        totalConnections,
        totalMessagesRelayed,
        sessionPasswordActive: sessionPasswordHash !== null,
        clients: Array.from(clientRegistry.values()).map(c => ({
          role: c.role,
          channel: c.channel,
          duration: Math.round((Date.now() - c.connectedAt) / 1000),
          messagesRelayed: c.messagesRelayed,
        })),
      })
    }
  })
})

// ─── Periodic health log (every 5 minutes) ─────────────────────────────────
setInterval(() => {
  const clientCount = io.sockets.sockets.size
  console.log(
    `[SAATIRIL] Health: ${clientCount} clients, ${totalMessagesRelayed} messages relayed, ` +
    `uptime: ${getUptime()}, password: ${sessionPasswordHash ? 'active' : 'none'}`
  )
  if (clientCount > 0) {
    for (const [id, info] of clientRegistry) {
      console.log(`  → ${id.slice(0, 8)}: ${info.role} Ch.${info.channel}, ${info.messagesRelayed} msgs, ${Math.round((Date.now() - info.connectedAt) / 1000)}s`)
    }
  }
}, 5 * 60 * 1000)

// ─── Start server ──────────────────────────────────────────────────────────
httpServer.listen(PORT, '0.0.0.0', () => {
  console.log(`[SAATIRIL] ═══════════════════════════════════════════════════════════`)
  console.log(`[SAATIRIL]  Socket.io Relay Server — PRODUCTION GRADE`)
  console.log(`[SAATIRIL]  Port: ${PORT}`)
  console.log(`[SAATIRIL]  Max connections: ${MAX_CONNECTIONS}`)
  console.log(`[SAATIRIL]  Max payload: ${MAX_HTTP_BUFFER / 1e6}MB`)
  console.log(`[SAATIRIL]  Ping: interval=${PING_INTERVAL}ms timeout=${PING_TIMEOUT}ms`)
  console.log(`[SAATIRIL]  Identification timeout: ${IDENTIFICATION_TIMEOUT_MS}ms`)
  console.log(`[SAATIRIL]  Session password enforcement: ENABLED`)
  console.log(`[SAATIRIL]  Health check: http://localhost:${PORT}/health`)
  console.log(`[SAATIRIL] ═══════════════════════════════════════════════════════════`)
})

// ─── Graceful shutdown ─────────────────────────────────────────────────────
function gracefulShutdown(signal: string) {
  console.log(`[SAATIRIL] Received ${signal}, shutting down gracefully...`)
  console.log(`[SAATIRIL] Final stats: ${totalMessagesRelayed} messages, ${totalConnections} total connections, uptime: ${getUptime()}`)

  // Notify all clients before shutting down
  io.emit('lan-message', {
    event: 'SERVER_SHUTDOWN',
    data: { reason: signal, timestamp: Date.now() },
  })

  // Give clients 2 seconds to receive the shutdown notification
  setTimeout(() => {
    io.close(() => {
      console.log('[SAATIRIL] All connections closed')
      httpServer.close(() => {
        console.log('[SAATIRIL] HTTP server closed')
        process.exit(0)
      })
    })
  }, 2000)
}

process.on('SIGTERM', () => gracefulShutdown('SIGTERM'))
process.on('SIGINT', () => gracefulShutdown('SIGINT'))

// ─── Uncaught error handling — prevent crashes ─────────────────────────────
process.on('uncaughtException', (error) => {
  console.error('[SAATIRIL] UNCAUGHT EXCEPTION (server stays alive):', error.message)
  // Don't exit — keep the server running for the ceremony!
})

process.on('unhandledRejection', (reason) => {
  console.error('[SAATIRIL] UNHANDLED REJECTION (server stays alive):', reason)
  // Don't exit — keep the server running for the ceremony!
})
