'use client'

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  ArrowLeft,
  Camera,
  LayoutDashboard,
  Megaphone,
  Radio,
  Loader2,
  Wifi,
  Copy,
  Check,
  Lock,
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
import { useSaatirilStore, type AppTab, type Role, type Project, type CameraMode, mergeDatabases, stripFrameForSync, preserveFrameOnSync, preservePhotoHistoryOnSync, isDualMode, isPhotoshootMode } from '@/store/use-saatiril-store'
import { connectSocket, onLocal, offLocal, emitLocal, getSocket, getConnectionHealth, setSessionPassword, clearSessionPassword, reidentifyWithPassword, isSocketAuthenticated, isServerPasswordRequired, resendSessionPasswordHash } from '@/lib/socket'

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

// ─── Tab configuration ────────────────────────────────────────────────────────
interface TabConfig {
  id: AppTab
  label: string
  icon: React.ReactNode
}

const TABS: TabConfig[] = [
  { id: 'admin', label: 'Admin Dashboard', icon: <LayoutDashboard className="size-4" /> },
  { id: 'mc', label: 'Panel MC', icon: <Megaphone className="size-4" /> },
  { id: 'operator', label: 'Panel Operator', icon: <Camera className="size-4" /> },
]

// ─── Mode badge text helper ───────────────────────────────────────────────────
function getModeBadgeText(role: Role, channel: number, mode: CameraMode): string {
  switch (role) {
    case 'admin':
      return 'Admin Control Center'
    case 'mc':
      return isPhotoshootMode(mode) ? `Layar MC` : `Layar MC - Jalur ${channel}`
    case 'operator':
      return isPhotoshootMode(mode) ? `Kamera ${channel}` : `Kamera - Jalur ${channel}`
  }
}

// ─── Component ────────────────────────────────────────────────────────────────
export function MainApp() {
  // ── License gate ─────────────────────────────────────────────────────────
  const [licenseValid, setLicenseValid] = useState(false)

  // ── Store bindings ─────────────────────────────────────────────────────────
  const currentProject = useSaatirilStore((s) => s.currentProject)
  const updateCurrentProject = useSaatirilStore((s) => s.updateCurrentProject)
  const myRole = useSaatirilStore((s) => s.myRole)
  const myChannel = useSaatirilStore((s) => s.myChannel)
  const currentTab = useSaatirilStore((s) => s.currentTab)
  const setMyRole = useSaatirilStore((s) => s.setMyRole)
  const setMyChannel = useSaatirilStore((s) => s.setMyChannel)
  const setCurrentScreen = useSaatirilStore((s) => s.setCurrentScreen)
  const setCurrentTab = useSaatirilStore((s) => s.setCurrentTab)
  const loadProjectsFromStorage = useSaatirilStore((s) => s.loadProjectsFromStorage)

  // ── Local state ────────────────────────────────────────────────────────────
  const [serverConnected, setServerConnected] = useState(false)
  const [connectionQuality, setConnectionQuality] = useState<'good' | 'degraded' | 'disconnected'>('disconnected')
  const [lanIP, setLanIP] = useState<string>('')
  const [copiedIP, setCopiedIP] = useState(false)
  const [sessionPasswordInput, setSessionPasswordInput] = useState('')
  const [sessionPasswordVerified, setSessionPasswordVerified] = useState(false)
  const [sessionPasswordError, setSessionPasswordError] = useState(false)
  const [serverRequiresPassword, setServerRequiresPassword] = useState(false)
  const [authFailedReason, setAuthFailedReason] = useState<string | null>(null)

  // ── Refs for stable event handlers ─────────────────────────────────────────
  const myRoleRef = useRef(myRole)
  const currentProjectRef = useRef(currentProject)
  useEffect(() => { myRoleRef.current = myRole }, [myRole])
  useEffect(() => { currentProjectRef.current = currentProject }, [currentProject])

  // ── Derived values ─────────────────────────────────────────────────────────
  const isDualModeVal = isDualMode(currentProject?.config.mode ?? 'single')
  // Non-admin is synced when they have a project (from server or localStorage)
  const isSynced = myRole === 'admin' || currentProject !== null
  const effectiveTab: AppTab = useMemo(() => {
    if (myRole === 'admin') return currentTab
    if (myRole === 'mc') return 'mc'
    return 'operator'
  }, [myRole, currentTab])

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
    const roleParam = params.get('role') as Role | null
    const channelParam = params.get('channel')

    if (roleParam && ['admin', 'mc', 'operator'].includes(roleParam)) {
      setMyRole(roleParam)
    }
    if (channelParam) {
      const ch = parseInt(channelParam, 10)
      if (ch >= 1 && ch <= 2) {
        setMyChannel(ch)
      }
    }
  }, [setMyRole, setMyChannel])

  // ── Socket initialization ─────────────────────────────────────────────────
  useEffect(() => {
    const socket = connectSocket()

    const handleConnect = () => {
      setServerConnected(true)
      setConnectionQuality('good')

      // On (re)connection, re-request state sync from admin to ensure we have latest data
      const role = myRoleRef.current
      if (role !== 'admin') {
        emitLocal('REQUEST_STATE', { role, channel: useSaatirilStore.getState().myChannel })
      } else {
        // Admin: if project has a session password, (re-)set it on the server
        // This is critical for reconnection — the server may have restarted or
        // the password may not have been set yet.
        const curProj = useSaatirilStore.getState().currentProject
        if (curProj) {
          if (curProj.config.sessionPassword && curProj.config.sessionPassword !== '__PASSWORD_SET__') {
            // Plaintext password (fresh from project creation) — hash and send
            setSessionPassword(curProj.config.sessionPassword, curProj.id)
          } else if (curProj.config.sessionPassword === '__PASSWORD_SET__') {
            // Password marker — look up the stored hash and re-send it
            const storedHash = typeof window !== 'undefined'
              ? localStorage.getItem(`saatiril_pwdhash_${curProj.id}`)
              : null
            if (storedHash && storedHash !== '__PWD_PENDING__') {
              resendSessionPasswordHash(storedHash)
              console.log('[SAATIRIL] Admin re-sent stored password hash to server on reconnect')
            }
          }
        }
      }

      console.log('[SAATIRIL] Connected — requesting state sync')
    }
    const handleDisconnect = () => {
      setServerConnected(false)
      setConnectionQuality('disconnected')
    }

    // ── Auth event handlers ────────────────────────────────────────────────
    const handleAuthRequirement = (data: { passwordRequired: boolean }) => {
      setServerRequiresPassword(data.passwordRequired)
      if (data.passwordRequired && myRoleRef.current !== 'admin') {
        // Server requires password — show prompt if not yet verified
        setSessionPasswordVerified(false)
        // If the server now requires a password and we were previously
        // authenticated, we need to re-authenticate. The server may have
        // just received the password from the admin.
        // Don't re-identify here — the user needs to enter the password first.
      } else if (!data.passwordRequired && myRoleRef.current !== 'admin') {
        // Password no longer required — mark as verified so we don't show prompt
        setSessionPasswordVerified(true)
        setSessionPasswordError(false)
      }
    }

    const handleAuthSuccess = (data: { role: string; channel: number }) => {
      setSessionPasswordVerified(true)
      setSessionPasswordError(false)
      setAuthFailedReason(null)
      // Now that we're authenticated, request state sync
      if (data.role !== 'admin') {
        emitLocal('REQUEST_STATE', { role: data.role, channel: data.channel })
      }
    }

    const handleAuthFailed = (data: { reason: string }) => {
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
      if (socket.connected) {
        setServerConnected(true)
        setConnectionQuality('good')
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
      const role = myRoleRef.current
      // Read latest state synchronously (avoids stale currentProjectRef race
      // when SYNC_DB echo arrives right after a local state update like reset).
      const curProj = useSaatirilStore.getState().currentProject

      if (role !== 'admin' && data.project) {
        // For MC/Operator: merge incoming database with local (prevents data regression)
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
        } else {
          updateCurrentProject(data.project)
        }
      } else if (role === 'admin' && data.project) {
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
      const role = myRoleRef.current
      // Read from store directly (not ref) to get the latest frame data
      const curProj = useSaatirilStore.getState().currentProject
      if (role === 'admin' && curProj) {
        // DO NOT strip frame for REQUEST_STATE responses — new clients need the full frame data.
        // stripFrameForSync is only for subsequent SYNC_DB updates where clients already have the frame.
        // See the NOTE in stripFrameForSync() documentation.
        //
        // SECURITY: Strip the session password from REQUEST_STATE — the server now handles
        // password validation, so we never send the actual password over the LAN.
        // Instead, we send a flag so clients know a password is required.
        const safeProject = {
          ...curProj,
          config: {
            ...curProj.config,
            sessionPassword: curProj.config.sessionPassword ? '__PASSWORD_SET__' : undefined,
          },
          // Strip photo history photos (they're already sent via PHOTOS_SAVED events)
          photoHistory: curProj.photoHistory.map(h => ({ ...h, photos: [] })),
        }
        // Remove internal fields that should never be sent over the LAN
        delete (safeProject as any)._sessionPasswordHash
        emitLocal('SYNC_DB', { project: safeProject })
      }
    }

    onLocal('SYNC_DB', handleSyncDb)
    onLocal('REQUEST_STATE', handleRequestState)

    return () => {
      offLocal('SYNC_DB', handleSyncDb)
      offLocal('REQUEST_STATE', handleRequestState)
    }
  }, [updateCurrentProject])

  // ── Non-admin: load localStorage + request state from admin ────────────────
  useEffect(() => {
    if (myRole === 'admin') return

    // Try to recover project from localStorage first (for reconnection/recovery)
    loadProjectsFromStorage()

    // Request state from admin via socket
    emitLocal('REQUEST_STATE', { role: myRole, channel: myChannel })

    // Periodic retry while we don't have a project
    const requestInterval = setInterval(() => {
      if (!useSaatirilStore.getState().currentProject) {
        emitLocal('REQUEST_STATE', { role: myRole, channel: myChannel })
      }
    }, 3000)

    return () => clearInterval(requestInterval)
  }, [myRole, myChannel, loadProjectsFromStorage])

  // ── Handlers ──────────────────────────────────────────────────────────────
  const handleBack = useCallback(() => {
    // Save project state before navigating back to hub
    const store = useSaatirilStore.getState()
    if (store.currentProject) {
      store.saveProjectsToStorageNow()
    }
    // Reset to admin role and tab when going back to hub
    store.setMyRole('admin')
    store.setCurrentTab('admin')
    store.setMyChannel(1)
    store.resetOpState()
    setCurrentScreen('hub')
  }, [setCurrentScreen])

  const handleTabChange = useCallback(
    (tab: AppTab) => {
      if (myRole === 'admin') {
        setCurrentTab(tab)
      }
    },
    [myRole, setCurrentTab],
  )

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

  // ── Render: Session password prompt (non-admin, when server requires password) ─
  // Show prompt when:
  // 1. Server says password is required (primary check — server-side validation)
  // 2. OR we have a __PASSWORD_SET__ marker in our project config and we're not authenticated
  // The serverRequiresPassword flag is set by the 'auth-requirement' event on connect.
  const needsPassword = myRole !== 'admin' && !sessionPasswordVerified && (
    serverRequiresPassword ||
    (currentProject?.config?.sessionPassword === '__PASSWORD_SET__' && !isSocketAuthenticated())
  )
  if (needsPassword) {
    return (
      <div
        className="flex h-dvh flex-col items-center justify-center gap-6 px-6"
        style={{ backgroundColor: THEME.bg }}
      >
        <div
          className="flex size-20 items-center justify-center rounded-full"
          style={{ backgroundColor: `${THEME.gold}15`, borderWidth: 1, borderColor: `${THEME.gold}33` }}
        >
          <Lock className="size-10" style={{ color: THEME.gold }} />
        </div>
        <div className="text-center">
          <h2 className="text-xl font-bold text-white">Password Sesi Diperlukan</h2>
          <p className="mt-2 text-sm" style={{ color: THEME.muted }}>
            Admin telah mengatur password untuk sesi ini.
            Masukkan password untuk bergabung.
          </p>
        </div>
        <div className="w-full max-w-xs space-y-3">
          <Input
            type="password"
            placeholder="Masukkan password sesi..."
            value={sessionPasswordInput}
            onChange={(e) => { setSessionPasswordInput(e.target.value); setSessionPasswordError(false) }}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && sessionPasswordInput.trim()) {
                reidentifyWithPassword(sessionPasswordInput.trim())
              }
            }}
            className="h-10 text-center font-mono text-sm"
            style={{
              backgroundColor: THEME.bg,
              borderColor: sessionPasswordError ? THEME.red : THEME.border,
              color: THEME.gold,
            }}
          />
          {sessionPasswordError && (
            <p className="text-center text-xs font-medium" style={{ color: THEME.red }}>
              {authFailedReason === 'session_password_required'
                ? 'Password salah. Coba lagi.'
                : 'Gagal mengautentikasi. Coba lagi.'}
            </p>
          )}
          <Button
            className="w-full h-10 font-semibold"
            style={{ backgroundColor: THEME.gold, color: THEME.bg }}
            onClick={() => {
              if (sessionPasswordInput.trim()) {
                reidentifyWithPassword(sessionPasswordInput.trim())
              }
            }}
          >
            Bergabung
          </Button>
        </div>
        <Badge
          className="gap-1.5 border-[#533485] bg-[#2a164a] px-3 py-1 text-xs"
          style={{ color: THEME.muted }}
        >
          <Radio className="size-3" style={{ color: THEME.gold }} />
          {myRole === 'mc' ? 'MC' : 'Operator'} — Jalur {myChannel}
        </Badge>
      </div>
    )
  }

  // ── Render: Sync waiting screen ───────────────────────────────────────────
  if (!isSynced && myRole !== 'admin') {
    return (
      <div
        className="flex h-full flex-col items-center justify-center gap-6 px-6"
        style={{ backgroundColor: THEME.bg }}
      >
        <div className="flex size-20 items-center justify-center rounded-full border-2 border-[#533485] bg-[#2a164a]">
          <Loader2 className="size-10 animate-spin" style={{ color: THEME.gold }} />
        </div>
        <div className="text-center">
          <h2 className="text-xl font-bold text-white">Sinkronisasi Data</h2>
          <p className="mt-2 text-sm" style={{ color: THEME.muted }}>
            Menunggu data proyek dari Admin...
          </p>
          <p className="mt-1 text-xs" style={{ color: `${THEME.muted}88` }}>
            Pastikan Admin sudah membuka proyek di jaringan LAN yang sama.
          </p>
        </div>
        <Badge
          className="gap-1.5 border-[#533485] bg-[#2a164a] px-3 py-1 text-xs"
          style={{ color: THEME.muted }}
        >
          <Radio className="size-3" style={{ color: THEME.gold }} />
          {myRole === 'mc' ? 'MC' : 'Operator'} — Jalur {myChannel}
        </Badge>
      </div>
    )
  }

  // ── Render: Tab content ───────────────────────────────────────────────────
  const renderTabContent = () => {
    switch (effectiveTab) {
      case 'admin':
        return <AdminDashboard />
      case 'mc':
        return <McPanel readOnly={myRole === 'admin'} />
      case 'operator':
        return <OperatorPanel readOnly={myRole === 'admin'} />
    }
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
        <div className="flex flex-col gap-0">
          {/* Top row: back, project name, badge, server status */}
          <div className="flex items-center gap-2 px-2 py-2 sm:gap-3 sm:px-4 md:gap-4 md:px-6">
            {/* Back button — only for Admin (MC/Operator should never navigate back to hub) */}
            {myRole === 'admin' && (
              <Button
                variant="ghost"
                size="icon"
                onClick={handleBack}
                className="shrink-0 text-[#c4b5fd] hover:bg-white/10 hover:text-[#d4af37]"
                aria-label="Kembali ke hub"
              >
                <ArrowLeft className="size-5" />
              </Button>
            )}

            {/* Project name */}
            <div className="min-w-0 flex-1">
              <h1 className="truncate text-sm font-bold text-white sm:text-base">
                {currentProject?.name ?? 'Saatiril'}
              </h1>
            </div>

            {/* Mode badge — shorter on mobile */}
            <Badge
              className="shrink-0 gap-1 border-none px-2 py-1 text-[9px] font-semibold uppercase tracking-wider sm:text-[10px] md:text-xs md:gap-1.5 md:px-2.5"
              style={{
                backgroundColor: myRole === 'admin' ? `${THEME.gold}22` : myRole === 'mc' ? `${THEME.gold}22` : `${THEME.cyan}22`,
                color: myRole === 'operator' ? THEME.cyan : THEME.gold,
              }}
            >
              {myRole === 'admin' && <LayoutDashboard className="size-3" />}
              {myRole === 'mc' && <Megaphone className="size-3" />}
              {myRole === 'operator' && <Camera className="size-3" />}
              <span className="hidden md:inline">{getModeBadgeText(myRole, myChannel, currentProject?.config.mode ?? 'single')}</span>
              <span className="md:hidden">{myRole === 'admin' ? 'Admin' : myRole === 'mc' ? `MC-${myChannel}` : `Op-${myChannel}`}</span>
            </Badge>

            {/* Channel indicator (MC/Operator only) — hidden on mobile since badge shows it */}
            {myRole !== 'admin' && (
              <Badge
                className="hidden sm:flex shrink-0 gap-1 border-none px-2 py-0.5 text-[10px] font-bold md:text-xs"
                style={{
                  backgroundColor: myChannel === 1 ? `${THEME.gold}22` : `${THEME.cyan}22`,
                  color: myChannel === 1 ? THEME.gold : THEME.cyan,
                }}
              >
                <Radio className="size-3" />
                Jalur {myChannel}
              </Badge>
            )}

            {/* LAN IP indicator — hidden on mobile to save space */}
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
            <div className="flex shrink-0 items-center gap-1.5" title={connectionQuality === 'good' ? 'Koneksi LAN stabil' : connectionQuality === 'degraded' ? 'Koneksi tidak stabil' : 'Tidak terhubung'}>
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
          </div>

          {/* Tab navigation (admin only) — compact on mobile */}
          {myRole === 'admin' && (
            <div className="flex items-center gap-1 border-t px-2 py-1 sm:px-4 md:px-6" style={{ borderColor: `${THEME.border}66` }}>
              {TABS.map((tab) => {
                const isActive = effectiveTab === tab.id
                return (
                  <button
                    key={tab.id}
                    onClick={() => handleTabChange(tab.id)}
                    className={`
                      flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-semibold
                      transition-all duration-200 sm:text-sm
                      ${
                        isActive
                          ? 'text-[#1a0b2e] shadow-md'
                          : 'text-[#c4b5fd] hover:bg-white/5 hover:text-white'
                      }
                    `}
                    style={
                      isActive
                        ? { backgroundColor: THEME.gold }
                        : undefined
                    }
                    aria-selected={isActive}
                    role="tab"
                  >
                    {tab.icon}
                    <span className="hidden sm:inline">{tab.label}</span>
                  </button>
                )
              })}

              {/* Channel selector (admin, dual mode, on MC or Operator tab) */}
              {isDualModeVal && (effectiveTab === 'mc' || effectiveTab === 'operator') && (
                <div className="ml-auto flex items-center gap-2">
                  <span className="text-[10px] font-medium uppercase tracking-wider" style={{ color: THEME.muted }}>
                    Jalur Simulasi
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
            </div>
          )}
        </div>
      </header>

      {/* ── Main Content Area ───────────────────────────────────────────────── */}
      <main className="flex-1 min-h-0 overflow-hidden">
        <div
          key={effectiveTab}
          className="h-full animate-in fade-in slide-in-from-y-2 duration-300"
        >
          {renderTabContent()}
        </div>
      </main>

      {/* ── Footer (sticky to bottom) — hidden on mobile Operator/MC to save space */}
      {!(myRole !== 'admin') && (
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
