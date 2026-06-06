'use client'

import { io, Socket } from 'socket.io-client'

// ─── Types ────────────────────────────────────────────────────────────────
export type LocalNetworkCallback = (data: any) => void

// ─── Module-level state ───────────────────────────────────────────────────
let socket: Socket | null = null
const listeners: Record<string, LocalNetworkCallback[]> = {}

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
 * 2. LAN device (MC or Operator):
 *    - socketPort is the HTTP Socket.io port (3003)
 *    - Connect via http://hostname:socketPort
 *    - All connections use HTTP (no HTTPS server)
 *    - Operator needs Chrome Flag for camera access
 *
 * 3. Web/sandbox mode (development):
 *    - Use XTransformPort=3003 for Caddy gateway routing
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

  // LAN device: always use HTTP to connect to Socket.io server
  if (socketPortParam) {
    const hostname = window.location.hostname
    return `http://${hostname}:${socketPortParam}`
  }

  // Web/sandbox mode: use Caddy gateway with XTransformPort
  return '/?XTransformPort=3003'
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
const CRITICAL_EVENTS = new Set(['PHOTOS_SAVED', 'MC_CALL', 'SYNC_DB'])

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

  console.log('[SAATIRIL] Connecting to Socket.io server...', socketUrl)
  socket = io(socketUrl, socketOptions)

  // ── Connection lifecycle ──────────────────────────────────────────────
  socket.on('connect', () => {
    connectTime = Date.now()
    isReconnecting = false
    console.log('[SAATIRIL] Socket connected:', socket?.id, `(reconnects: ${reconnectCount})`)

    // Identify ourselves to the server
    socket?.emit('identify', {
      role: typeof window !== 'undefined'
        ? new URLSearchParams(window.location.search).get('role') || 'unknown'
        : 'unknown',
      channel: typeof window !== 'undefined'
        ? parseInt(new URLSearchParams(window.location.search).get('channel') || '1', 10)
        : 1,
    })

    // Flush any queued events from when we were disconnected
    flushEventQueue()

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
