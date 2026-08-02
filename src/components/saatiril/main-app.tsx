'use client'

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  ArrowLeft,
  Camera,
  LayoutDashboard,
  Megaphone,
  PanelLeftClose,
  PanelLeftOpen,
  Loader2,
  Wifi,
  Copy,
  Check,
  Minimize,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { useSaatirilStore, type Project, type CameraMode, mergeDatabases, stripFrameForSync, preserveFrameOnSync, preservePhotoHistoryOnSync, isDualMode, isPhotoshootMode } from '@/store/use-saatiril-store'
import { connectSocket, onLocal, offLocal, emitLocal, getSocket, getConnectionHealth, setSessionPassword, clearSessionPassword, reidentifyWithPassword, isSocketAuthenticated, isServerPasswordRequired, resendSessionPasswordHash, getSocketAuthState } from '@/lib/socket'

import AdminDashboard from '@/components/saatiril/admin-dashboard'
import { McPanel } from '@/components/saatiril/mc-panel'
import OperatorPanel from '@/components/saatiril/operator-panel'
import { SaatirilFooterLines } from '@/components/saatiril/saatiril-footer'
import { LicenseGate } from '@/components/saatiril/license-gate'

// ─── Theme constants ──────────────────────────────────────────────────────────
const THEME = {
  bg: '#1a0b2e',
  panel: '#2a164a',
  card: '#3b2263',
  border: '#533485',
  gold: '#d4af37',
  muted: '#c4b5fd',
  cyan: '#06b6d4',
  red: '#ef4444',
} as const

// ─── View type ────────────────────────────────────────────────────────────────
type MainView = 'live' | 'admin'

// ─── Component ────────────────────────────────────────────────────────────────
export function MainApp() {
  // ── License gate ─────────────────────────────────────────────────────────
  const [licenseValid, setLicenseValid] = useState(false)

  // ── Store bindings ─────────────────────────────────────────────────────────
  const currentProject = useSaatirilStore((s) => s.currentProject)
  const updateCurrentProject = useSaatirilStore((s) => s.updateCurrentProject)
  const myChannel = useSaatirilStore((s) => s.myChannel)
  const setMyChannel = useSaatirilStore((s) => s.setMyChannel)
  const setCurrentScreen = useSaatirilStore((s) => s.setCurrentScreen)
  const loadProjectsFromStorage = useSaatirilStore((s) => s.loadProjectsFromStorage)

  // ── Local state ────────────────────────────────────────────────────────────
  const [activeView, setActiveView] = useState<MainView>('live')
  const [mcSidebarOpen, setMcSidebarOpen] = useState(true)
  const [appFullscreen, setAppFullscreen] = useState(false)
  const [serverConnected, setServerConnected] = useState(false)
  const [connectionQuality, setConnectionQuality] = useState<'good' | 'degraded' | 'disconnected'>('disconnected')
  const [lanIP, setLanIP] = useState<string>('')
  const [copiedIP, setCopiedIP] = useState(false)
  const [sessionPasswordInput, setSessionPasswordInput] = useState('')
  const [sessionPasswordVerified, setSessionPasswordVerified] = useState(false)
  const [sessionPasswordError, setSessionPasswordError] = useState(false)
  const [serverRequiresPassword, setServerRequiresPassword] = useState(false)
  const [authFailedReason, setAuthFailedReason] = useState<string | null>(null)
  const [forceShowPasswordPrompt, setForceShowPasswordPrompt] = useState(false)

  // ── Refs for stable event handlers ─────────────────────────────────────────
  const currentProjectRef = useRef(currentProject)
  useEffect(() => { currentProjectRef.current = currentProject }, [currentProject])

  // ── Unified view: always admin role ────────────────────────────────────────
  const myRole = 'admin' as const

  // ── Derived values ─────────────────────────────────────────────────────────
  const isDualModeVal = isDualMode(currentProject?.config.mode ?? 'single')

  // ── App-wide fullscreen management ──────────────────────────────────────────
  const toggleAppFullscreen = useCallback(() => {
    setAppFullscreen((prev) => {
      const next = !prev
      if (next) {
        // Enter fullscreen: request browser fullscreen + hide chrome
        document.documentElement.requestFullscreen?.().catch(() => {})
      } else {
        // Exit fullscreen
        document.exitFullscreen?.().catch(() => {})
      }
      return next
    })
  }, [])

  // Sync state if user exits browser fullscreen via Escape or browser UI
  useEffect(() => {
    const onFsChange = () => {
      if (!document.fullscreenElement) {
        setAppFullscreen(false)
      }
    }
    document.addEventListener('fullscreenchange', onFsChange)
    return () => document.removeEventListener('fullscreenchange', onFsChange)
  }, [])

  // ── Detect LAN IP via WebRTC ───────────────────────────────────────────────
  const lanIPFoundRef = useRef(false)
  useEffect(() => {
    try {
      const pc = new RTCPeerConnection({ iceServers: [] })
      pc.createDataChannel('')
      pc.createOffer().then((offer) => pc.setLocalDescription(offer))
      pc.onicecandidate = (e) => {
        if (!e.candidate) return
        const parts = e.candidate.candidate.split(' ')
        const ip = parts[4]
        if (ip && /^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$/.test(ip) && !ip.startsWith('0.') && ip !== '0.0.0.0') {
          lanIPFoundRef.current = true
          setLanIP(ip)
          pc.close()
        }
      }
      // Fallback: also try to detect from hostname
      setTimeout(() => {
        pc.close()
        if (!lanIPFoundRef.current && typeof window !== 'undefined') {
          const hostname = window.location.hostname
          if (hostname && hostname !== 'localhost' && hostname !== '127.0.0.1') {
            setLanIP(hostname)
          }
        }
      }, 3000)
    } catch {
      // WebRTC not available, skip
    }
  }, [])

  // ── Detect HTTP port for LAN access ───────────────────────────────────────
  const [httpPort, setHttpPort] = useState(3000)

  useEffect(() => {
    const api = window.saatirilAPI
    if (api?.isElectron && api.getLanInfo) {
      api.getLanInfo().then((info: { httpPort: number }) => {
        setHttpPort(info.httpPort)
      }).catch(() => {})
    }
  }, [])

  // ── Copy IP to clipboard ───────────────────────────────────────────────────
  const handleCopyIP = useCallback(() => {
    if (!lanIP) return
    navigator.clipboard.writeText(`http://${lanIP}:${httpPort}`)
    setCopiedIP(true)
    setTimeout(() => setCopiedIP(false), 2000)
  }, [lanIP, httpPort])

  // ── URL parameter handling (run once on mount) ────────────────────────────
  useEffect(() => {
    const params = new URLSearchParams(window.location.search)
    const channelParam = params.get('channel')
    if (channelParam) {
      const ch = parseInt(channelParam, 10)
      if (ch >= 1 && ch <= 2) {
        setMyChannel(ch)
      }
    }
  }, [setMyChannel])

  // ── Socket initialization ─────────────────────────────────────────────────
  useEffect(() => {
    const socket = connectSocket()

    const handleConnect = () => {
      setServerConnected(true)
      setConnectionQuality('good')

      const curProj = useSaatirilStore.getState().currentProject
      if (curProj) {
        if (curProj.config.sessionPassword && curProj.config.sessionPassword !== '__PASSWORD_SET__') {
          setSessionPassword(curProj.config.sessionPassword, curProj.id)
        } else if (curProj.config.sessionPassword === '__PASSWORD_SET__') {
          const storedHash = typeof window !== 'undefined'
            ? localStorage.getItem(`saatiril_pwdhash_${curProj.id}`)
            : null
          if (storedHash && storedHash !== '__PWD_PENDING__') {
            resendSessionPasswordHash(storedHash)
            console.log('[SAATIRIL] Admin re-sent stored password hash to server on reconnect')
          }
        }
      }

      console.log('[SAATIRIL] Connected — admin unified view')
    }
    const handleDisconnect = () => {
      setServerConnected(false)
      setConnectionQuality('disconnected')
    }

    const handleAuthRequirement = (data: { passwordRequired: boolean }) => {
      console.log('[SAATIRIL] auth-requirement received:', data.passwordRequired)
      setServerRequiresPassword(data.passwordRequired)
    }

    const handleAuthSuccess = () => {
      console.log('[SAATIRIL] auth-success received (admin)')
      setSessionPasswordVerified(true)
      setSessionPasswordError(false)
      setAuthFailedReason(null)
      setForceShowPasswordPrompt(false)
    }

    const handleAuthFailed = (data: { reason: string }) => {
      console.log('[SAATIRIL] auth-failed received:', data.reason)
      setSessionPasswordVerified(false)
      setSessionPasswordError(true)
      setAuthFailedReason(data.reason)
    }

    socket.on('connect', handleConnect)
    socket.on('disconnect', handleDisconnect)
    socket.on('auth-requirement', handleAuthRequirement)
    socket.on('auth-success', handleAuthSuccess)
    socket.on('auth-failed', handleAuthFailed)

    queueMicrotask(() => {
      const authState = getSocketAuthState()
      if (authState.connected) {
        setServerConnected(true)
        setConnectionQuality('good')

        const curProj = useSaatirilStore.getState().currentProject
        if (curProj) {
          if (curProj.config.sessionPassword && curProj.config.sessionPassword !== '__PASSWORD_SET__') {
            setSessionPassword(curProj.config.sessionPassword, curProj.id)
          } else if (curProj.config.sessionPassword === '__PASSWORD_SET__') {
            const storedHash = typeof window !== 'undefined'
              ? localStorage.getItem(`saatiril_pwdhash_${curProj.id}`)
              : null
            if (storedHash && storedHash !== '__PWD_PENDING__') {
              resendSessionPasswordHash(storedHash)
            }
          }
        }
      }
      if (authState.passwordRequired) {
        setServerRequiresPassword(true)
      }
    })

    return () => {
      socket.off('connect', handleConnect)
      socket.off('disconnect', handleDisconnect)
      socket.off('auth-requirement', handleAuthRequirement)
      socket.off('auth-success', handleAuthSuccess)
      socket.off('auth-failed', handleAuthFailed)
    }
  }, [])

  // ── Connection quality monitor ────────────────────────────────────────────
  useEffect(() => {
    const monitor = setInterval(() => {
      const health = getConnectionHealth()
      if (!health.connected) {
        setConnectionQuality('disconnected')
      } else if (health.reconnectCount > 2) {
        setConnectionQuality('degraded')
      } else {
        setConnectionQuality('good')
      }
    }, 5000)
    return () => clearInterval(monitor)
  }, [])

  // ── Socket event listeners (stable — no currentProject in deps) ──────────
  useEffect(() => {
    const handleSyncDb = (data: { project: Project }) => {
      const curProj = useSaatirilStore.getState().currentProject

      if (data.project) {
        if (curProj && data.project.id === curProj.id) {
          const mergedDb = mergeDatabases(curProj.database, data.project.database)
          const mergedConfig = preserveFrameOnSync(data.project.config, curProj.config)
          const mergedPhotoHistory = preservePhotoHistoryOnSync(
            data.project.photoHistory ?? [],
            curProj.photoHistory,
          )
          updateCurrentProject({
            ...curProj,
            database: mergedDb,
            photoHistory: mergedPhotoHistory,
            config: mergedConfig,
          })
        }
      }
    }

    const handleRequestState = () => {
      const curProj = useSaatirilStore.getState().currentProject
      if (curProj) {
        let frameToSend = curProj.config.frame
        if (frameToSend === '__FRAME_SAVED__') {
          let savedFrame = typeof window !== 'undefined'
            ? localStorage.getItem(`saatiril_frame_${curProj.id}`)
            : null
          if (!savedFrame && typeof window !== 'undefined') {
            try {
              const raw = localStorage.getItem('saatiril_projects')
              if (raw) {
                const projects = JSON.parse(raw)
                const found = projects.find((p: any) => p.id === curProj.id)
                if (found?.config?.frame && found.config.frame !== '__FRAME_SAVED__') {
                  savedFrame = found.config.frame
                  console.log('[SAATIRIL] REQUEST_STATE: Restored frame from projects JSON fallback')
                }
              }
            } catch { /* ignore parse errors */ }
          }
          if (savedFrame) {
            frameToSend = savedFrame
            try { localStorage.setItem(`saatiril_frame_${curProj.id}`, savedFrame) } catch {}
            console.log('[SAATIRIL] REQUEST_STATE: Restored frame from localStorage for sync')
          } else {
            frameToSend = null
            console.warn('[SAATIRIL] REQUEST_STATE: Frame marker __FRAME_SAVED__ found but no data in localStorage — sending null')
          }
        }

        console.log('[SAATIRIL] REQUEST_STATE: Sending project to client — frame:', frameToSend ? `${Math.round((frameToSend.length / 1024))}KB` : 'none', 'password:', curProj.config.sessionPassword ? '__PASSWORD_SET__' : 'none')

        const safeProject = {
          ...curProj,
          config: {
            ...curProj.config,
            frame: frameToSend,
            sessionPassword: curProj.config.sessionPassword ? '__PASSWORD_SET__' : undefined,
          },
          photoHistory: curProj.photoHistory.map(h => ({ ...h, photos: [] })),
        }
        delete (safeProject as any)._sessionPasswordHash
        emitLocal('SYNC_DB', { project: safeProject })
      }
    }

    const handleRequestFrame = (data: { projectId: string; requesterRole: string }) => {
      const curProj = useSaatirilStore.getState().currentProject
      if (!curProj || curProj.id !== data.projectId) return

      let frameData = curProj.config.frame
      if (frameData === '__FRAME_SAVED__') {
        const savedFrame = typeof window !== 'undefined'
          ? localStorage.getItem(`saatiril_frame_${curProj.id}`)
          : null
        frameData = savedFrame || null
      }

      if (frameData) {
        console.log('[SAATIRIL] REQUEST_FRAME: Sending frame to', data.requesterRole, `(${Math.round(frameData.length / 1024)}KB)`)
        emitLocal('FRAME_DATA', { projectId: curProj.id, frame: frameData })
      } else {
        console.warn('[SAATIRIL] REQUEST_FRAME: No frame data available to send')
      }
    }

    onLocal('SYNC_DB', handleSyncDb)
    onLocal('REQUEST_STATE', handleRequestState)
    onLocal('REQUEST_FRAME', handleRequestFrame)

    return () => {
      offLocal('SYNC_DB', handleSyncDb)
      offLocal('REQUEST_STATE', handleRequestState)
      offLocal('REQUEST_FRAME', handleRequestFrame)
    }
  }, [updateCurrentProject])

  // ── Handlers ──────────────────────────────────────────────────────────────
  const handleBack = useCallback(() => {
    const store = useSaatirilStore.getState()
    if (store.currentProject) {
      store.saveProjectsToStorageNow()
    }
    store.setMyRole('admin')
    store.setCurrentTab('admin')
    store.setMyChannel(1)
    store.resetOpState()
    setCurrentScreen('hub')
  }, [setCurrentScreen])

  const handleChannelSelect = useCallback(
    (channel: string) => {
      setMyChannel(parseInt(channel, 10))
    },
    [setMyChannel],
  )

  // ── Render: License gate (Electron only) ──────────────────────────────────
  if (!licenseValid) {
    return <LicenseGate onLicenseValid={() => setLicenseValid(true)} />
  }

  // ── Main render ───────────────────────────────────────────────────────────
  return (
    <div className="flex h-dvh flex-col" style={{ backgroundColor: THEME.bg }}>
      {/* ── Header (hidden in fullscreen) ─────────────────────────────────────── */}
      {!appFullscreen && (
        <header
          className="shrink-0 border-b backdrop-blur-sm z-20"
          style={{
            backgroundColor: `${THEME.panel}ee`,
            borderColor: THEME.border,
          }}
        >
          <div className="flex items-center gap-2 px-2 py-2 sm:gap-3 sm:px-4 md:gap-4 md:px-6">
            {/* Back button */}
            <Button
              variant="ghost"
              size="icon"
              onClick={handleBack}
              className="shrink-0 text-[#c4b5fd] hover:bg-white/10 hover:text-[#d4af37]"
              aria-label="Kembali ke hub"
            >
              <ArrowLeft className="size-5" />
            </Button>

            {/* Project name */}
            <div className="min-w-0 flex-1">
              <h1 className="truncate text-sm font-bold text-white sm:text-base">
                {currentProject?.name ?? 'Saatiril'}
              </h1>
            </div>

            {/* View toggle: Live / Admin */}
            <div className="flex items-center gap-1 rounded-lg p-1" style={{ backgroundColor: `${THEME.bg}88` }}>
              <button
                onClick={() => setActiveView('live')}
                className="flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-semibold uppercase tracking-wider transition-all cursor-pointer"
                style={{
                  backgroundColor: activeView === 'live' ? `${THEME.gold}22` : 'transparent',
                  color: activeView === 'live' ? THEME.gold : THEME.muted,
                }}
              >
                <Megaphone className="size-3.5" />
                <span className="hidden sm:inline">MC + Operator</span>
                <span className="sm:hidden">Live</span>
              </button>
              <button
                onClick={() => setActiveView('admin')}
                className="flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-semibold uppercase tracking-wider transition-all cursor-pointer"
                style={{
                  backgroundColor: activeView === 'admin' ? 'rgba(167,139,250,0.15)' : 'transparent',
                  color: activeView === 'admin' ? '#a78bfa' : THEME.muted,
                }}
              >
                <LayoutDashboard className="size-3.5" />
                <span>Admin</span>
              </button>
            </div>

            {/* Channel selector (dual mode) */}
            {isDualModeVal && (
              <div className="flex items-center gap-2">
                <span className="hidden text-[10px] font-medium uppercase tracking-wider sm:inline" style={{ color: THEME.muted }}>
                  Jalur
                </span>
                <Select value={String(myChannel)} onValueChange={handleChannelSelect}>
                  <SelectTrigger
                    size="sm"
                    className="h-7 gap-1 border px-2 text-xs"
                    style={{
                      backgroundColor: THEME.card,
                      borderColor: THEME.border,
                      color: THEME.muted,
                    }}
                  >
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent
                    className="border"
                    style={{
                      backgroundColor: THEME.panel,
                      borderColor: THEME.border,
                    }}
                  >
                    <SelectItem
                      value="1"
                      className="text-xs"
                      style={{ color: THEME.gold }}
                    >
                      Jalur 1 — Kiri
                    </SelectItem>
                    <SelectItem
                      value="2"
                      className="text-xs"
                      style={{ color: THEME.cyan }}
                    >
                      Jalur 2 — Kanan
                    </SelectItem>
                  </SelectContent>
                </Select>
              </div>
            )}

            {/* LAN IP indicator */}
            {lanIP && (
              <button
                onClick={handleCopyIP}
                className="hidden sm:flex shrink-0 items-center gap-1.5 rounded-md px-2 py-1 transition-colors hover:bg-white/10 cursor-pointer"
                title="Klik untuk salin alamat LAN"
              >
                <Wifi className="size-3" style={{ color: THEME.gold }} />
                <span className="text-[10px] font-mono font-medium" style={{ color: THEME.gold }}>
                  {lanIP}:{httpPort}
                </span>
                {copiedIP ? (
                  <Check className="size-3" style={{ color: '#22c55e' }} />
                ) : (
                  <Copy className="size-3" style={{ color: THEME.muted }} />
                )}
              </button>
            )}

            {/* Server status with connection quality */}
            <div className="flex shrink-0 items-center gap-1.5" title={connectionQuality === 'good' ? 'Koneksi LAN stabil' : connectionQuality === 'degraded' ? 'Koneksi tidak stabil — gunakan WiFi 5GHz atau Hotspot langsung' : 'Tidak terhubung — coba Hotspot langsung dari Admin'}>
              <span
                className="size-2 rounded-full"
                style={{
                  backgroundColor: connectionQuality === 'good' ? '#22c55e' : connectionQuality === 'degraded' ? '#f59e0b' : '#ef4444',
                  boxShadow: connectionQuality === 'good'
                    ? '0 0 6px #22c55e88'
                    : connectionQuality === 'degraded'
                      ? '0 0 6px #f59e0b88'
                      : '0 0 6px #ef444488',
                  animation: connectionQuality === 'degraded' ? 'pulse 2s infinite' : 'none',
                }}
              />
              <span className="hidden text-[10px] font-medium sm:inline" style={{ color: connectionQuality === 'good' ? '#22c55e' : connectionQuality === 'degraded' ? '#f59e0b' : THEME.muted }}>
                {connectionQuality === 'good' ? 'LAN' : connectionQuality === 'degraded' ? 'LAN ⚠' : 'OFFLINE'}
              </span>
            </div>
            {/* WiFi congestion tip for degraded/disconnected */}
            {connectionQuality !== 'good' && (
              <div
                className="hidden sm:flex items-center gap-1 rounded px-1.5 py-0.5 text-[9px]"
                style={{ backgroundColor: connectionQuality === 'disconnected' ? 'rgba(239,68,68,0.1)' : 'rgba(245,158,11,0.1)', color: connectionQuality === 'disconnected' ? '#f87171' : '#fbbf24' }}
              >
                <Wifi className="size-3" />
                {connectionQuality === 'disconnected' ? 'Hotspot?' : '5GHz?'}
              </div>
            )}
          </div>
        </header>
      )}

      {/* ── Fullscreen floating toolbar (visible only in fullscreen) ────────── */}
      {appFullscreen && (
        <div
          className="absolute top-0 left-0 right-0 z-50 flex items-center gap-2 px-3 py-2 transition-all duration-300"
          style={{
            backgroundColor: 'rgba(0,0,0,0.75)',
            backdropFilter: 'blur(12px)',
            borderBottom: `1px solid ${THEME.border}44`,
          }}
        >
          {/* Exit fullscreen button */}
          <button
            onClick={toggleAppFullscreen}
            className="flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-semibold cursor-pointer transition-all duration-200 hover:bg-white/20"
            style={{
              backgroundColor: `${THEME.red}33`,
              color: THEME.red,
              border: `1px solid ${THEME.red}55`,
            }}
            title="Keluar Fullscreen (F)"
          >
            <Minimize className="size-3.5" />
            <span>Keluar Fullscreen</span>
          </button>

          {/* Project name */}
          <span className="text-xs font-medium truncate" style={{ color: THEME.muted }}>
            {currentProject?.name ?? 'Saatiril'}
          </span>

          <div className="flex-1" />

          {/* View toggle in fullscreen */}
          <div className="flex items-center gap-1 rounded-lg p-0.5" style={{ backgroundColor: `${THEME.bg}88` }}>
            <button
              onClick={() => setActiveView('live')}
              className="flex items-center gap-1 rounded-md px-2.5 py-1 text-[10px] font-semibold uppercase tracking-wider transition-all cursor-pointer"
              style={{
                backgroundColor: activeView === 'live' ? `${THEME.gold}22` : 'transparent',
                color: activeView === 'live' ? THEME.gold : THEME.muted,
              }}
            >
              <Megaphone className="size-3" />
              <span>Live</span>
            </button>
            <button
              onClick={() => setActiveView('admin')}
              className="flex items-center gap-1 rounded-md px-2.5 py-1 text-[10px] font-semibold uppercase tracking-wider transition-all cursor-pointer"
              style={{
                backgroundColor: activeView === 'admin' ? 'rgba(167,139,250,0.15)' : 'transparent',
                color: activeView === 'admin' ? '#a78bfa' : THEME.muted,
              }}
            >
              <LayoutDashboard className="size-3" />
              <span>Admin</span>
            </button>
          </div>

          {/* MC sidebar toggle in fullscreen */}
          {activeView === 'live' && (
            <button
              onClick={() => setMcSidebarOpen((v) => !v)}
              className="flex items-center gap-1 rounded-lg px-2.5 py-1.5 text-[10px] font-semibold uppercase tracking-wider cursor-pointer transition-all duration-200 hover:bg-white/10"
              style={{
                backgroundColor: mcSidebarOpen ? `${THEME.gold}22` : THEME.panel,
                color: mcSidebarOpen ? THEME.gold : THEME.muted,
                border: `1px solid ${mcSidebarOpen ? THEME.gold : THEME.border}`,
              }}
              title={mcSidebarOpen ? 'Sembunyikan Panel MC' : 'Tampilkan Panel MC'}
            >
              {mcSidebarOpen ? <PanelLeftClose className="size-3.5" /> : <PanelLeftOpen className="size-3.5" />}
              <span>MC</span>
            </button>
          )}

          {/* Connection quality */}
          <div className="flex items-center gap-1">
            <span
              className="size-2 rounded-full"
              style={{
                backgroundColor: connectionQuality === 'good' ? '#22c55e' : connectionQuality === 'degraded' ? '#f59e0b' : '#ef4444',
                boxShadow: `0 0 6px ${connectionQuality === 'good' ? '#22c55e88' : connectionQuality === 'degraded' ? '#f59e0b88' : '#ef444488'}`,
              }}
            />
          </div>
        </div>
      )}

      {/* ── Main Content Area ────────────────────────────────────────────────── */}
      <main className={`flex-1 min-h-0 overflow-hidden ${appFullscreen ? 'pt-0' : ''}`}>
        {/* ── LIVE VIEW: MC sidebar + Operator panel side by side ──────────── */}
        {activeView === 'live' && (
          <div className="flex h-full">
            {/* MC Sidebar (left) — hidden in fullscreen when sidebar is closed */}
            <div
              className="shrink-0 flex flex-col border-r transition-all duration-300 ease-in-out"
              style={{
                width: mcSidebarOpen ? '380px' : '0px',
                backgroundColor: THEME.panel,
                borderColor: THEME.border,
                overflow: 'hidden',
              }}
            >
              {/* MC Sidebar header */}
              <div
                className="shrink-0 flex items-center justify-between px-3 py-2 border-b"
                style={{ borderColor: THEME.border }}
              >
                <div className="flex items-center gap-2">
                  <Megaphone className="size-4" style={{ color: THEME.gold }} />
                  <h2 className="text-xs font-bold uppercase tracking-wider" style={{ color: THEME.gold }}>
                    Panel MC
                  </h2>
                </div>
                <Button
                  variant="ghost"
                  size="icon"
                  className="size-7 text-[#c4b5fd] hover:bg-white/10 hover:text-[#d4af37] cursor-pointer"
                  onClick={() => setMcSidebarOpen(false)}
                  title="Sembunyikan panel MC"
                >
                  <PanelLeftClose className="size-4" />
                </Button>
              </div>
              {/* MC Sidebar content */}
              <div className="flex-1 min-h-0 overflow-hidden">
                <McPanel compact />
              </div>
            </div>

            {/* Operator Panel (right, flex-1) */}
            <div className="flex-1 min-h-0 relative">
              {/* Toggle MC sidebar button (when sidebar is closed, not fullscreen) */}
              {!mcSidebarOpen && !appFullscreen && (
                <Button
                  variant="ghost"
                  size="icon"
                  className="absolute top-2 left-2 z-10 size-8 text-[#c4b5fd] hover:bg-white/10 hover:text-[#d4af37] cursor-pointer"
                  style={{
                    backgroundColor: `${THEME.panel}cc`,
                    borderWidth: 1,
                    borderColor: THEME.border,
                  }}
                  onClick={() => setMcSidebarOpen(true)}
                  title="Tampilkan panel MC"
                >
                  <PanelLeftOpen className="size-4" />
                </Button>
              )}
              <OperatorPanel
                isAppFullscreen={appFullscreen}
                onToggleAppFullscreen={toggleAppFullscreen}
              />
            </div>
          </div>
        )}

        {/* ── ADMIN VIEW: Full-screen admin dashboard ──────────────────────── */}
        {activeView === 'admin' && (
          <div className="h-full overflow-y-auto p-3 sm:p-4 md:p-6">
            <AdminDashboard />
          </div>
        )}
      </main>

      {/* ── Footer (hidden in fullscreen) ──────────────────────────────────── */}
      {!appFullscreen && (
        <footer
          className="shrink-0 border-t"
          style={{
            backgroundColor: `${THEME.panel}88`,
            borderColor: `${THEME.border}44`,
          }}
        >
          <div className="space-y-0.5 px-4 py-1.5 sm:px-6 sm:py-2">
            <SaatirilFooterLines />
          </div>
        </footer>
      )}
    </div>
  )
}

export default MainApp
