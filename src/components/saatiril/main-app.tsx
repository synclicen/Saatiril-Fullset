'use client'

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  ArrowLeft,
  Camera,
  LayoutDashboard,
  Megaphone,
  Loader2,
  Wifi,
  Copy,
  Check,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Input } from '@/components/ui/input'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs'
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

// ─── Tab type ─────────────────────────────────────────────────────────────────
type PanelTab = 'operator' | 'mc' | 'admin'

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
  const [activeTab, setActiveTab] = useState<PanelTab>('operator')
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
          // Try using the hostname from current URL
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

      // Admin: if project has a session password, (re-)set it on the server
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

    // ── Auth event handlers ────────────────────────────────────────────────
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

    // ── Sync React state with socket module state on mount ──────────────────
    queueMicrotask(() => {
      const authState = getSocketAuthState()
      if (authState.connected) {
        setServerConnected(true)
        setConnectionQuality('good')

        // Admin: ensure session password is set on the server
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
        // For admin: merge database with incoming (prevents channel data overwrite in dual mode)
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
        // Ensure frame data is actual base64, not '__FRAME_SAVED__' marker.
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
        // Remove internal fields that should never be sent over the LAN
        delete (safeProject as any)._sessionPasswordHash
        emitLocal('SYNC_DB', { project: safeProject })
      }
    }

    // ── REQUEST_FRAME: Non-admin requests frame data from admin ───────────
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
    // Save project state before navigating back to hub
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
      {/* ── Header ──────────────────────────────────────────────────────────── */}
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

      {/* ── Tab Navigation Bar ───────────────────────────────────────────────── */}
      <nav
        className="shrink-0 border-b z-10"
        style={{
          backgroundColor: THEME.panel,
          borderColor: THEME.border,
        }}
      >
        <Tabs
          value={activeTab}
          onValueChange={(v) => setActiveTab(v as PanelTab)}
          className="flex flex-col gap-0"
        >
          <TabsList
            className="inline-flex h-11 w-full items-center justify-center gap-1 rounded-none border-0 bg-transparent p-1 sm:gap-2"
          >
            <TabsTrigger
              value="operator"
              className="flex-1 h-9 rounded-lg text-xs font-semibold uppercase tracking-wider transition-all data-[state=active]:shadow-md sm:text-sm"
              style={{
                color: activeTab === 'operator' ? THEME.cyan : THEME.muted,
                backgroundColor: activeTab === 'operator' ? `${THEME.cyan}18` : 'transparent',
              }}
            >
              <Camera className="size-4" />
              <span>Operator</span>
            </TabsTrigger>
            <TabsTrigger
              value="mc"
              className="flex-1 h-9 rounded-lg text-xs font-semibold uppercase tracking-wider transition-all data-[state=active]:shadow-md sm:text-sm"
              style={{
                color: activeTab === 'mc' ? THEME.gold : THEME.muted,
                backgroundColor: activeTab === 'mc' ? `${THEME.gold}18` : 'transparent',
              }}
            >
              <Megaphone className="size-4" />
              <span>MC</span>
            </TabsTrigger>
            <TabsTrigger
              value="admin"
              className="flex-1 h-9 rounded-lg text-xs font-semibold uppercase tracking-wider transition-all data-[state=active]:shadow-md sm:text-sm"
              style={{
                color: activeTab === 'admin' ? '#a78bfa' : THEME.muted,
                backgroundColor: activeTab === 'admin' ? 'rgba(167,139,250,0.1)' : 'transparent',
              }}
            >
              <LayoutDashboard className="size-4" />
              <span>Admin</span>
            </TabsTrigger>
          </TabsList>
        </Tabs>
      </nav>

      {/* ── Main Content Area — Full-screen tab content ──────────────────────── */}
      <main className="flex-1 min-h-0 overflow-hidden">
        <div className="h-full overflow-y-auto" style={{ backgroundColor: THEME.bg }}>
          {activeTab === 'operator' && (
            <div className="p-3 sm:p-4 md:p-6">
              <OperatorPanel />
            </div>
          )}
          {activeTab === 'mc' && (
            <div className="p-3 sm:p-4 md:p-6">
              <McPanel />
            </div>
          )}
          {activeTab === 'admin' && (
            <div className="p-3 sm:p-4 md:p-6">
              <AdminDashboard />
            </div>
          )}
        </div>
      </main>

      {/* ── Footer (sticky to bottom) ─────────────────────────────────────── */}
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
    </div>
  )
}

export default MainApp
