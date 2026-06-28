'use client'

import { io, Socket } from 'socket.io-client'

// ─── Types ────────────────────────────────────────────────────────────────
export type LocalNetworkCallback = (data: any) => void

// ─── Module-level state ───────────────────────────────────────────────────
let socket: Socket | null = null
const listeners: Record<string, LocalNetworkCallback[]> = {}

// ─── Session password (hash) ───────────────────────────────────────────────
// Stored locally so we can re-identify on reconnect
let currentSessionPasswordHash: string | null = null

// ─── Pending session password (plaintext) ──────────────────────────────────
// When the admin sets a password before the socket is connected, we store it
// here and send it once the socket connects.
let pendingSessionPassword: string | null = null

// ─── Auth state ─────────────────────────────────────────────────────────────
let isAuthenticated: boolean = false
let authRequiredByServer: boolean = false

// ─── Auth state snapshot ──────────────────────────────────────────────────
// Exposed via getAuthState() so that components mounting after socket
// connection can read the current auth state without missing events.
export interface AuthState {
  connected: boolean
  authenticated: boolean
  passwordRequired: boolean
}

export function getAuthState(): AuthState {
  return {
    connected: socket?.connected ?? false,
    authenticated: isAuthenticated,
    passwordRequired: authRequiredByServer,
  }
}

// ─── SHA-256 hash helper ────────────────────────────────────────────────────
// Uses crypto.subtle when available (secure contexts / HTTPS / localhost).
// Falls back to a pure JS implementation for insecure HTTP LAN contexts
// (e.g., http://192.168.x.x:3000) where crypto.subtle is not available.
async function sha256(text: string): Promise<string> {
  // Try native crypto.subtle first (fast, secure)
  if (typeof crypto !== 'undefined' && crypto.subtle) {
    try {
      const encoder = new TextEncoder()
      const data = encoder.encode(text)
      const hashBuffer = await crypto.subtle.digest('SHA-256', data)
      const hashArray = Array.from(new Uint8Array(hashBuffer))
      return hashArray.map(b => b.toString(16).padStart(2, '0')).join('')
    } catch (err) {
      console.warn('[SAATIRIL] crypto.subtle.digest failed, using JS fallback:', err)
    }
  }

  // Pure JS SHA-256 fallback for insecure HTTP contexts
  // (http://192.168.x.x — crypto.subtle unavailable)
  return sha256JS(text)
}

// ─── Pure JS SHA-256 (fallback for HTTP LAN) ────────────────────────────────
// Tested against known test vectors to match crypto.subtle output exactly.
function sha256JS(msg: string): string {
  // Precompute round constants
  const K = new Uint32Array([
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
  ])

  // Encode string to bytes (UTF-8)
  const bytes: number[] = []
  for (let i = 0; i < msg.length; i++) {
    const c = msg.charCodeAt(i)
    if (c < 0x80) {
      bytes.push(c)
    } else if (c < 0x800) {
      bytes.push(0xc0 | (c >> 6), 0x80 | (c & 0x3f))
    } else {
      bytes.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 0x3f), 0x80 | (c & 0x3f))
    }
  }

  // Pre-processing: pad message
  const bitLen = bytes.length * 8
  bytes.push(0x80)
  while ((bytes.length % 64) !== 56) bytes.push(0)
  // Append length as 64-bit big-endian (we only use low 32 bits for JS string lengths)
  bytes.push(0, 0, 0, 0)
  bytes.push((bitLen >>> 24) & 0xff, (bitLen >>> 16) & 0xff, (bitLen >>> 8) & 0xff, bitLen & 0xff)

  // Initial hash values
  let h0 = 0x6a09e667, h1 = 0xbb67ae85, h2 = 0x3c6ef372, h3 = 0xa54ff53a
  let h4 = 0x510e527f, h5 = 0x9b05688c, h6 = 0x1f83d9ab, h7 = 0x5be0cd19

  // Process each 512-bit (64-byte) block
  const w = new Uint32Array(64)
  for (let offset = 0; offset < bytes.length; offset += 64) {
    // Create message schedule
    for (let t = 0; t < 16; t++) {
      const i = offset + t * 4
      w[t] = (bytes[i] << 24) | (bytes[i + 1] << 16) | (bytes[i + 2] << 8) | bytes[i + 3]
    }
    for (let t = 16; t < 64; t++) {
      const s0 = rotr(w[t - 15], 7) ^ rotr(w[t - 15], 18) ^ (w[t - 15] >>> 3)
      const s1 = rotr(w[t - 2], 17) ^ rotr(w[t - 2], 19) ^ (w[t - 2] >>> 10)
      w[t] = (w[t - 16] + s0 + w[t - 7] + s1) | 0
    }

    // Working variables
    let a = h0, b = h1, c = h2, d = h3, e = h4, f = h5, g = h6, h = h7

    // Compression
    for (let t = 0; t < 64; t++) {
      const S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)
      const ch = (e & f) ^ (~e & g)
      const temp1 = (h + S1 + ch + K[t] + w[t]) | 0
      const S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)
      const maj = (a & b) ^ (a & c) ^ (b & c)
      const temp2 = (S0 + maj) | 0

      h = g; g = f; f = e; e = (d + temp1) | 0
      d = c; c = b; b = a; a = (temp1 + temp2) | 0
    }

    // Update hash
    h0 = (h0 + a) | 0; h1 = (h1 + b) | 0; h2 = (h2 + c) | 0; h3 = (h3 + d) | 0
    h4 = (h4 + e) | 0; h5 = (h5 + f) | 0; h6 = (h6 + g) | 0; h7 = (h7 + h) | 0
  }

  // Produce final hash
  return [h0, h1, h2, h3, h4, h5, h6, h7]
    .map(v => (v >>> 0).toString(16).padStart(8, '0'))
    .join('')
}

function rotr(x: number, n: number): number {
  return (x >>> n) | (x << (32 - n))
}

// ─── Connection health tracking ───────────────────────────────────────────
let connectTime: number | null = null
let lastEventTime: number | null = null
let reconnectCount = 0
let isReconnecting = false

// ─── Latency tracking ─────────────────────────────────────────────────────
let currentLatencyMs: number = -1   // -1 = unknown
let latencyHistory: number[] = []   // Last 20 ping samples
const MAX_LATENCY_HISTORY = 20
let pingIntervalId: ReturnType<typeof setInterval> | null = null
let pendingPingTimestamp: number | null = null

export interface ConnectionHealth {
  connected: boolean
  connectTime: number | null
  lastEventTime: number | null
  reconnectCount: number
  socketId: string | null
  uptime: number // seconds since connect
  latencyMs: number  // -1 = unknown
  avgLatencyMs: number // -1 = unknown
  networkQuality: 'excellent' | 'good' | 'fair' | 'poor' | 'unknown'
}

/**
 * Determine network quality based on average latency.
 * For LAN: excellent <5ms, good <15ms, fair <30ms, poor >=30ms
 */
function classifyNetworkQuality(avgLatency: number): ConnectionHealth['networkQuality'] {
  if (avgLatency < 0) return 'unknown'
  if (avgLatency < 5) return 'excellent'
  if (avgLatency < 15) return 'good'
  if (avgLatency < 30) return 'fair'
  return 'poor'
}

export function getConnectionHealth(): ConnectionHealth {
  const avgLatency = latencyHistory.length > 0
    ? latencyHistory.reduce((a, b) => a + b, 0) / latencyHistory.length
    : -1

  return {
    connected: socket?.connected ?? false,
    connectTime,
    lastEventTime,
    reconnectCount,
    socketId: socket?.id ?? null,
    uptime: connectTime ? Math.round((Date.now() - connectTime) / 1000) : 0,
    latencyMs: currentLatencyMs,
    avgLatencyMs: Math.round(avgLatency),
    networkQuality: classifyNetworkQuality(avgLatency),
  }
}

/** Subscribe to latency updates — called every 5s after ping measurement */
type LatencyCallback = (health: ConnectionHealth) => void
const latencyListeners: LatencyCallback[] = []

export function onLatencyUpdate(cb: LatencyCallback) {
  latencyListeners.push(cb)
  return () => {
    const idx = latencyListeners.indexOf(cb)
    if (idx !== -1) latencyListeners.splice(idx, 1)
  }
}

function notifyLatencyListeners() {
  const health = getConnectionHealth()
  for (const cb of latencyListeners) {
    try { cb(health) } catch {}
  }
}

// ─── Ping/pong measurement ────────────────────────────────────────────────
function startPingMeasurement() {
  stopPingMeasurement()
  // Measure latency every 5 seconds
  pingIntervalId = setInterval(() => {
    if (!socket?.connected) return
    pendingPingTimestamp = Date.now()
    socket.emit('saatiril-ping', pendingPingTimestamp)
  }, 5000)
  // Do first ping immediately
  if (socket?.connected) {
    pendingPingTimestamp = Date.now()
    socket.emit('saatiril-ping', pendingPingTimestamp)
  }
}

function stopPingMeasurement() {
  if (pingIntervalId) {
    clearInterval(pingIntervalId)
    pingIntervalId = null
  }
  pendingPingTimestamp = null
}

function handlePong(timestamp: number) {
  if (pendingPingTimestamp && pendingPingTimestamp === timestamp) {
    const latency = Date.now() - timestamp
    currentLatencyMs = latency
    latencyHistory.push(latency)
    if (latencyHistory.length > MAX_LATENCY_HISTORY) {
      latencyHistory.shift()
    }
    pendingPingTimestamp = null
    notifyLatencyListeners()
  }
}

/**
 * Get the Socket.io server URL.
 *
 * Connection modes:
 *
 * 1. Electron desktop (admin):
 *    - Read socketPort from URL query parameter (passed by Electron main process)
 *    - Connect directly to localhost:PORT (always HTTP)
 *
 * 2. Direct LAN access (port 3000/3001):
 *    - User accessed the Next.js app directly on its port
 *    - Socket.io is on a different port (default 3003) on the same host
 *    - Connect via http://hostname:socketPort
 *    - All connections use HTTP (no HTTPS server)
 *
 * 3. Web/sandbox mode (Caddy proxy or any other reverse proxy):
 *    - User accessed through a reverse proxy (e.g., Caddy on port 81)
 *    - Socket.io port is NOT directly accessible from the browser
 *    - Use XTransformPort=PORT query parameter for Caddy gateway routing
 *    - Connect via window.location.origin (same origin, Caddy handles routing)
 *
 * IMPORTANT: The detection is based on the ACCESS PORT, not the presence of
 * the socketPort URL parameter. The socketPort param tells us WHICH port the
 * Socket.io server runs on, but the connection METHOD (direct vs proxy) depends
 * on how the user accessed the page (direct port 3000 vs proxy port 81).
 */
function getSocketUrl(): string {
  if (typeof window === 'undefined') return '/'

  // Check if running in Electron
  const isElectron = !!(window as any).saatirilAPI?.isElectron
  const params = new URLSearchParams(window.location.search)
  const socketPortParam = params.get('socketPort')

  if (isElectron) {
    // Electron admin: always connect via HTTP localhost
    const port = socketPortParam || '3003'
    return `http://localhost:${port}`
  }

  // Determine if this is a direct LAN access (port 3000/3001) or proxy access
  // This is the KEY distinction: direct LAN can reach the socket port directly,
  // while proxy access (Caddy) must route through the proxy.
  const currentPort = window.location.port
  const isDirectLanAccess = currentPort === '3000' || currentPort === '3001'

  if (isDirectLanAccess) {
    // Direct LAN access: connect directly to the Socket.io server
    const port = socketPortParam || '3003'
    return `http://${window.location.hostname}:${port}`
  }

  // Web/sandbox/proxy mode: go through the reverse proxy (Caddy)
  // The socket.io client will include XTransformPort in its query params
  // (set in connectSocket()) so Caddy can route to the correct port.
  // We use the current origin as the base URL — Caddy handles the rest.
  return window.location.origin
}

export function getSocket(): Socket | null {
  return socket
}

// ─── Critical event queue ─────────────────────────────────────────────────
// Events emitted while disconnected are queued and sent on reconnect
interface QueuedEvent {
  event: string
  data: any
  timestamp: number
  retries: number
}

const eventQueue: QueuedEvent[] = []
const MAX_QUEUE_SIZE = 50
const MAX_RETRIES = 3
const CRITICAL_EVENTS = new Set(['PHOTOS_SAVED', 'MC_CALL', 'SYNC_DB', 'STUDENT_DONE', 'STUDENT_RESET'])

function queueEvent(event: string, data: any) {
  // Only queue critical events
  if (!CRITICAL_EVENTS.has(event)) return
  if (eventQueue.length >= MAX_QUEUE_SIZE) {
    // Remove oldest non-critical event
    const oldestIdx = eventQueue.findIndex(e => !CRITICAL_EVENTS.has(e.event))
    if (oldestIdx !== -1) {
      eventQueue.splice(oldestIdx, 1)
    } else {
      // All are critical — remove oldest
      eventQueue.shift()
    }
  }
  eventQueue.push({ event, data, timestamp: Date.now(), retries: 0 })
  console.log(`[SAATIRIL] Queued critical event: ${event} (queue: ${eventQueue.length})`)
}

function flushEventQueue() {
  if (!socket?.connected || eventQueue.length === 0) return

  const toSend = [...eventQueue]
  eventQueue.length = 0

  for (const item of toSend) {
    if (item.retries >= MAX_RETRIES) {
      console.warn(`[SAATIRIL] Dropping event after ${MAX_RETRIES} retries: ${item.event}`)
      continue
    }
    item.retries++
    socket.emit('lan-message', { event: item.event, data: item.data })
    console.log(`[SAATIRIL] Flushed queued event: ${item.event} (attempt ${item.retries})`)
  }
}

/**
 * Send any pending session password to the server.
 * Called when the socket connects.
 */
async function flushPendingSessionPassword() {
  if (!pendingSessionPassword || !socket?.connected) return
  const password = pendingSessionPassword
  pendingSessionPassword = null
  await setSessionPassword(password)
  console.log('[SAATIRIL] Flushed pending session password to server')
}

// ─── Connect Socket ───────────────────────────────────────────────────────
export function connectSocket(): Socket {
  if (socket?.connected) return socket

  // Clean up existing disconnected socket before creating a new one
  if (socket) {
    socket.removeAllListeners()
    socket.disconnect()
    socket = null
  }

  const socketUrl = getSocketUrl()
  const isElectron = !!(window as any).saatirilAPI?.isElectron

  // All modes use the same options — Socket.io server is always path '/'
  const socketOptions = {
    path: '/',
    transports: ['websocket', 'polling'],
    forceNew: true,
    reconnection: true,
    reconnectionAttempts: Infinity,    // Never give up during ceremony!
    reconnectionDelay: 1000,           // Start at 1s
    reconnectionDelayMax: 10000,       // Max 10s between retries
    timeout: 15000,                    // 15s connection timeout
  }

  // For sandbox/web/proxy mode, add XTransformPort as a query parameter
  // so Caddy gateway can route requests to the correct port.
  // Sandbox mode = not Electron AND not direct LAN access (port 3000/3001)
  //
  // IMPORTANT: We check ONLY the access port to determine sandbox mode,
  // NOT whether socketPort is in the URL. The socketPort param tells us
  // which port the Socket.io server runs on, but the connection METHOD
  // (direct vs proxy) depends on how the user accessed the page.
  //
  // Previously, the check was:
  //   !isElectron && !socketPortParam && !isDirectLanAccess
  // This was WRONG because when MC copies a link from the admin dashboard
  // (which always includes socketPort), the socketPort param is set, and
  // isSandboxMode would be false even in sandbox mode. The socket would
  // try to connect directly to hostname:3003, which is not accessible
  // through Caddy, causing MC to be stuck on "Sinkronisasi Data" forever.
  const currentPort = typeof window !== 'undefined' ? window.location.port : ''
  const isDirectLanAccess = currentPort === '3000' || currentPort === '3001'
  const isSandboxMode = !isElectron && !isDirectLanAccess
  // Use the socketPort from URL params, or default to 3003
  const socketPort = new URLSearchParams(window.location.search).get('socketPort') || '3003'
  const finalOptions = isSandboxMode
    ? { ...socketOptions, query: { ...socketOptions.query, XTransformPort: socketPort } }
    : socketOptions

  console.log('[SAATIRIL] Connecting to Socket.io server...', socketUrl, isSandboxMode ? `(sandbox mode with XTransformPort=${socketPort})` : '(direct connection)')
  socket = io(socketUrl, finalOptions)

  // ── Connection lifecycle ──────────────────────────────────────────────
  socket.on('connect', () => {
    connectTime = Date.now()
    isReconnecting = false
    console.log('[SAATIRIL] Socket connected:', socket?.id, `(reconnects: ${reconnectCount})`)

    // Reset auth state on new connection
    isAuthenticated = false

    // Identify ourselves to the server (with session password hash if available)
    const identifyPayload: Record<string, any> = {
      role: typeof window !== 'undefined'
        ? new URLSearchParams(window.location.search).get('role') || 'unknown'
        : 'unknown',
      channel: typeof window !== 'undefined'
        ? parseInt(new URLSearchParams(window.location.search).get('channel') || '1', 10)
        : 1,
    }

    // Include session password hash for non-admin clients
    const role = identifyPayload.role
    if (role !== 'admin' && currentSessionPasswordHash) {
      identifyPayload.sessionPasswordHash = currentSessionPasswordHash
    }

    socket?.emit('identify', identifyPayload)

    // Flush any queued events from when we were disconnected
    // Only flush after we know we're authenticated
    // (auth-success handler will flush)
    if (role === 'admin' || !authRequiredByServer) {
      flushEventQueue()
    }

    // Send any pending session password (admin set password before socket connected)
    flushPendingSessionPassword()

    // Notify local listeners that the socket is connected
    // This is used by the "ensure session password" hook in main-app.tsx
    if (listeners['__SOCKET_CONNECTED__']) {
      listeners['__SOCKET_CONNECTED__'].forEach(cb => {
        try { cb({}) } catch (err) {
          console.error('[SAATIRIL] Error in __SOCKET_CONNECTED__ listener:', err)
        }
      })
    }

    // Start ping measurement for latency tracking
    startPingMeasurement()
  })

  socket.on('disconnect', (reason) => {
    console.warn('[SAATIRIL] Socket disconnected. Reason:', reason)
    stopPingMeasurement()
    currentLatencyMs = -1
    notifyLatencyListeners()

    // If server initiated disconnect, we need manual reconnect
    if (reason === 'io server disconnect') {
      // Server kicked us — reconnect after delay
      setTimeout(() => {
        console.log('[SAATIRIL] Attempting manual reconnect...')
        socket?.connect()
      }, 2000)
    }
  })

  socket.on('connect_error', (error) => {
    if (!isReconnecting) {
      isReconnecting = true
      reconnectCount++
    }
    console.warn('[SAATIRIL] Connection error (attempt #' + reconnectCount + '):', error.message)
  })

  socket.on('reconnect', (attemptNumber) => {
    console.log('[SAATIRIL] Reconnected after', attemptNumber, 'attempts')
    isReconnecting = false
  })

  socket.on('reconnect_error', (error) => {
    console.warn('[SAATIRIL] Reconnection error:', error.message)
  })

  socket.on('reconnect_failed', () => {
    console.error('[SAATIRIL] Reconnection failed — will keep trying manually')
    // Manual retry every 5 seconds
    const manualRetry = setInterval(() => {
      if (socket?.connected) {
        clearInterval(manualRetry)
        return
      }
      console.log('[SAATIRIL] Manual reconnection attempt...')
      socket?.connect()
    }, 5000)
  })

  // ── Auth event handlers ────────────────────────────────────────────────
  socket.on('auth-requirement', (data: { passwordRequired: boolean }) => {
    authRequiredByServer = data.passwordRequired
    console.log(`[SAATIRIL] Server auth requirement: passwordRequired=${data.passwordRequired}`)
    // Notify listeners about auth requirement
    if (listeners['auth-requirement']) {
      listeners['auth-requirement'].forEach(cb => {
        try { cb(data) } catch (err) {
          console.error('[SAATIRIL] Error in auth-requirement listener:', err)
        }
      })
    }
  })

  socket.on('auth-success', (data: { role: string; channel: number }) => {
    isAuthenticated = true
    console.log(`[SAATIRIL] Auth success: role=${data.role}, channel=${data.channel}`)
    // Flush queued events now that we're authenticated
    flushEventQueue()
    // Notify listeners about auth success
    if (listeners['auth-success']) {
      listeners['auth-success'].forEach(cb => {
        try { cb(data) } catch (err) {
          console.error('[SAATIRIL] Error in auth-success listener:', err)
        }
      })
    }
  })

  socket.on('auth-failed', (data: { reason: string }) => {
    isAuthenticated = false
    console.warn(`[SAATIRIL] Auth failed: ${data.reason}`)
    // Notify listeners about auth failure
    if (listeners['auth-failed']) {
      listeners['auth-failed'].forEach(cb => {
        try { cb(data) } catch (err) {
          console.error('[SAATIRIL] Error in auth-failed listener:', err)
        }
      })
    }
  })

  // ── Ping/pong handler for latency measurement ────────────────────────
  socket.on('saatiril-pong', handlePong)

  // ── Server shutdown notification ──────────────────────────────────────
  socket.on('lan-message', (payload: { event: string; data: any }) => {
    const { event: evt, data } = payload
    lastEventTime = Date.now()

    if (evt === 'SERVER_SHUTDOWN') {
      console.warn('[SAATIRIL] Server is shutting down:', data)
      return
    }

    if (listeners[evt]) {
      listeners[evt].forEach(cb => {
        try {
          cb(data)
        } catch (err) {
          console.error(`[SAATIRIL] Error in listener for ${evt}:`, err)
        }
      })
    }
  })

  return socket
}

// ─── Emit with queue ──────────────────────────────────────────────────────
export function emitLocal(event: string, data: any) {
  if (socket?.connected) {
    socket.emit('lan-message', { event, data })
  } else if (CRITICAL_EVENTS.has(event)) {
    // Queue critical events for later delivery
    queueEvent(event, data)
  } else {
    console.warn(`[SAATIRIL] Event "${event}" lost — socket not connected and not critical`)
  }

  // Always trigger local listeners immediately (even if disconnected)
  if (listeners[event]) {
    listeners[event].forEach(cb => {
      try {
        cb(data)
      } catch (err) {
        console.error(`[SAATIRIL] Error in local listener for ${event}:`, err)
      }
    })
  }
}

// ─── Listener management ──────────────────────────────────────────────────
export function onLocal(event: string, callback: LocalNetworkCallback) {
  if (!listeners[event]) listeners[event] = []
  listeners[event].push(callback)
  return () => {
    listeners[event] = listeners[event].filter(cb => cb !== callback)
  }
}

export function offLocal(event: string, callback?: LocalNetworkCallback) {
  if (!listeners[event]) return
  if (callback) {
    listeners[event] = listeners[event].filter(cb => cb !== callback)
  } else {
    delete listeners[event]
  }
}

// ─── Session Password Management ──────────────────────────────────────────

/**
 * Admin: set the session password on the server.
 * Sends the SHA-256 hash so the password is never transmitted in plaintext.
 *
 * If the socket is not connected yet, the password is stored as pending
 * and will be sent once the socket connects.
 */
export async function setSessionPassword(password: string): Promise<void> {
  try {
    const hash = await sha256(password)
    currentSessionPasswordHash = hash

    if (!socket?.connected) {
      // Socket not connected yet — store as pending and send on connect
      pendingSessionPassword = password
      console.log('[SAATIRIL] Session password queued (socket not connected)')
      return
    }

    socket.emit('SET_SESSION_PASSWORD', { passwordHash: hash })
    console.log('[SAATIRIL] Session password set on server')
  } catch (err) {
    console.error('[SAATIRIL] Failed to set session password:', err)
  }
}

/**
 * Admin: clear the session password on the server.
 */
export function clearSessionPassword(): void {
  currentSessionPasswordHash = null
  pendingSessionPassword = null
  if (!socket?.connected) return
  socket.emit('CLEAR_SESSION_PASSWORD')
  console.log('[SAATIRIL] Session password cleared on server')
}

/**
 * Non-admin: re-identify with a session password.
 * Called after the user enters the password in the prompt.
 * Hashes the password and sends it to the server for validation.
 *
 * IMPORTANT: Uses sha256() which has a pure JS fallback for insecure
 * HTTP contexts (crypto.subtle unavailable on http://192.168.x.x).
 */
export async function reidentifyWithPassword(password: string): Promise<void> {
  if (!socket?.connected) {
    console.warn('[SAATIRIL] Cannot re-identify: socket not connected')
    return
  }
  try {
    const hash = await sha256(password)
    currentSessionPasswordHash = hash

    const role = typeof window !== 'undefined'
      ? new URLSearchParams(window.location.search).get('role') || 'unknown'
      : 'unknown'
    const channel = typeof window !== 'undefined'
      ? parseInt(new URLSearchParams(window.location.search).get('channel') || '1', 10)
      : 1

    socket.emit('identify', {
      role,
      channel,
      sessionPasswordHash: hash,
    })
    console.log(`[SAATIRIL] Re-identifying with session password (role: ${role})`)
  } catch (err) {
    console.error('[SAATIRIL] Failed to re-identify with password:', err)
  }
}

/**
 * Check if the current connection is authenticated.
 */
export function isSocketAuthenticated(): boolean {
  return isAuthenticated
}

/**
 * Check if the server requires a session password.
 */
export function isServerPasswordRequired(): boolean {
  return authRequiredByServer
}
