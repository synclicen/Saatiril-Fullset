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
  const [forceShowPasswordPrompt, setForceShowPasswordPrompt] = useState(false)

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
      console.log('[SAATIRIL] Socket connected — role:', role, 'authenticated:', isSocketAuthenticated(), 'passwordRequired:', isServerPasswordRequired())
      if (role !== 'admin') {
        // Only request state if authenticated — if password is required,
        // the REQUEST_STATE would be blocked by the server anyway.
        // After successful auth, handleAuthSuccess will send REQUEST_STATE.
        if (isSocketAuthenticated()) {
          emitLocal('REQUEST_STATE', { role, channel: useSaatirilStore.getState().myChannel })
        }
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
      console.log('[SAATIRIL] auth-requirement received:', data.passwordRequired, 'role:', myRoleRef.current)
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
      console.log('[SAATIRIL] auth-success received:', data.role, 'Ch.' + data.channel)
      setSessionPasswordVerified(true)
      setSessionPasswordError(false)
      setAuthFailedReason(null)
      setForceShowPasswordPrompt(false)
      // Now that we're authenticated, request state sync
      if (data.role !== 'admin') {
        emitLocal('REQUEST_STATE', { role: data.role, channel: data.channel })
        // One-shot retry after 1 second in case the first REQUEST_STATE is lost
        // (e.g., admin hasn't finished setting up yet, or network glitch)
        setTimeout(() => {
          if (!useSaatirilStore.getState().currentProject && isSocketAuthenticated()) {
            console.log('[SAATIRIL] Retrying REQUEST_STATE after auth success (1s delay)')
            emitLocal('REQUEST_STATE', { role: data.role, channel: data.channel })
          }
        }, 1000)
      }
    }

    const handleAuthFailed = (data: { reason: string }) => {
      console.log('[SAATIRIL] auth-failed received:', data.reason, 'role:', myRoleRef.current)
      setSessionPasswordVerified(false)
      setSessionPasswordError(true)
      setAuthFailedReason(data.reason)
      // CRITICAL FIX: If auth failed due to session password requirement,
      // also set serverRequiresPassword so the password prompt shows
      // even if the auth-requirement event was missed or arrived before
      // the React handlers were registered.
      if (data.reason === 'session_password_required') {
        setServerRequiresPassword(true)
      }
    }

    socket.on('connect', handleConnect)
    socket.on('disconnect', handleDisconnect)
    socket.on('auth-requirement', handleAuthRequirement)
    socket.on('auth-success', handleAuthSuccess)
    socket.on('auth-failed', handleAuthFailed)

    // ── Sync React state with socket module state on mount ──────────────────
    // CRITICAL FIX: When MainApp mounts and the socket is already connected
    // (e.g., from a previous mount or because the admin navigated from
    // project-setup), the `connect` event won't fire again. We need to
    // read the current auth state from the socket module and sync it to
    // React state. Without this, the React state would have stale initial
    // values (serverRequiresPassword=false, sessionPasswordVerified=false)
    // while the socket module has the correct state.
    //
    // Using queueMicrotask to avoid the React lint warning about calling
    // setState synchronously within an effect body. The microtask runs
    // after the current execution context, which is safe because the
    // socket state we're reading is already settled.
    queueMicrotask(() => {
      const authState = getSocketAuthState()
      console.log('[SAATIRIL] Mount sync — authState:', {
        connected: authState.connected,
        authenticated: authState.authenticated,
        passwordRequired: authState.passwordRequired,
        role: myRoleRef.current,
      })
      if (authState.connected) {
        setServerConnected(true)
        setConnectionQuality('good')

        // If socket is already connected but handleConnect hasn't run for this
        // mount (because there's no new `connect` event), we need to perform
        // the same logic that handleConnect does.
        const role = myRoleRef.current
        if (role === 'admin') {
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
        } else {
          // Non-admin: only request state sync if authenticated
          // FIX: Don't send REQUEST_STATE if not authenticated — the server
          // would block it anyway (role='auth-pending'), and it creates
          // confusing log messages.
          if (authState.authenticated) {
            emitLocal('REQUEST_STATE', { role, channel: useSaatirilStore.getState().myChannel })
          }
        }
      }
      if (authState.passwordRequired) {
        setServerRequiresPassword(true)
        // CRITICAL FIX: If server requires password, non-admin clients
        // must NOT be considered verified, even if authState.authenticated
        // is true from a previous session. The server may have just received
        // a new password from the admin, invalidating previous auth.
        if (myRoleRef.current !== 'admin') {
          setSessionPasswordVerified(false)
        }
      } else {
        // No password required — if authenticated, mark as verified
        if (authState.authenticated && myRoleRef.current !== 'admin') {
          setSessionPasswordVerified(true)
        }
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

  // ── Periodic auth state sync (safety net for missed events) ──────────────
  // Every 2 seconds, sync the React state with the socket module state.
  // This ensures the password prompt shows even if the 'auth-requirement'
  // event was missed or arrived before the React handlers were registered.
  // This is CRITICAL for the password flow — without it, MC can get stuck
  // at the "Menunggu data proyek" screen when admin uses a password.
  useEffect(() => {
    const syncInterval = setInterval(() => {
      if (myRole === 'admin') return // Skip for admin

      const socketPasswordRequired = isServerPasswordRequired()
      const socketAuthenticated = isSocketAuthenticated()

      // Sync serverRequiresPassword from socket module
      if (socketPasswordRequired !== serverRequiresPassword) {
        console.log(`[SAATIRIL] Auth state sync: serverRequiresPassword ${serverRequiresPassword} → ${socketPasswordRequired}`)
        setServerRequiresPassword(socketPasswordRequired)
      }

      // If server requires password and we're not verified, ensure we're not
      // incorrectly marked as verified. Also force-show the password prompt
      // if we detect a password requirement that the React state missed.
      if (socketPasswordRequired && !socketAuthenticated) {
        if (sessionPasswordVerified) {
          console.log('[SAATIRIL] Auth state sync: resetting sessionPasswordVerified (server requires password, not authenticated)')
          setSessionPasswordVerified(false)
        }
        // If we're stuck on the waiting screen but the server requires a password,
        // automatically show the password prompt
        if (!forceShowPasswordPrompt && !currentProject) {
          console.log('[SAATIRIL] Auth state sync: forcing password prompt (server requires password, no project data)')
          setForceShowPasswordPrompt(true)
        }
      }

      // If server does NOT require password and we ARE authenticated, mark as verified
      if (!socketPasswordRequired && socketAuthenticated && !sessionPasswordVerified) {
        console.log('[SAATIRIL] Auth state sync: marking verified (no password required, authenticated)')
        setSessionPasswordVerified(true)
        setSessionPasswordError(false)
      }
    }, 2000)
    return () => clearInterval(syncInterval)
  }, [myRole, serverRequiresPassword, sessionPasswordVerified, forceShowPasswordPrompt, currentProject])

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
          // First-time project data for MC/Operator
          // SAFETY: If the incoming project has a __FRAME_SAVED__ marker (shouldn't happen
          // on first SYNC_DB from REQUEST_STATE, but can happen from broadcast SYNC_DB),
          // try to restore from localStorage. If not found, request the frame from admin.
          let projectToSet = data.project
          if (projectToSet.config.frame === '__FRAME_SAVED__') {
            const savedFrame = typeof window !== 'undefined'
              ? localStorage.getItem(`saatiril_frame_${projectToSet.id}`)
              : null
            if (savedFrame) {
              projectToSet = {
                ...projectToSet,
                config: {
                  ...projectToSet.config,
                  frame: savedFrame,
                },
              }
              console.log('[SAATIRIL] SYNC_DB first-time: __FRAME_SAVED__ marker replaced with actual frame data from localStorage')
            } else {
              // No frame in localStorage — replace marker with null for now,
              // and request the frame from admin via REQUEST_FRAME event.
              projectToSet = {
                ...projectToSet,
                config: {
                  ...projectToSet.config,
                  frame: null,
                },
              }
              console.log('[SAATIRIL] SYNC_DB first-time: __FRAME_SAVED__ marker found but no localStorage data — requesting frame from admin')
              // Request frame from admin (only if authenticated)
              if (isSocketAuthenticated()) {
                emitLocal('REQUEST_FRAME', { projectId: projectToSet.id, requesterRole: role })
              }
            }
          }
          updateCurrentProject(projectToSet)
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
      // Use getState() for synchronous, always-fresh reads — refs can be stale
      // if the handler fires before the next React render cycle updates them.
      const role = useSaatirilStore.getState().myRole
      const curProj = useSaatirilStore.getState().currentProject
      if (role === 'admin' && curProj) {
        // DO NOT strip frame for REQUEST_STATE responses — new clients need the full frame data.
        // stripFrameForSync is only for subsequent SYNC_DB updates where clients already have the frame.
        // See the NOTE in stripFrameForSync() documentation.
        //
        // SECURITY: Strip the session password from REQUEST_STATE — the server now handles
        // password validation, so we never send the actual password over the LAN.
        // Instead, we send a flag so clients know a password is required.

        // Ensure frame data is actual base64, not '__FRAME_SAVED__' marker.
        // The store's setCurrentProject/updateCurrentProject should restore from
        // separate localStorage, but as a safety net, check and restore here too.
        // We try TWICE: first from the dedicated frame key, then from the projects
        // JSON as a last resort (in case the frame was saved there but the separate
        // key was cleared).
        let frameToSend = curProj.config.frame
        if (frameToSend === '__FRAME_SAVED__') {
          // Try #1: dedicated frame localStorage key
          let savedFrame = typeof window !== 'undefined'
            ? localStorage.getItem(`saatiril_frame_${curProj.id}`)
            : null
          // Try #2: parse the projects JSON and look for the frame there
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
            // Also save to the dedicated key so future requests don't need the fallback
            try { localStorage.setItem(`saatiril_frame_${curProj.id}`, savedFrame) } catch {}
            console.log('[SAATIRIL] REQUEST_STATE: Restored frame from localStorage for sync')
          } else {
            frameToSend = null // No frame available — don't send marker
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
          // Strip photo history photos (they're already sent via PHOTOS_SAVED events)
          photoHistory: curProj.photoHistory.map(h => ({ ...h, photos: [] })),
        }
        // Remove internal fields that should never be sent over the LAN
        delete (safeProject as any)._sessionPasswordHash
        emitLocal('SYNC_DB', { project: safeProject })
      }
    }

    // ── REQUEST_FRAME: Non-admin requests frame data from admin ───────────
    // When an operator/MC connects and receives a project with __FRAME_SAVED__
    // marker (and localStorage has no frame), they can request the frame
    // specifically from the admin. This is a targeted request that doesn't
    // require a full SYNC_DB cycle.
    const handleRequestFrame = (data: { projectId: string; requesterRole: string }) => {
      const role = useSaatirilStore.getState().myRole
      const curProj = useSaatirilStore.getState().currentProject
      if (role !== 'admin' || !curProj || curProj.id !== data.projectId) return

      // Try to get the frame from current project or localStorage
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

    // ── FRAME_DATA: Non-admin receives frame data from admin ───────────────
    const handleFrameData = (data: { projectId: string; frame: string }) => {
      const role = myRoleRef.current
      const curProj = useSaatirilStore.getState().currentProject
      if (role === 'admin' || !curProj || curProj.id !== data.projectId) return

      if (data.frame && data.frame !== '__FRAME_SAVED__') {
        console.log('[SAATIRIL] FRAME_DATA: Received frame from admin', `(${Math.round(data.frame.length / 1024)}KB)`)
        // Save to localStorage for persistence
        try {
          localStorage.setItem(`saatiril_frame_${data.projectId}`, data.frame)
        } catch (e) {
          console.error('[SAATIRIL] FRAME_DATA: Failed to save frame to localStorage:', e)
        }
        // Update the current project with the frame data
        updateCurrentProject({
          ...curProj,
          config: { ...curProj.config, frame: data.frame },
        })
      }
    }

    onLocal('SYNC_DB', handleSyncDb)
    onLocal('REQUEST_STATE', handleRequestState)
    onLocal('REQUEST_FRAME', handleRequestFrame)
    onLocal('FRAME_DATA', handleFrameData)

    return () => {
      offLocal('SYNC_DB', handleSyncDb)
      offLocal('REQUEST_STATE', handleRequestState)
      offLocal('REQUEST_FRAME', handleRequestFrame)
      offLocal('FRAME_DATA', handleFrameData)
    }
  }, [updateCurrentProject])

  // ── Non-admin: load localStorage + request state from admin ────────────────
  useEffect(() => {
    if (myRole === 'admin') return

    // Try to recover project from localStorage first (for reconnection/recovery)
    loadProjectsFromStorage()

    // Request state from admin via socket (only if authenticated)
    if (isSocketAuthenticated()) {
      emitLocal('REQUEST_STATE', { role: myRole, channel: myChannel })
    }

    // Periodic retry while we don't have a project AND we're authenticated
    // (unauthenticated clients can't relay messages, so REQUEST_STATE would be blocked)
    const requestInterval = setInterval(() => {
      const state = useSaatirilStore.getState()
      if (!state.currentProject && isSocketAuthenticated()) {
        emitLocal('REQUEST_STATE', { role: myRole, channel: myChannel })
      }
      // Also check if we have a project but the frame is missing — request it
      if (state.currentProject && !state.currentProject.config.frame && isSocketAuthenticated()) {
        console.log('[SAATIRIL] Periodic check: requesting frame from admin (frame is missing)')
        emitLocal('REQUEST_FRAME', { projectId: state.currentProject.id, requesterRole: myRole })
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
  // 2. OR the socket module reports password is required (fallback for when
  //    the React event handler missed the auth-requirement event)
  // 3. OR we have a __PASSWORD_SET__ marker in our project config and we're not authenticated
  // 4. OR auth-failed was received with session_password_required reason
  // 5. OR the forceShowPasswordPrompt flag was set (by the waiting screen or periodic sync)
  // The serverRequiresPassword flag is set by the 'auth-requirement' event on connect
  // AND by the 'auth-failed' handler for robustness.
  const needsPassword = myRole !== 'admin' && !sessionPasswordVerified && (
    serverRequiresPassword ||
    isServerPasswordRequired() ||
    (currentProject?.config?.sessionPassword === '__PASSWORD_SET__' && !isSocketAuthenticated()) ||
    (sessionPasswordError && authFailedReason === 'session_password_required') ||
    forceShowPasswordPrompt
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
  // Also check if server requires password — if so, show a password hint
  // instead of the generic waiting message. This handles the case where
  // the auth-requirement event was received by the socket module but the
  // React state hasn't synced yet, or the needsPassword check failed.
  // Note: !isSynced already implies myRole !== 'admin' (because isSynced is
  // true when myRole === 'admin'), so we don't need the extra check.
  if (!isSynced) {
    const waitingScreenPasswordRequired = serverRequiresPassword || isServerPasswordRequired()
    // If server requires password, show password-required waiting screen
    if (waitingScreenPasswordRequired) {
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
            <h2 className="text-xl font-bold text-white">Password Diperlukan</h2>
            <p className="mt-2 text-sm" style={{ color: THEME.muted }}>
              Sesi ini dilindungi password. Masukkan password untuk bergabung.
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

    // Generic waiting screen (no password required)
    return (
      <div
        className="flex h-dvh flex-col items-center justify-center gap-6 px-6"
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
        {/* Fallback button: if server requires password but the needsPassword
            check failed to detect it, allow the user to manually show the
            password prompt */}
        {isServerPasswordRequired() && (
          <Button
            variant="outline"
            className="gap-2 border-[#533485] text-sm"
            style={{ color: THEME.gold, borderColor: THEME.border }}
            onClick={() => setForceShowPasswordPrompt(true)}
          >
            <Lock className="size-4" />
            Masukkan Password
          </Button>
        )}
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
