'use client'

import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react'
import { Card, CardContent } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Input } from '@/components/ui/input'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {
  Camera,
  CheckCircle2,
  Clock,
  Loader2,

  Search,
  User,
  Video,
  VideoOff,
  Aperture,
  Frame,
  List,
  X,
  SwitchCamera,
  Brain,
  Sparkles,
  Zap,
  Timer,
  Hand,
  Grid3x3,
  Maximize,
  Minimize,
} from 'lucide-react'
import {
  useSaatirilStore,
  type Student,
  type StudentStatus,
  type PhotoHistoryItem,
  type CameraMode,
  mergeDatabases,
  stripFrameForSync,
  preserveFrameOnSync,
  preservePhotoHistoryOnSync,
  mergeCaptureVersions,
  isPhotoshootMode,
  isDualPhotoshootMode,
} from '@/store/use-saatiril-store'
import { emitLocal, onLocal, offLocal } from '@/lib/socket'
import { useIsMobile } from '@/hooks/use-mobile'
import { NetworkQualityBadge } from '@/components/saatiril/network-quality-badge'
import { useAIDetection, type AIMomentEvent } from '@/hooks/use-ai-detection'
import { usePalmDetection } from '@/hooks/use-palm-detection'
import { useToast } from '@/hooks/use-toast'
import { ResizablePanelGroup, ResizablePanel, ResizableHandle } from '@/components/ui/resizable'

// ─── Theme tokens ───────────────────────────────────────────────────────────
const THEME = {
  bg: '#1a0b2e',
  panel: '#2a164a',
  card: '#3b2263',
  border: '#533485',
  gold: '#d4af37',
  muted: '#c4b5fd',
  red: '#ef4444',
} as const

// ─── Shutter mode types ────────────────────────────────────────────────────
type ShutterMode = 'manual' | 'timer-3' | 'timer-5' | 'timer-10' | 'ai'

const SHUTTER_MODES: { id: ShutterMode; label: string; shortLabel: string; icon: React.ReactNode; modesAllowed?: CameraMode[] }[] = [
  { id: 'manual', label: 'Manual', shortLabel: 'Manual', icon: <Camera className="size-3" /> },
  { id: 'timer-3', label: 'Timer 3 detik', shortLabel: '3s', icon: <Timer className="size-3" /> },
  { id: 'timer-5', label: 'Timer 5 detik', shortLabel: '5s', icon: <Timer className="size-3" /> },
  { id: 'timer-10', label: 'Timer 10 detik', shortLabel: '10s', icon: <Timer className="size-3" /> },
  { id: 'ai', label: 'AI Pintar', shortLabel: 'AI', icon: <Brain className="size-3" />, modesAllowed: ['single', 'dual'] },
]

function getTimerDuration(mode: ShutterMode): number {
  switch (mode) {
    case 'timer-3': return 3
    case 'timer-5': return 5
    case 'timer-10': return 10
    default: return 0
  }
}

function isTimerMode(mode: ShutterMode): boolean {
  return mode === 'timer-3' || mode === 'timer-5' || mode === 'timer-10'
}

// ─── Filter preset map ──────────────────────────────────────────────────────
const PRESET_FILTERS: Record<string, string> = {
  original: 'none',
  studio: 'brightness(1.1) contrast(1.05) saturate(1.1)',
  cinematic: 'sepia(0.15) contrast(1.1) brightness(0.95) saturate(1.3)',
  pro: 'contrast(1.25) brightness(1.05) saturate(1.15)',
  vivid: 'brightness(1.08) contrast(1.12) saturate(1.45) hue-rotate(5deg)',
  softPortrait: 'brightness(1.12) contrast(0.92) saturate(1.08) sepia(0.08)',
  classicFilm: 'brightness(1.02) contrast(1.15) saturate(0.85) sepia(0.2)',
  dramaticBW: 'brightness(1.05) contrast(1.35) saturate(0) grayscale(1)',
  warmSunset: 'brightness(1.06) contrast(1.08) saturate(1.3) sepia(0.18) hue-rotate(-10deg)',
}

// ─── Ratio parser ───────────────────────────────────────────────────────────
function parseRatio(ratioStr: string): number {
  const parts = ratioStr.split(':')
  if (parts.length === 2) {
    const w = parseFloat(parts[0])
    const h = parseFloat(parts[1])
    if (w > 0 && h > 0) return w / h
  }
  return 4 / 3
}

// ─── Helpers ────────────────────────────────────────────────────────────────
// DEFENSIVE: nama/nim can be undefined if a photoHistory entry got corrupted
// during reset+retake cycles. Never let .trim() crash the whole app.
function sanitizeNama(nama: string | undefined | null): string {
  return (nama ?? '').trim().replace(/\s+/g, '_').replace(/[^a-zA-Z0-9_]/g, '')
}

function sanitizeNim(nim: string | undefined | null): string {
  return (nim ?? '').toString().trim().replace(/[^a-zA-Z0-9_-]/g, '')
}

/**
 * Build a versioned filename for standard mode (Toga + Ijazah).
 * version: 1 = first capture, 2+ = retake after MC reset.
 * v1 → `NIM_Nama_1_Toga.jpg` (no version suffix for backwards compat)
 * v2 → `NIM_Nama_1_Toga_v2.jpg`, v3 → `..._v3.jpg`, …
 */
function buildFilename(nim: string | undefined, nama: string | undefined, suffix: number, type: string, version: number = 1): string {
  const base = `${sanitizeNim(nim)}_${sanitizeNama(nama)}_${suffix}_${type}`
  return version > 1 ? `${base}_v${version}.jpg` : `${base}.jpg`
}

/**
 * Build a versioned filename for photoshoot mode.
 * version: 1 = first capture, 2+ = retake after MC reset.
 * v1 → `NIM_Nama.jpg` or `NIM_Nama_Ch2.jpg` (no version suffix)
 * v2 → `NIM_Nama_v2.jpg` or `NIM_Nama_Ch2_v2.jpg`, …
 */
function buildPhotoshootFilename(nim: string | undefined, nama: string | undefined, channel: number, version: number = 1): string {
  const base = `${sanitizeNim(nim)}_${sanitizeNama(nama)}`
  const withCh = channel > 1 ? `${base}_Ch${channel}` : base
  return version > 1 ? `${withCh}_v${version}.jpg` : `${withCh}.jpg`
}

function isActiveStatus(status: StudentStatus): boolean {
  return status.startsWith('active')
}

function getActiveChannel(status: StudentStatus): number | null {
  if (!isActiveStatus(status)) return null
  const ch = status.split('_')[1]
  return ch ? parseInt(ch, 10) : null
}

function statusLabel(status: StudentStatus): string {
  if (status === 'pending') return 'Menunggu'
  if (status === 'sent') return 'Dikirim'
  if (status === 'done') return 'Selesai'
  const ch = getActiveChannel(status)
  return ch != null ? `Foto Ch.${ch}` : 'Aktif'
}

function fitAspectRatio(
  availW: number,
  availH: number,
  ratio: number,
): { width: number; height: number } {
  const hFromW = availW / ratio
  if (hFromW <= availH) {
    return { width: availW, height: hFromW }
  }
  const wFromH = availH * ratio
  return { width: wFromH, height: availH }
}

// ─── Capture state machine ──────────────────────────────────────────────────
type CapturePhase = 'standby' | 'ready-1' | 'ready-2' | 'sending'

// ─── Video device info ──────────────────────────────────────────────────────
interface VideoDeviceInfo {
  deviceId: string
  label: string
}

// ─── Socket event data shapes ───────────────────────────────────────────────
interface McCallData {
  student: Student
  channel: number
}

interface SyncDbData {
  project: {
    id: string
    name: string
    config: {
      mode: CameraMode
      ratio: string
      preset: string
      targetFolder: string
      frame: string | null
    }
    database: Student[]
    photoHistory: PhotoHistoryItem[]
  }
}

// ─── Component ──────────────────────────────────────────────────────────────
export function OperatorPanel() {
  const isMobile = useIsMobile()
  const { toast } = useToast()

  // ── Store ────────────────────────────────────────────────────────────────
  const currentProject = useSaatirilStore((s) => s.currentProject)
  const myChannel = useSaatirilStore((s) => s.myChannel)
  const opCurrentTarget = useSaatirilStore((s) => s.opCurrentTarget)
  const opCapturedPhotos = useSaatirilStore((s) => s.opCapturedPhotos)
  const setOpCurrentTarget = useSaatirilStore((s) => s.setOpCurrentTarget)
  const addOpCapturedPhoto = useSaatirilStore((s) => s.addOpCapturedPhoto)
  const resetOpState = useSaatirilStore((s) => s.resetOpState)
  const updateStudentStatus = useSaatirilStore((s) => s.updateStudentStatus)
  const updateCurrentProject = useSaatirilStore((s) => s.updateCurrentProject)
  const saveProjectsToStorageNow = useSaatirilStore((s) => s.saveProjectsToStorageNow)

  // ── Local state ──────────────────────────────────────────────────────────
  const [videoDevices, setVideoDevices] = useState<VideoDeviceInfo[]>([])
  const [selectedDeviceId, setSelectedDeviceId] = useState<string>('')
  const [cameraAvailable, setCameraAvailable] = useState(false)
  const [flashVisible, setFlashVisible] = useState(false)
  const [sending, setSending] = useState(false)
  const [cameraDims, setCameraDims] = useState({ width: 0, height: 0 })
  const [showQueueOnMobile, setShowQueueOnMobile] = useState(false)
  const [opSearchQuery, setOpSearchQuery] = useState('')
  // Buffer for MC_CALL events that arrive before the database updates via SYNC_DB
  const [mcCallBuffer, setMcCallBuffer] = useState<Student[]>([])
  const isCapturingRef = useRef(false)

  // ── Panel visibility state (toggleable sections) ────────────────────────
  const [showShutterPanel, setShowShutterPanel] = useState(false)
  const [showGridlinePanel, setShowGridlinePanel] = useState(false)
  const [showQueuePanel, setShowQueuePanel] = useState(true)

  // ── Gridline overlay state ─────────────────────────────────────────────────
  const [gridlineEnabled, setGridlineEnabled] = useState(true)
  const [gridlineType, setGridlineType] = useState<'thirds' | 'quarters' | 'crosshair' | 'diagonal'>('thirds')
  const [gridlineThickness, setGridlineThickness] = useState<1 | 2 | 3>(1)
  const [gridlineColor, setGridlineColor] = useState<'white' | 'yellow' | 'red' | 'cyan' | 'green'>('white')

  // ── Shutter mode state ───────────────────────────────────────────────────
  const [shutterMode, setShutterMode] = useState<ShutterMode>('manual')
  // Palm trigger: when ON, showing an open palm to the camera triggers the
  // selected shutter mode (manual → instant photo, timer → starts countdown).
  // This is a hands-free trigger, NOT a shutter mode itself.
  const [palmTriggerEnabled, setPalmTriggerEnabled] = useState(false)
  const [timerCountdown, setTimerCountdown] = useState<number>(0)
  const timerIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null)
  const timerActiveRef = useRef(false)

  // ── AI auto-capture ──────────────────────────────────────────────────────
  const ai = useAIDetection()

  // ── Palm detection (selfie-style shutter) ────────────────────────────────
  const palm = usePalmDetection()

  // ── Refs ─────────────────────────────────────────────────────────────────
  const videoRef = useRef<HTMLVideoElement>(null)
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const flashRef = useRef<HTMLDivElement>(null)
  const activeRowRef = useRef<HTMLDivElement>(null)
  const nextRowRef = useRef<HTMLDivElement>(null)
  const streamRef = useRef<MediaStream | null>(null)
  const selectedDeviceRef = useRef<string>('')
  const frameImgRef = useRef<HTMLImageElement | null>(null)
  const cameraZoneRef = useRef<HTMLDivElement>(null)

  // ── Derived config ───────────────────────────────────────────────────────
  const config = currentProject?.config
  const aspectRatio = config?.ratio ? parseRatio(config.ratio) : 4 / 3
  const cssFilter = config?.preset ? PRESET_FILTERS[config.preset] ?? 'none' : 'none'
  const frameData = useMemo(() => {
    if (!config?.frame) return null
    if (config.frame !== '__FRAME_SAVED__') return config.frame
    // Frame was stripped for sync — try to restore from separate localStorage key.
    // This is a safety net: updateCurrentProject should have already restored it,
    // but in case of a race condition or localStorage timing issue, we try here too.
    try {
      const projectId = currentProject?.id
      if (projectId) {
        const saved = localStorage.getItem(`saatiril_frame_${projectId}`)
        if (saved) return saved
      }
    } catch {}
    return null
  }, [config?.frame, currentProject?.id])

  // ── Resize Observer: calculate camera dimensions ─────────────────────────
  useEffect(() => {
    const zone = cameraZoneRef.current
    if (!zone) return

    const updateSize = () => {
      const rect = zone.getBoundingClientRect()
      const padding = isMobile ? 8 : 16
      const availW = rect.width - padding
      const availH = rect.height - padding
      if (availW > 0 && availH > 0) {
        setCameraDims(fitAspectRatio(availW, availH, aspectRatio))
      }
    }

    updateSize()
    const observer = new ResizeObserver(updateSize)
    observer.observe(zone)
    return () => observer.disconnect()
  }, [aspectRatio, isMobile])

  // ── Preload frame image ──────────────────────────────────────────────────
  useEffect(() => {
    if (frameData) {
      const img = new Image()
      img.crossOrigin = 'anonymous'
      img.onload = () => { frameImgRef.current = img }
      img.onerror = () => { frameImgRef.current = null }
      img.src = frameData
    } else {
      frameImgRef.current = null
    }
  }, [frameData])

  // ── Derived data ─────────────────────────────────────────────────────────
  const mode = currentProject?.config.mode ?? 'single'
  const photoshoot = isPhotoshootMode(mode)
  const dualPhotoshoot = isDualPhotoshootMode(mode)

  // AI mode is only allowed in single/dual mode
  const aiAllowed = mode === 'single' || mode === 'dual'

  // Compute effective shutter mode — if AI is selected but not allowed, fallback to manual
  const effectiveShutterMode: ShutterMode = (shutterMode === 'ai' && !aiAllowed) ? 'manual' : shutterMode

  const channelStudents = useMemo<Student[]>(() => {
    if (!currentProject) return []
    if (photoshoot) {
      // In photoshoot modes, show all students
      return currentProject.database
    }
    return currentProject.database.filter((s) => s.assignedChannel === myChannel)
  }, [currentProject, myChannel, photoshoot])

  // ── Operator queue: derived from database + MC_CALL buffer ──────────────────
  const opQueue = useMemo<Student[]>(() => {
    if (!photoshoot || !currentProject) return []
    const alreadyPhotographed = new Set(
      currentProject.photoHistory
        .filter((h) => h.channel === myChannel)
        .map((h) => h.student.id)
    )
    const doneIds = new Set(
      currentProject.database.filter((s) => s.status === 'done').map((s) => s.id)
    )
    const dbQueueIds = new Set<string>()
    const dbItems = currentProject.database.filter(
      (s) => s.status === 'sent' && !alreadyPhotographed.has(s.id)
    )
    dbItems.forEach((s) => dbQueueIds.add(s.id))
    const bufferItems = mcCallBuffer.filter(
      (s) => !dbQueueIds.has(s.id) && !doneIds.has(s.id) && !alreadyPhotographed.has(s.id)
    )
    return [...dbItems, ...bufferItems]
  }, [photoshoot, currentProject, myChannel, mcCallBuffer])

  const opSearchResults = useMemo<Student[]>(() => {
    if (!opSearchQuery.trim()) return opQueue
    const q = opSearchQuery.toLowerCase().trim()
    return opQueue.filter(
      (s) => s.nim.toLowerCase().includes(q) || s.nama.toLowerCase().includes(q)
    )
  }, [opSearchQuery, opQueue])

  const currentlyActive = useMemo<Student | null>(() => {
    if (photoshoot) {
      return opCurrentTarget
    }
    const targetStatus: StudentStatus = `active_${myChannel}`
    return channelStudents.find((s) => s.status === targetStatus) ?? null
  }, [channelStudents, myChannel, photoshoot, opCurrentTarget])

  const nextPending = useMemo<Student | null>(() => {
    return channelStudents.find((s) => s.status === 'pending') ?? null
  }, [channelStudents])

  const remainingCount = useMemo<number>(() => {
    return channelStudents.filter((s) => s.status === 'pending').length
  }, [channelStudents])

  const hasActiveTarget = opCurrentTarget !== null && !!opCurrentTarget.id && (opCurrentTarget.nama !== undefined || opCurrentTarget.nim !== undefined)

  const capturePhase = useMemo<CapturePhase>(() => {
    if (sending) return 'sending'
    if (!hasActiveTarget) return 'standby'
    if (photoshoot) {
      if (opCapturedPhotos.length === 0) return 'ready-1'
      return 'sending'
    }
    if (opCapturedPhotos.length === 0) return 'ready-1'
    if (opCapturedPhotos.length === 1) return 'ready-2'
    return 'standby'
  }, [sending, hasActiveTarget, opCapturedPhotos.length, photoshoot])

  // ── Auto-scroll refs ─────────────────────────────────────────────────────
  useEffect(() => {
    const target = activeRowRef.current ?? nextRowRef.current
    if (target) target.scrollIntoView({ behavior: 'smooth', block: 'center' })
  }, [currentlyActive, nextPending])

  // ── Camera: enumerate devices ────────────────────────────────────────────
  const enumerateVideoDevices = useCallback(async () => {
    if (typeof navigator === 'undefined' || !navigator.mediaDevices) return
    try {
      const devices = await navigator.mediaDevices.enumerateDevices()
      const videoInputs = devices
        .filter((d) => d.kind === 'videoinput')
        .map((d) => ({
          deviceId: d.deviceId,
          label: d.label || `Kamera ${d.deviceId.slice(0, 6)}`,
        }))
      setVideoDevices(videoInputs)
      if (videoInputs.length > 0 && !selectedDeviceRef.current) {
        setSelectedDeviceId(videoInputs[0].deviceId)
        selectedDeviceRef.current = videoInputs[0].deviceId
      }
    } catch (err) {
      console.error('[SAATIRIL OP] Failed to enumerate devices:', err)
    }
  }, [])

  // ── Camera: start stream ─────────────────────────────────────────────────
  const startCamera = useCallback(
    async (deviceId?: string) => {
      if (typeof navigator === 'undefined' || !navigator.mediaDevices) {
        setCameraAvailable(false)
        return
      }
      if (streamRef.current) {
        streamRef.current.getTracks().forEach((t) => t.stop())
        streamRef.current = null
      }

      let constraints: MediaStreamConstraints
      if (deviceId) {
        constraints = {
          video: { deviceId: { exact: deviceId }, width: { ideal: 1920 }, height: { ideal: 1080 } },
          audio: false,
        }
      } else if (isMobile) {
        constraints = {
          video: { facingMode: 'environment', width: { ideal: 1920 }, height: { ideal: 1080 } },
          audio: false,
        }
      } else {
        constraints = {
          video: { width: { ideal: 1920 }, height: { ideal: 1080 } },
          audio: false,
        }
      }

      try {
        const stream = await navigator.mediaDevices.getUserMedia(constraints)
        streamRef.current = stream
        setCameraAvailable(true)
        if (videoRef.current) videoRef.current.srcObject = stream
        await enumerateVideoDevices()
      } catch (err) {
        console.error('[SAATIRIL OP] Camera access failed:', err)
        if (isMobile && !deviceId) {
          try {
            const fallbackConstraints: MediaStreamConstraints = {
              video: { width: { ideal: 1920 }, height: { ideal: 1080 } },
              audio: false,
            }
            const stream = await navigator.mediaDevices.getUserMedia(fallbackConstraints)
            streamRef.current = stream
            setCameraAvailable(true)
            if (videoRef.current) videoRef.current.srcObject = stream
            await enumerateVideoDevices()
          } catch (fallbackErr) {
            console.error('[SAATIRIL OP] Camera fallback also failed:', fallbackErr)
            setCameraAvailable(false)
          }
        } else {
          setCameraAvailable(false)
        }
      }
    },
    [enumerateVideoDevices, isMobile],
  )

  useEffect(() => {
    queueMicrotask(() => void startCamera())
    return () => {
      if (streamRef.current) {
        streamRef.current.getTracks().forEach((t) => t.stop())
        streamRef.current = null
      }
    }
  }, [startCamera])

  useEffect(() => {
    if (selectedDeviceId && selectedDeviceRef.current !== selectedDeviceId) {
      selectedDeviceRef.current = selectedDeviceId
      queueMicrotask(() => void startCamera(selectedDeviceId))
    }
  }, [selectedDeviceId, startCamera])

  useEffect(() => {
    if (typeof navigator === 'undefined' || !navigator.mediaDevices) return
    const handler = () => enumerateVideoDevices()
    navigator.mediaDevices.addEventListener('devicechange', handler)
    return () => { navigator.mediaDevices.removeEventListener('devicechange', handler) }
  }, [enumerateVideoDevices])

  // ── Switch camera (mobile: toggle front/rear) ────────────────────────────
  const handleSwitchCamera = useCallback(async () => {
    if (videoDevices.length < 2) return
    const currentIdx = videoDevices.findIndex((d) => d.deviceId === selectedDeviceId)
    const nextIdx = (currentIdx + 1) % videoDevices.length
    const nextDeviceId = videoDevices[nextIdx].deviceId
    setSelectedDeviceId(nextDeviceId)
    selectedDeviceRef.current = nextDeviceId
  }, [videoDevices, selectedDeviceId])

  // ── Refs for stable handlers ─────────────────────────────────────────────
  const myChannelRef = useRef(myChannel)
  const currentProjectRef = useRef(currentProject)
  useEffect(() => { myChannelRef.current = myChannel }, [myChannel])
  useEffect(() => { currentProjectRef.current = currentProject }, [currentProject])

  // ── State recovery (non-photoshoot only) ────────────────────────────────
  useEffect(() => {
    if (!currentProject || photoshoot) return
    const activeStudent = currentProject.database.find((s) => {
      return s.assignedChannel === myChannel && isActiveStatus(s.status)
    })
    if (activeStudent) {
      setOpCurrentTarget(activeStudent)
    } else if (opCurrentTarget && !isActiveStatus(opCurrentTarget.status)) {
      setOpCurrentTarget(null)
    }
  }, [currentProject, myChannel, setOpCurrentTarget, photoshoot])

  // ── Socket: MC_CALL ─────────────────────────────────────────────────────
  useEffect(() => {
    const handleMcCall = (data: McCallData) => {
      // In photoshoot mode: accept MC_CALL from ANY channel (both operators see it)
      // In non-photoshoot mode: only accept MC_CALL for our channel
      if (!photoshoot && data.channel !== myChannelRef.current) return
      console.log('[SAATIRIL OP] MC_CALL received:', data.student.nama, 'status:', data.student.status, 'Ch.', data.channel)
      if (photoshoot) {
        // REPLACE any existing buffer entry for this student instead of
        // dedup-skipping. This ensures a RE-SEND (after reset/retake) updates
        // the buffer with fresh data instead of being silently dropped.
        setMcCallBuffer((prev) => {
          const without = prev.filter((s) => s.id !== data.student.id)
          return [...without, data.student]
        })
      } else {
        setOpCurrentTarget(data.student)
      }
    }
    onLocal('MC_CALL', handleMcCall)
    return () => { offLocal('MC_CALL', handleMcCall) }
  }, [setOpCurrentTarget, photoshoot])

  // ── Socket: STUDENT_RESET — explicit reset/retake signal from MC ────────
  // This bypasses the normal SYNC_DB merge (which blocks status regression:
  // pending priority 0 < sent/done) so the operator can fully clear a student
  // for retake: drop from mcCallBuffer, clear active target, remove the
  // photoHistory entry on this channel, and set status to 'pending' locally.
  useEffect(() => {
    const handleStudentReset = (data: { studentId: string; channel: number }) => {
      // In photoshoot mode: accept from any channel (matching APK behavior)
      // In non-photoshoot mode: only accept for our channel
      if (!photoshoot && data.channel !== myChannelRef.current) return
      console.log('[SAATIRIL OP] STUDENT_RESET received — clearing for retake:', data.studentId, 'Ch.', data.channel)

      // 1. Drop from mcCallBuffer
      setMcCallBuffer((prev) => prev.filter((s) => s.id !== data.studentId))

      // 2. Clear active target if it matches
      setOpCurrentTarget((cur) => (cur?.id === data.studentId ? null : cur))

      // 3. Remove this channel's photoHistory entry + reset status locally
      //    (bypasses mergeDatabases priority which would otherwise ignore the
      //    'pending' regression coming via SYNC_DB).
      //    IMPORTANT: read via useSaatirilStore.getState() (synchronous, latest)
      //    instead of currentProjectRef.current (stale ref updated only after
      //    render). This prevents a race where SYNC_DB arrives right after
      //    STUDENT_RESET and sees the OLD photoHistory — which would re-add
      //    the cleared entry via preservePhotoHistoryOnSync.
      const proj = useSaatirilStore.getState().currentProject
      if (proj) {
        const cleanedHistory = proj.photoHistory.filter(
          (h) => !(h.student.id === data.studentId && h.channel === myChannelRef.current),
        )
        const cleanedDb = proj.database.map((s) =>
          s.id === data.studentId ? { ...s, status: 'pending' as StudentStatus } : s,
        )
        updateCurrentProject({ ...proj, database: cleanedDb, photoHistory: cleanedHistory })
      }
    }
    onLocal('STUDENT_RESET', handleStudentReset)
    return () => { offLocal('STUDENT_RESET', handleStudentReset) }
  }, [setOpCurrentTarget, updateCurrentProject])

  // ── Socket: SYNC_DB ─────────────────────────────────────────────────────
  useEffect(() => {
    const handleSyncDb = (data: SyncDbData) => {
      // Read latest state synchronously (avoids stale currentProjectRef race
      // when SYNC_DB arrives immediately after STUDENT_RESET / other updates).
      const proj = useSaatirilStore.getState().currentProject
      if (!proj) return
      const mergedDb = mergeDatabases(proj.database, data.project.database)
      const mergedConfig = preserveFrameOnSync(data.project.config, proj.config)
      const mergedPhotoHistory = preservePhotoHistoryOnSync(
        data.project.photoHistory ?? [],
        proj.photoHistory,
      )
      // Merge captureVersions (MAX per key) so retake version numbers never
      // regress across clients. The operator who just captured has the
      // highest version; other clients adopt it via this merge.
      const mergedVersions = mergeCaptureVersions(
        proj.captureVersions,
        (data.project as any).captureVersions,
      )
      updateCurrentProject({ ...proj, database: mergedDb, photoHistory: mergedPhotoHistory, config: mergedConfig, captureVersions: mergedVersions })

      if (!isPhotoshootMode(data.project.config.mode)) {
        const ch = myChannelRef.current
        const activeStudent = data.project.database.find((s: Student) => {
          return s.assignedChannel === ch && isActiveStatus(s.status)
        })
        if (activeStudent) setOpCurrentTarget(activeStudent)
      }

      const doneIds = new Set(
        data.project.database.filter((s: Student) => s.status === 'done').map((s: Student) => s.id)
      )
      if (doneIds.size > 0) {
        setMcCallBuffer((prev) => prev.filter((s) => !doneIds.has(s.id)))
      }

      // Dual-photoshoot: when EITHER camera takes a photo, the participant is
      // considered done. If our current target is now 'done' (because the OTHER
      // operator took the photo), clear our target + captured photos so we don't
      // take a redundant photo. This realizes the "1 camera is enough" rule.
      const curTarget = useSaatirilStore.getState().opCurrentTarget
      if (curTarget && doneIds.has(curTarget.id)) {
        console.log('[SAATIRIL OP] SYNC_DB: current target is now done — clearing (other operator finished):', curTarget.nama)
        resetOpState()
      }
    }
    onLocal('SYNC_DB', handleSyncDb)
    return () => { offLocal('SYNC_DB', handleSyncDb) }
  }, [setOpCurrentTarget, resetOpState, updateCurrentProject])

  // ── Finalize capture ────────────────────────────────────────────────────
  // OPTIMIZED: Removed 400ms of unnecessary delays, added STUDENT_DONE lightweight
  // event for immediate MC unblocking, and SYNC_DB now strips photos (tiny payload).
  const finalizeCapture = useCallback(
    (canvas: HTMLCanvasElement) => {
      setFlashVisible(true)
      setTimeout(() => setFlashVisible(false), 200)

      const dataUrl = canvas.toDataURL('image/jpeg', 0.95)
      addOpCapturedPhoto(dataUrl)

      const currentPhotos = useSaatirilStore.getState().opCapturedPhotos
      const currentTarget = useSaatirilStore.getState().opCurrentTarget
      const photoCount = currentPhotos.length
      const currentMode = useSaatirilStore.getState().currentProject?.config.mode ?? 'single'
      const isPhotoshoot = isPhotoshootMode(currentMode)

      console.log('[SAATIRIL OP] finalizeCapture: photoCount =', photoCount, 'mode =', currentMode)

      // Helper: update local photoHistory + captureVersions, emit lightweight SYNC_DB.
      // `newVersions` is the updated captureVersions map (with the just-captured
      // student+channel incremented) so version numbers persist across resets.
      const finishCapture = (
        student: Student,
        historyItem: PhotoHistoryItem,
        newVersions?: Record<string, number>,
      ) => {
        const store = useSaatirilStore.getState()
        if (store.currentProject) {
          const existingIdx = store.currentProject.photoHistory.findIndex(
            (h) => h.student.id === student.id && h.channel === myChannel
          )
          let newHistory: PhotoHistoryItem[]
          if (existingIdx !== -1) {
            newHistory = [...store.currentProject.photoHistory]
            newHistory[existingIdx] = historyItem
          } else {
            newHistory = [...store.currentProject.photoHistory, historyItem]
          }
          const updatedProject = {
            ...store.currentProject,
            photoHistory: newHistory,
            captureVersions: newVersions ?? store.currentProject.captureVersions,
          }
          store.updateCurrentProject(updatedProject)
          // SYNC_DB is now lightweight — photos stripped, only metadata sent
          // (captureVersions IS included so admin/MC see the same version numbers)
          emitLocal('SYNC_DB', { project: stripFrameForSync(updatedProject) })
        }
        setSending(false)
        isCapturingRef.current = false
        resetOpState()
      }

      // ── Compute versioned filename helper ──────────────────────────────────
      // Reads the per-student+channel capture counter from the project, increments
      // it, and returns { version, newVersions, filename } so the caller can
      // (a) use the filename for the disk save, and (b) pass newVersions to
      // finishCapture so the counter persists.
      const proj0 = useSaatirilStore.getState().currentProject
      const computeVersionedFilename = (
        student: Student,
        isPhotoshootModeFlag: boolean,
      ): { version: number; newVersions: Record<string, number>; filename: string } => {
        const versionKey = `${student.id}_${myChannel}`
        const currentVersions = proj0?.captureVersions ?? {}
        const prevVersion = currentVersions[versionKey] ?? 0
        const version = prevVersion + 1
        const newVersions = { ...currentVersions, [versionKey]: version }
        const filename = isPhotoshootModeFlag
          ? buildPhotoshootFilename(student.nim, student.nama, myChannel, version)
          : buildFilename(student.nim, student.nama, 1, 'Toga', version) // placeholder; standard mode overrides below
        return { version, newVersions, filename }
      }

      // Photoshoot mode: save after 1 photo
      if (isPhotoshoot && photoCount >= 1) {
        if (!currentTarget) {
          console.warn('[SAATIRIL OP] finalizeCapture: opCurrentTarget is null — aborting save')
          // CRITICAL: tell the operator WHY the photo wasn't saved, so they
          // don't think it was captured successfully. This happens when MC
          // resets a student but hasn't re-sent them yet.
          toast({
            title: 'Foto Tidak Tersimpan',
            description: 'Tidak ada peserta aktif. Minta MC mengirim ulang peserta, lalu foto kembali.',
            variant: 'destructive',
          })
          setSending(false)
          isCapturingRef.current = false
          resetOpState()
          return
        }
        setSending(true)
        const student = currentTarget
        const allPhotos = [...currentPhotos]
        const historyItem: PhotoHistoryItem = {
          student: { ...student },
          photos: allPhotos,
          channel: myChannel,
        }
        saveProjectsToStorageNow()
        setMcCallBuffer((prev) => prev.filter((s) => s.id !== student.id))

        // ── VERSIONED FILENAME ────────────────────────────────────────────────
        // Each capture (incl. retakes after MC reset) gets a unique version so
        // retakes create NEW files on disk instead of overwriting.
        // v1 → `NIM_Nama.jpg`, v2 → `NIM_Nama_v2.jpg`, …
        const { version, newVersions, filename: vFilename } = computeVersionedFilename(student, true)
        console.log(`[SAATIRIL OP] Photoshoot capture v${version} for ${student.nama}: ${vFilename}`)

        console.log('[SAATIRIL OP] Emitting PHOTOS_SAVED for student:', student.nama, 'channel:', myChannel, 'version:', version)

        // PHOTOS_SAVED for Admin/gallery (contains photos for display + version)
        emitLocal('PHOTOS_SAVED', {
          student: { ...student, status: student.status },
          photos: allPhotos,
          channel: myChannel,
          version,
          filename: vFilename,
        })
        emitLocal('OP_PROGRESS', { channel: myChannel, status: 'Selesai — Menunggu target...' })

        // ── SAVE TO DISK ──────────────────────────────────────────────────────
        // Electron only: photos write to config.targetFolder (the folder chosen
        // at PROJECT CREATION) via IPC. In browser mode (Chrome) photos stay in
        // memory/gallery only — run via Electron desktop for permanent disk
        // saves. The operator's job is ONLY to take photos; the folder is NOT
        // picked here.
        const projConfig = useSaatirilStore.getState().currentProject?.config
        if (projConfig) {
          const api = window.saatirilAPI
          if (api?.savePhoto) {
            const targetFolder = projConfig.targetFolder
            console.log(`[SAATIRIL OP] Saving photo to disk: ${targetFolder}/${vFilename}`)
            api.savePhoto({ base64Data: allPhotos[0], filename: vFilename, targetFolder }).then((path: string | null) => {
              if (path) {
                console.log(`[SAATIRIL OP] ✓ Photo saved to disk: → ${path}`)
              } else {
                console.warn(`[SAATIRIL OP] ✗ Photo FAILED to save to disk: ${targetFolder}/${vFilename}`)
                toast({
                  title: 'Gagal Simpan ke Disk',
                  description: `Foto ${vFilename} tidak tersimpan. Cek ruang disk & folder target.`,
                  variant: 'destructive',
                })
              }
            }).catch((err: Error) => {
              console.error('[SAATIRIL OP] Error saving photo to disk:', err)
              toast({
                title: 'Error Simpan Foto',
                description: err.message || 'Terjadi kesalahan saat menyimpan ke disk.',
                variant: 'destructive',
              })
            })
          } else {
            // Browser mode (operator opened in Chrome for camera flags, etc.).
            // The operator does NOT save to its own disk here — the Admin panel
            // (running in Electron) receives the PHOTOS_SAVED socket event and
            // saves to config.targetFolder via its own IPC. So the photo IS
            // saved to disk; we just skip the operator's local save silently.
            console.log('[SAATIRIL OP] Browser mode — operator disk save skipped. Admin (Electron) will save via PHOTOS_SAVED event.')
          }
        }

        // IMMEDIATE: update local state + emit lightweight SYNC_DB (no delays!)
        finishCapture(student, historyItem, newVersions)
        return
      }

      // Standard mode (single/dual): 2 photos
      if (photoCount === 1) {
        isCapturingRef.current = false
        emitLocal('OP_PROGRESS', { channel: myChannel, status: 'Pose 1 OK — Siap Foto 2' })
      } else if (photoCount >= 2) {
        if (!currentTarget) {
          console.warn('[SAATIRIL OP] finalizeCapture: opCurrentTarget is null — aborting save')
          toast({
            title: 'Foto Tidak Tersimpan',
            description: 'Tidak ada peserta aktif. Minta MC mengirim ulang peserta, lalu foto kembali.',
            variant: 'destructive',
          })
          setSending(false)
          isCapturingRef.current = false
          resetOpState()
          return
        }
        setSending(true)
        const student = currentTarget
        const allPhotos = [...currentPhotos]
        const historyItem: PhotoHistoryItem = {
          student: { ...student },
          photos: allPhotos,
          channel: myChannel,
        }
        updateStudentStatus(student.id, 'done')
        saveProjectsToStorageNow()

        // ── VERSIONED FILENAMES ───────────────────────────────────────────────
        // Standard mode: 2 photos (Toga + Ijazah), both share the same version.
        // v1 → `NIM_Nama_1_Toga.jpg` + `NIM_Nama_2_Ijazah.jpg`
        // v2 → `NIM_Nama_1_Toga_v2.jpg` + `NIM_Nama_2_Ijazah_v2.jpg`
        const { version, newVersions } = computeVersionedFilename(student, false)
        const togaFilename = buildFilename(student.nim, student.nama, 1, 'Toga', version)
        const ijazahFilename = buildFilename(student.nim, student.nama, 2, 'Ijazah', version)
        console.log(`[SAATIRIL OP] Standard capture v${version} for ${student.nama}: ${togaFilename}, ${ijazahFilename}`)

        console.log('[SAATIRIL OP] Emitting STUDENT_DONE + PHOTOS_SAVED for student:', student.nama, 'channel:', myChannel, 'version:', version)

        // PRIORITY 1: STUDENT_DONE — lightweight event for IMMEDIATE MC unblocking
        // This fires BEFORE the heavy PHOTOS_SAVED so MC can call next student instantly
        emitLocal('STUDENT_DONE', {
          studentId: student.id,
          channel: myChannel,
        })

        // PRIORITY 2: PHOTOS_SAVED — contains photos for Admin gallery + version
        emitLocal('PHOTOS_SAVED', {
          student: { ...student, status: 'done' },
          photos: allPhotos,
          channel: myChannel,
          version,
          filename: togaFilename,
        })
        emitLocal('OP_PROGRESS', { channel: myChannel, status: 'Selesai — Menunggu target...' })

        // ── SAVE TO DISK ──────────────────────────────────────────────────────
        // Electron only: photos write to config.targetFolder (folder chosen at
        // PROJECT CREATION) via IPC. In browser mode photos stay in
        // memory/gallery — run via Electron desktop for permanent disk saves.
        const projConfig = useSaatirilStore.getState().currentProject?.config
        if (projConfig) {
          const api = window.saatirilAPI
          if (api?.savePhoto) {
            const targetFolder = projConfig.targetFolder
            console.log(`[SAATIRIL OP] Saving 2 photos to disk: ${targetFolder}/`)
            Promise.all([
              api.savePhoto({ base64Data: allPhotos[0], filename: togaFilename, targetFolder }),
              api.savePhoto({ base64Data: allPhotos[1], filename: ijazahFilename, targetFolder }),
            ]).then(([path1, path2]) => {
              if (path1 && path2) {
                console.log(`[SAATIRIL OP] ✓ Photos saved to disk:\n  → ${path1}\n  → ${path2}`)
              } else {
                console.warn(`[SAATIRIL OP] ✗ Some photos FAILED to save: toga=${!!path1} ijazah=${!!path2}`)
                toast({
                  title: 'Gagal Simpan ke Disk',
                  description: 'Sebagian foto tidak tersimpan. Cek ruang disk & folder target.',
                  variant: 'destructive',
                })
              }
            }).catch((err) => {
              console.error('[SAATIRIL OP] Error saving photos to disk:', err)
              toast({
                title: 'Error Simpan Foto',
                description: err.message || 'Terjadi kesalahan saat menyimpan ke disk.',
                variant: 'destructive',
              })
            })
          } else {
            // Browser mode (operator opened in Chrome for camera flags, etc.).
            // The operator does NOT save to its own disk here — the Admin panel
            // (running in Electron) receives the PHOTOS_SAVED socket event and
            // saves to config.targetFolder via its own IPC. So the photo IS
            // saved to disk; we just skip the operator's local save silently.
            console.log('[SAATIRIL OP] Browser mode — operator disk save skipped. Admin (Electron) will save via PHOTOS_SAVED event.')
          }
        }

        // IMMEDIATE: update local state + emit lightweight SYNC_DB (no delays!)
        finishCapture(student, historyItem, newVersions)
      }
    },
    [myChannel, addOpCapturedPhoto, updateStudentStatus, saveProjectsToStorageNow, resetOpState, toast],
  )

  // ── Photo capture logic ──────────────────────────────────────────────────
  const handleCapture = useCallback(() => {
    if (!opCurrentTarget) return
    if (photoshoot) {
      if (capturePhase !== 'ready-1') return
    } else {
      if (capturePhase !== 'ready-1' && capturePhase !== 'ready-2') return
    }
    if (isCapturingRef.current) return
    isCapturingRef.current = true

    const canvas = canvasRef.current
    const video = videoRef.current
    if (!canvas) return

    const targetWidth = 1920
    const targetHeight = Math.round(targetWidth / aspectRatio)
    canvas.width = targetWidth
    canvas.height = targetHeight

    const ctx = canvas.getContext('2d')
    if (!ctx) return

    ctx.fillStyle = '#000000'
    ctx.fillRect(0, 0, targetWidth, targetHeight)

    if (video && video.readyState >= 2) {
      const videoWidth = video.videoWidth
      const videoHeight = video.videoHeight
      const videoRatio = videoWidth / videoHeight
      let sx = 0, sy = 0, sw = videoWidth, sh = videoHeight
      if (videoRatio > aspectRatio) {
        sw = videoHeight * aspectRatio
        sx = (videoWidth - sw) / 2
      } else {
        sh = videoWidth / aspectRatio
        sy = (videoHeight - sh) / 2
      }
      if (cssFilter !== 'none') ctx.filter = cssFilter
      ctx.drawImage(video, sx, sy, sw, sh, 0, 0, targetWidth, targetHeight)
      ctx.filter = 'none'
    } else {
      ctx.fillStyle = '#1a0b2e'
      ctx.fillRect(0, 0, targetWidth, targetHeight)
      ctx.fillStyle = '#533485'
      ctx.font = 'bold 48px sans-serif'
      ctx.textAlign = 'center'
      ctx.textBaseline = 'middle'
      ctx.fillText('NO CAMERA SIGNAL', targetWidth / 2, targetHeight / 2)
    }

    if (frameImgRef.current) {
      ctx.drawImage(frameImgRef.current, 0, 0, targetWidth, targetHeight)
      finalizeCapture(canvas)
    } else if (frameData) {
      const frameImg = new Image()
      frameImg.crossOrigin = 'anonymous'
      frameImg.onload = () => { ctx.drawImage(frameImg, 0, 0, targetWidth, targetHeight); finalizeCapture(canvas) }
      frameImg.onerror = () => { finalizeCapture(canvas) }
      frameImg.src = frameData
    } else {
      finalizeCapture(canvas)
    }
  }, [opCurrentTarget, capturePhase, aspectRatio, cssFilter, frameData, finalizeCapture, photoshoot])

  // ── Shutter: Timer logic ─────────────────────────────────────────────────
  const handleCaptureRef = useRef(handleCapture)
  useEffect(() => { handleCaptureRef.current = handleCapture }, [handleCapture])
  const handleCaptureClickRef = useRef<() => void>(() => {})

  const cancelTimer = useCallback(() => {
    if (timerIntervalRef.current) {
      clearInterval(timerIntervalRef.current)
      timerIntervalRef.current = null
    }
    timerActiveRef.current = false
    setTimerCountdown(0)
  }, [])

  // Cancel timer interval when capture phase changes away from ready (external cleanup only)
  useEffect(() => {
    if (capturePhase !== 'ready-1' && capturePhase !== 'ready-2') {
      if (timerIntervalRef.current) {
        clearInterval(timerIntervalRef.current)
        timerIntervalRef.current = null
      }
      timerActiveRef.current = false
    }
  }, [capturePhase])

  // Derived: only show countdown when in ready phase
  const effectiveTimerCountdown = (capturePhase === 'ready-1' || capturePhase === 'ready-2') ? timerCountdown : 0

  const startTimer = useCallback(() => {
    if (!isTimerMode(effectiveShutterMode)) return
    if (capturePhase !== 'ready-1' && capturePhase !== 'ready-2') return
    if (timerActiveRef.current) {
      // Cancel if already running
      cancelTimer()
      return
    }

    const duration = getTimerDuration(effectiveShutterMode)
    let remaining = duration
    timerActiveRef.current = true
    setTimerCountdown(remaining)

    timerIntervalRef.current = setInterval(() => {
      remaining -= 1
      if (remaining <= 0) {
        // Time's up — capture!
        if (timerIntervalRef.current) {
          clearInterval(timerIntervalRef.current)
          timerIntervalRef.current = null
        }
        timerActiveRef.current = false
        setTimerCountdown(0)
        handleCaptureRef.current()
      } else {
        setTimerCountdown(remaining)
      }
    }, 1000)
  }, [effectiveShutterMode, capturePhase, cancelTimer])

  // ── Shutter: AI detection ────────────────────────────────────────────────
  const capturePhaseRef = useRef(capturePhase)
  useEffect(() => { capturePhaseRef.current = capturePhase }, [capturePhase])

  // AI: Initialize when camera is ready and AI shutter mode is active
  useEffect(() => {
    if (effectiveShutterMode === 'ai' && cameraAvailable && hasActiveTarget && !ai.scriptsLoaded && ai.status === 'unloaded') {
      ai.initialize().then((ok) => {
        if (ok) console.log('[SAATIRIL OP] AI initialized for shutter mode')
      })
    }
  }, [effectiveShutterMode, cameraAvailable, hasActiveTarget])

  // AI: Start/stop detection based on shutter mode
  useEffect(() => {
    if (effectiveShutterMode === 'ai' && ai.modelLoaded && cameraAvailable && videoRef.current && hasActiveTarget) {
      ai.startDetection(videoRef.current, (event: AIMomentEvent) => {
        console.log('[SAATIRIL OP] AI moment:', event.type, 'phase:', capturePhaseRef.current)
        const phase = capturePhaseRef.current
        if (event.type === 'toga' && phase === 'ready-1') {
          handleCaptureRef.current()
        } else if (event.type === 'ijazah' && phase === 'ready-2') {
          handleCaptureRef.current()
        }
      })
    } else if (effectiveShutterMode !== 'ai' && ai.isRunning) {
      ai.stopDetection()
    }
  }, [effectiveShutterMode, ai.modelLoaded, cameraAvailable, hasActiveTarget, capturePhase])

  // ── Palm trigger (hands-free shutter) ────────────────────────────────────
  // Palm detection is a TRIGGER, not a shutter mode. When the toggle is ON,
  // showing an open palm fires handleCaptureButtonClick() — which respects
  // the selected mode (manual → instant photo, timer → starts countdown).
  // Disabled in AI mode (AI already auto-triggers).
  const palmTriggerActive = palmTriggerEnabled && effectiveShutterMode !== 'ai'

  useEffect(() => {
    if (palmTriggerActive && cameraAvailable && hasActiveTarget && palm.status === 'unloaded') {
      palm.initialize().then((ok) => {
        if (ok) console.log('[SAATIRIL OP] Palm trigger initialized')
      })
    }
  }, [palmTriggerActive, cameraAvailable, hasActiveTarget])

  useEffect(() => {
    if (palmTriggerActive && (palm.status === 'model_ready' || palm.status === 'stopped') && cameraAvailable && videoRef.current && hasActiveTarget) {
      palm.startDetection(videoRef.current, {
        onPalmConfirmed: () => {
          console.log('[SAATIRIL OP] Hand confirmed — waiting for hand to leave')
        },
        onPalmLeft: () => {
          // PHOTOBOOTH TRIGGER: hand left frame → start timer/capture
          console.log('[SAATIRIL OP] Hand left frame → triggering shutter')
          handleCaptureClickRef.current()
        },
      })
    } else if (!palmTriggerActive && palm.isRunning) {
      palm.stopDetection()
    }
  }, [palmTriggerActive, palm.status, cameraAvailable, hasActiveTarget])

  // ── Cleanup on unmount ───────────────────────────────────────────────────
  useEffect(() => {
    return () => {
      cancelTimer()
      if (ai.isRunning) ai.stopDetection()
      if (palm.isRunning) palm.stopDetection()
    }
  }, [])

  // ── Shutter mode: determine if capture button should trigger timer or direct capture
  const handleCaptureButtonClick = useCallback(() => {
    if (isTimerMode(effectiveShutterMode)) {
      if (timerActiveRef.current) {
        // Cancel running timer
        cancelTimer()
      } else {
        // Start timer
        startTimer()
      }
    } else {
      // Manual mode — direct capture
      handleCapture()
    }
  }, [effectiveShutterMode, startTimer, cancelTimer, handleCapture])

  // Keep handleCaptureClickRef in sync with the latest handler
  useEffect(() => { handleCaptureClickRef.current = handleCaptureButtonClick }, [handleCaptureButtonClick])

  // ── Keyboard shortcuts (physical shutter for camera operator) ──────────────
  // Space / Enter → trigger capture (same as clicking the FOTO button)
  // Esc           → cancel a running countdown timer
  // F             → toggle browser fullscreen (hands-free operation)
  const cancelTimerRef = useRef(cancelTimer)
  useEffect(() => { cancelTimerRef.current = cancelTimer }, [cancelTimer])
  const [isFullscreen, setIsFullscreen] = useState(false)

  const toggleFullscreen = useCallback(() => {
    const docEl = document.documentElement
    if (!document.fullscreenElement) {
      docEl.requestFullscreen?.().then(() => setIsFullscreen(true)).catch(() => {})
    } else {
      document.exitFullscreen?.().then(() => setIsFullscreen(false)).catch(() => {})
    }
  }, [])

  const exitFullscreen = useCallback(() => {
    document.exitFullscreen?.().then(() => setIsFullscreen(false)).catch(() => {})
  }, [])

  useEffect(() => {
    // Keyboard shortcut handler

    const isTypingTarget = (el: EventTarget | null): boolean => {
      if (!(el instanceof HTMLElement)) return false
      const tag = el.tagName
      return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable
    }

    const onKeyDown = (e: KeyboardEvent) => {
      if (isTypingTarget(e.target)) return

      if (e.key === 'Escape') {
        if (timerActiveRef.current) {
          e.preventDefault()
          cancelTimerRef.current()
        }
        return
      }

      if (e.key === ' ' || e.key === 'Enter') {
        e.preventDefault()
        handleCaptureClickRef.current()
        return
      }

      if (e.key === 'f' || e.key === 'F') {
        e.preventDefault()
        toggleFullscreen()
        return
      }
    }

    const onFullscreenChange = () => {
      setIsFullscreen(!!document.fullscreenElement)
    }

    window.addEventListener('keydown', onKeyDown)
    document.addEventListener('fullscreenchange', onFullscreenChange)
    return () => {
      window.removeEventListener('keydown', onKeyDown)
      document.removeEventListener('fullscreenchange', onFullscreenChange)
    }
  }, [toggleFullscreen])

  // ── Progress badge text ──────────────────────────────────────────────────
  const progressText = useMemo(() => {
    if (!hasActiveTarget) return 'Menunggu Arahan MC...'
    if (effectiveTimerCountdown > 0) return `Timer: ${effectiveTimerCountdown}s`
    if (effectiveShutterMode === 'ai' && ai.isRunning) {
      if (ai.momentState === 'toga_possible' || ai.momentState === 'toga_sustained') return 'AI: Toga terdeteksi...'
      if (ai.momentState === 'ijazah_possible' || ai.momentState === 'ijazah_sustained') return 'AI: Ijazah terdeteksi...'
    }
    if (palmTriggerEnabled && palm.isRunning) {
      if (palm.palmState === 'triggered') return 'Timer dimulai — bereskan pose...'
      if (palm.palmState === 'confirmed') return 'Tangan terdeteksi — lepaskan untuk mulai'
      if (palm.palmState === 'hand_detected') return 'Tangan terdeteksi...'
      if (palm.palmState === 'searching') return 'Menunggu tangan...'
    }
    if (photoshoot) {
      if (capturePhase === 'ready-1') return 'Siap Foto'
      if (capturePhase === 'sending') return 'Mengirim...'
    } else {
      if (capturePhase === 'ready-1') return 'Siap Foto 1'
      if (capturePhase === 'ready-2') return 'Pose 1 OK - Siap Foto 2'
      if (capturePhase === 'sending') return 'Mengirim...'
    }
    return 'Menunggu Arahan MC...'
  }, [hasActiveTarget, capturePhase, photoshoot, effectiveTimerCountdown, effectiveShutterMode, ai.isRunning, ai.momentState, palmTriggerEnabled, palm.isRunning, palm.palmState])

  // ── Render helpers ───────────────────────────────────────────────────────
  const getRowStyle = (student: Student): React.CSSProperties => {
    const isActive = student.status === `active_${myChannel}`
    const isSent = student.status === 'sent'
    const isNext = !photoshoot && student.id === nextPending?.id && student.status === 'pending'
    const isDone = student.status === 'done'
    if (isActive) return { backgroundColor: `${THEME.gold}22`, borderLeft: `4px solid ${THEME.gold}`, boxShadow: `0 0 12px ${THEME.gold}44` }
    if (isSent) return { backgroundColor: `${THEME.gold}0a`, borderLeft: `4px solid ${THEME.gold}88` }
    if (isNext) return { backgroundColor: THEME.panel, borderLeft: `4px solid ${THEME.gold}` }
    if (isDone) return { backgroundColor: '#22c55e0d', opacity: 0.55, borderLeft: `4px solid #22c55e66` }
    return { backgroundColor: THEME.panel, borderLeft: `4px solid ${THEME.border}` }
  }

  const renderStatusBadge = (status: StudentStatus) => {
    if (status === 'done') return <Badge className="text-[10px] px-1.5 py-0" style={{ backgroundColor: '#22c55e33', color: '#4ade80', border: '1px solid #22c55e55' }}><CheckCircle2 className="size-3 mr-0.5" />Selesai</Badge>
    if (status === 'sent') return <Badge className="text-[10px] px-1.5 py-0" style={{ backgroundColor: '#d4af3733', color: THEME.gold, border: '1px solid #d4af3766' }}><Camera className="size-3 mr-0.5" />Dikirim</Badge>
    if (isActiveStatus(status)) return <Badge className="text-[10px] px-1.5 py-0 animate-pulse" style={{ backgroundColor: `${THEME.gold}33`, color: THEME.gold, border: `1px solid ${THEME.gold}66` }}><Loader2 className="size-3 mr-0.5 animate-spin" />{statusLabel(status)}</Badge>
    return <Badge className="text-[10px] px-1.5 py-0" style={{ backgroundColor: `${THEME.border}44`, color: THEME.muted, border: `1px solid ${THEME.border}` }}><Clock className="size-3 mr-0.5" />Menunggu</Badge>
  }

  // ── Shutter mode selector ────────────────────────────────────────────────
  const renderShutterModeSelector = (compact = false) => {
    const availableModes = SHUTTER_MODES.filter((m) => {
      if (m.modesAllowed && !m.modesAllowed.includes(mode as CameraMode)) return false
      return true
    })

    return (
      <div className={`flex flex-col gap-1.5 ${compact ? '' : ''}`}>
        <p className={`font-semibold uppercase tracking-widest ${compact ? 'text-[8px]' : 'text-[9px]'}`} style={{ color: THEME.muted }}>
          Mode Shutter
        </p>
        {/* Row 1: Shutter mode buttons */}
        <div className={`flex flex-wrap gap-1 ${compact ? '' : ''}`}>
          {availableModes.map((m) => {
            const isActive = effectiveShutterMode === m.id
            const isLoading = (m.id === 'ai' && (ai.status === 'loading_scripts' || ai.status === 'loading_model'))

            return (
              <button
                key={m.id}
                onClick={() => {
                  if (!isLoading) {
                    setShutterMode(m.id)
                    cancelTimer()
                  }
                }}
                className={`flex items-center gap-1 rounded-md font-semibold transition-all duration-200 cursor-pointer ${
                  compact ? 'px-1.5 py-1 text-[9px]' : 'px-2 py-1.5 text-[10px]'
                } ${isActive ? 'scale-105' : 'hover:bg-white/5'}`}
                style={{
                  backgroundColor: isActive ? `${THEME.gold}33` : THEME.panel,
                  color: isActive ? THEME.gold : THEME.muted,
                  border: `1px solid ${isActive ? THEME.gold : THEME.border}`,
                  boxShadow: isActive ? `0 0 8px ${THEME.gold}22` : 'none',
                  opacity: isLoading ? 0.6 : 1,
                }}
                title={m.label}
              >
                {isLoading ? <Loader2 className="size-3 animate-spin" /> : m.icon}
                <span>{m.shortLabel}</span>
              </button>
            )
          })}
        </div>
        {/* Row 2: Palm trigger toggle (separate row for clarity — prevents overlap) */}
        {effectiveShutterMode !== 'ai' && (
          <button
            onClick={() => {
              setPalmTriggerEnabled((v) => !v)
              if (palmTriggerEnabled) palm.stopDetection()
            }}
            className={`flex items-center gap-1.5 rounded-md font-semibold transition-all duration-200 cursor-pointer w-full ${
              compact ? 'px-1.5 py-1 text-[9px]' : 'px-2 py-1.5 text-[10px]'
            } ${palmTriggerEnabled ? 'scale-[1.02]' : 'hover:bg-white/5'}`}
            style={{
              backgroundColor: palmTriggerEnabled ? '#22c55e22' : THEME.panel,
              color: palmTriggerEnabled ? '#4ade80' : THEME.muted,
              border: `1px solid ${palmTriggerEnabled ? '#22c55e' : THEME.border}`,
              boxShadow: palmTriggerEnabled ? '0 0 8px #22c55e22' : 'none',
            }}
            title="Trigger Tangan — tunjukkan tangan, lalu lepaskan untuk memulai timer/foto"
          >
            {palm.status === 'loading_scripts' || palm.status === 'loading_model' ? (
              <Loader2 className="size-3 animate-spin" />
            ) : (
              <Hand className="size-3" />
            )}
            <span className="font-bold">Trigger Tangan</span>
            {palmTriggerEnabled && (
              <>
                <span style={{ color: THEME.muted }}>•</span>
                <span className={`text-[8px] ${compact ? '' : 'text-[9px]'}`} style={{
                  color: palm.palmState === 'triggered' ? '#4ade80' : palm.palmState === 'confirmed' ? '#4ade80' : palm.palmState === 'hand_detected' ? THEME.gold : THEME.muted
                }}>
                  {palm.palmState === 'triggered'
                    ? 'Timer dimulai ✓'
                    : palm.palmState === 'confirmed'
                      ? 'Lepas tangan = mulai'
                      : palm.palmState === 'hand_detected'
                        ? 'Tangan terdeteksi...'
                        : palm.isRunning
                          ? 'Tunjukkan tangan'
                          : ''}
                </span>
              </>
            )}
          </button>
        )}
      </div>
    )
  }

  // ── Gridline settings ──────────────────────────────────────────────────────
  const renderGridlineSettings = (compact = false) => {
    const colorSwatches: { id: typeof gridlineColor; color: string; label: string }[] = [
      { id: 'white', color: '#ffffff', label: 'Putih' },
      { id: 'yellow', color: '#facc15', label: 'Kuning' },
      { id: 'red', color: '#ef4444', label: 'Merah' },
      { id: 'cyan', color: '#06b6d4', label: 'Cyan' },
      { id: 'green', color: '#22c55e', label: 'Hijau' },
    ]

    const typeOptions: { id: typeof gridlineType; label: string; icon: React.ReactNode }[] = [
      { id: 'thirds', label: '1/3', icon: <List className="size-3" /> },
      { id: 'quarters', label: '1/4', icon: <List className="size-3" /> },
      { id: 'crosshair', label: '+', icon: <Aperture className="size-3" /> },
      { id: 'diagonal', label: 'X', icon: <Frame className="size-3" /> },
    ]

    return (
      <div className={`flex flex-col gap-1.5 ${compact ? '' : ''}`}>
        <div className="flex items-center justify-between">
          <p className={`font-semibold uppercase tracking-widest ${compact ? 'text-[8px]' : 'text-[9px]'}`} style={{ color: THEME.muted }}>
            Gridline
          </p>
          <button
            onClick={() => setGridlineEnabled((v) => !v)}
            className="flex items-center gap-1 cursor-pointer"
            title={gridlineEnabled ? 'Sembunyikan gridline' : 'Tampilkan gridline'}
          >
            <div
              className={`rounded-full transition-all duration-200 ${compact ? 'w-6 h-3.5' : 'w-7 h-4'}`}
              style={{
                backgroundColor: gridlineEnabled ? THEME.gold : THEME.border,
                position: 'relative',
              }}
            >
              <div
                className={`rounded-full bg-white transition-all duration-200 ${compact ? 'size-2.5' : 'size-3'}`}
                style={{
                  position: 'absolute',
                  top: '50%',
                  transform: `translateY(-50%) translateX(${gridlineEnabled ? (compact ? '10px' : '14px') : '1px'})`,
                  transition: 'transform 0.2s ease',
                }}
              />
            </div>
          </button>
        </div>

        {gridlineEnabled && (
          <>
            {/* Grid type selector */}
            <div className="flex gap-1">
              {typeOptions.map((t) => (
                <button
                  key={t.id}
                  onClick={() => setGridlineType(t.id)}
                  className={`flex items-center justify-center gap-0.5 rounded-md font-semibold transition-all duration-200 cursor-pointer ${
                    compact ? 'px-1.5 py-1 text-[9px]' : 'px-2 py-1.5 text-[10px]'
                  } ${gridlineType === t.id ? 'scale-105' : 'hover:bg-white/5'}`}
                  style={{
                    backgroundColor: gridlineType === t.id ? `${THEME.gold}33` : THEME.panel,
                    color: gridlineType === t.id ? THEME.gold : THEME.muted,
                    border: `1px solid ${gridlineType === t.id ? THEME.gold : THEME.border}`,
                    minWidth: compact ? '28px' : '34px',
                  }}
                  title={t.label}
                >
                  {t.icon}
                  <span>{t.label}</span>
                </button>
              ))}
            </div>

            {/* Thickness selector */}
            <div className="flex items-center gap-1.5">
              <span className={`font-medium ${compact ? 'text-[8px]' : 'text-[9px]'}`} style={{ color: THEME.muted }}>
                Ketebalan
              </span>
              <div className="flex gap-1">
                {[1, 2, 3].map((t) => (
                  <button
                    key={t}
                    onClick={() => setGridlineThickness(t as 1 | 2 | 3)}
                    className={`flex items-center justify-center rounded-md transition-all duration-200 cursor-pointer ${
                      compact ? 'w-6 h-5' : 'w-7 h-6'
                    }`}
                    style={{
                      backgroundColor: gridlineThickness === t ? `${THEME.gold}33` : THEME.panel,
                      border: `1px solid ${gridlineThickness === t ? THEME.gold : THEME.border}`,
                    }}
                    title={`Ketebalan ${t}`}
                  >
                    <div
                      className="rounded-full"
                      style={{
                        backgroundColor: gridlineThickness === t ? THEME.gold : THEME.muted,
                        width: `${t * 2 + 1}px`,
                        height: `${t * 2 + 1}px`,
                      }}
                    />
                  </button>
                ))}
              </div>
            </div>

            {/* Color selector */}
            <div className="flex items-center gap-1.5">
              <span className={`font-medium ${compact ? 'text-[8px]' : 'text-[9px]'}`} style={{ color: THEME.muted }}>
                Warna
              </span>
              <div className="flex gap-1">
                {colorSwatches.map((c) => (
                  <button
                    key={c.id}
                    onClick={() => setGridlineColor(c.id)}
                    className={`rounded-full transition-all duration-200 cursor-pointer ${
                      compact ? 'size-4' : 'size-5'
                    }`}
                    style={{
                      backgroundColor: c.color,
                      border: `2px solid ${gridlineColor === c.id ? THEME.gold : 'transparent'}`,
                      boxShadow: gridlineColor === c.id ? `0 0 6px ${c.color}66` : 'none',
                      opacity: gridlineColor === c.id ? 1 : 0.5,
                    }}
                    title={c.label}
                  />
                ))}
              </div>
            </div>
          </>
        )}
      </div>
    )
  }

  // ── Operator queue with search (photoshoot mode) ────────────────────────
  const renderOpSearch = (compact = false) => {
    if (!photoshoot) return null
    return (
      <Card
        className="shrink-0 border rounded-lg overflow-hidden"
        style={{ backgroundColor: THEME.card, borderColor: THEME.gold }}
      >
        <CardContent className={compact ? 'p-2 space-y-1.5' : 'p-2.5 space-y-2'}>
          <div className="flex items-center justify-between">
            <p
              className="text-[9px] font-semibold uppercase tracking-widest"
              style={{ color: THEME.gold }}
            >
              <List className="size-3 inline mr-1" />
              Antre dari MC ({opQueue.length})
            </p>
          </div>

          <div className="relative">
            <Search className="absolute left-2 top-1/2 -translate-y-1/2 size-3.5" style={{ color: THEME.muted }} />
            <Input
              placeholder="Cari NIM / Nama di antrean..."
              value={opSearchQuery}
              onChange={(e) => setOpSearchQuery(e.target.value)}
              className={`pl-7 border-[#533485] bg-[#2a164a] text-white placeholder:text-[#533485] focus-visible:border-[#d4af37] focus-visible:ring-[#d4af37]/30 ${compact ? 'h-7 text-[11px]' : 'h-8 text-xs'}`}
            />
          </div>

          {opQueue.length === 0 ? (
            <p className={`text-center ${compact ? 'text-[9px] py-1' : 'text-[10px] py-1.5'}`} style={{ color: THEME.muted }}>
              {opSearchQuery.trim() ? 'Tidak ditemukan di antrean' : 'Belum ada peserta dikirim MC'}
            </p>
          ) : (
            <div
              className={`overflow-y-auto rounded-md border ${compact ? 'max-h-32' : 'max-h-48'}`}
              style={{ borderColor: THEME.border }}
            >
              {opSearchResults.map((student) => (
                <button
                  key={student.id}
                  onClick={() => {
                    if (!sending) {
                      setOpCurrentTarget(student)
                      setOpSearchQuery('')
                    }
                  }}
                  className={`w-full flex items-center gap-1.5 px-2 text-left transition-colors hover:bg-white/5 cursor-pointer ${compact ? 'py-1' : 'py-1.5'} ${opCurrentTarget?.id === student.id ? 'bg-[#d4af37]/10' : ''}`}
                  style={{
                    borderBottom: `1px solid ${THEME.border}44`,
                    borderLeft: opCurrentTarget?.id === student.id ? `3px solid ${THEME.gold}` : `3px solid transparent`,
                  }}
                >
                  <span className={`font-mono truncate shrink-0 ${compact ? 'text-[9px] w-12' : 'text-[10px] w-14'}`} style={{ color: THEME.muted }}>
                    {student.nim}
                  </span>
                  <span className={`font-medium truncate flex-1 ${compact ? 'text-[10px]' : 'text-[11px]'}`} style={{ color: opCurrentTarget?.id === student.id ? THEME.gold : '#ffffff' }}>
                    {student.nama}
                  </span>
                  {opCurrentTarget?.id === student.id && (
                    <Camera className="size-3 shrink-0" style={{ color: THEME.gold }} />
                  )}
                </button>
              ))}
            </div>
          )}
        </CardContent>
      </Card>
    )
  }

  // ── Gridline color map ────────────────────────────────────────────────────
  const GRIDLINE_COLORS: Record<string, { stroke: string; opacity: number }> = {
    white:  { stroke: '#ffffff', opacity: 0.25 },
    yellow: { stroke: '#facc15', opacity: 0.35 },
    red:    { stroke: '#ef4444', opacity: 0.35 },
    cyan:   { stroke: '#06b6d4', opacity: 0.35 },
    green:  { stroke: '#22c55e', opacity: 0.35 },
  }

  // ── Gridline SVG renderer ─────────────────────────────────────────────────
  const renderGridlineSVG = () => {
    const { stroke, opacity } = GRIDLINE_COLORS[gridlineColor] || GRIDLINE_COLORS.white
    const sw = gridlineThickness * 0.15 // stroke width in viewBox units (0.15, 0.3, 0.45)
    const commonProps = { stroke, strokeWidth: sw, opacity, fill: 'none' }

    switch (gridlineType) {
      case 'thirds':
        return (
          <>
            <line x1="33.333" y1="0" x2="33.333" y2="100" {...commonProps} />
            <line x1="66.666" y1="0" x2="66.666" y2="100" {...commonProps} />
            <line x1="0" y1="33.333" x2="100" y2="33.333" {...commonProps} />
            <line x1="0" y1="66.666" x2="100" y2="66.666" {...commonProps} />
          </>
        )
      case 'quarters':
        return (
          <>
            <line x1="25" y1="0" x2="25" y2="100" {...commonProps} />
            <line x1="50" y1="0" x2="50" y2="100" {...commonProps} />
            <line x1="75" y1="0" x2="75" y2="100" {...commonProps} />
            <line x1="0" y1="25" x2="100" y2="25" {...commonProps} />
            <line x1="0" y1="50" x2="100" y2="50" {...commonProps} />
            <line x1="0" y1="75" x2="100" y2="75" {...commonProps} />
          </>
        )
      case 'crosshair':
        return (
          <>
            <line x1="50" y1="0" x2="50" y2="100" {...commonProps} />
            <line x1="0" y1="50" x2="100" y2="50" {...commonProps} />
            {/* Center circle */}
            <circle cx="50" cy="50" r="8" {...commonProps} />
            <circle cx="50" cy="50" r="20" {...commonProps} strokeDasharray="2 2" />
            {/* Small tick marks */}
            <line x1="50" y1="0" x2="50" y2="5" {...commonProps} strokeWidth={sw * 2} />
            <line x1="50" y1="95" x2="50" y2="100" {...commonProps} strokeWidth={sw * 2} />
            <line x1="0" y1="50" x2="5" y2="50" {...commonProps} strokeWidth={sw * 2} />
            <line x1="95" y1="50" x2="100" y2="50" {...commonProps} strokeWidth={sw * 2} />
          </>
        )
      case 'diagonal':
        return (
          <>
            {/* Diagonal lines + thirds */}
            <line x1="0" y1="0" x2="100" y2="100" {...commonProps} />
            <line x1="100" y1="0" x2="0" y2="100" {...commonProps} />
            <line x1="33.333" y1="0" x2="33.333" y2="100" {...commonProps} opacity={opacity * 0.5} />
            <line x1="66.666" y1="0" x2="66.666" y2="100" {...commonProps} opacity={opacity * 0.5} />
            <line x1="0" y1="33.333" x2="100" y2="33.333" {...commonProps} opacity={opacity * 0.5} />
            <line x1="0" y1="66.666" x2="100" y2="66.666" {...commonProps} opacity={opacity * 0.5} />
          </>
        )
      default:
        return null
    }
  }

  // ── Shared camera view component ─────────────────────────────────────────
  const renderCameraView = () => (
    <div
      className="relative rounded-xl overflow-hidden border-2"
      style={{
        width: cameraDims.width > 0 ? cameraDims.width : undefined,
        height: cameraDims.height > 0 ? cameraDims.height : undefined,
        borderColor: hasActiveTarget ? THEME.gold : THEME.border,
        boxShadow: hasActiveTarget ? `0 0 16px ${THEME.gold}15` : 'none',
        backgroundColor: '#000000',
      }}
    >
      <video
        ref={videoRef}
        autoPlay
        playsInline
        muted
        className="absolute inset-0 w-full h-full object-cover"
        style={{
          filter: cssFilter !== 'none' ? cssFilter : undefined,
        }}
      />

      {frameData && (
        <img
          src={frameData}
          alt="Frame overlay"
          className="absolute inset-0 w-full h-full object-fill pointer-events-none"
          style={{ zIndex: 5 }}
        />
      )}

      {/* Gridline overlay */}
      {gridlineEnabled && (
        <svg
          className="absolute inset-0 w-full h-full pointer-events-none"
          style={{ zIndex: 6 }}
          viewBox="0 0 100 100"
          preserveAspectRatio="none"
        >
          {renderGridlineSVG()}
        </svg>
      )}

      {/* Timer countdown overlay */}
      {effectiveTimerCountdown > 0 && (
        <div className="absolute inset-0 flex items-center justify-center pointer-events-none" style={{ zIndex: 15 }}>
          <div className="flex items-center justify-center" style={{
            width: isMobile ? '80px' : '120px',
            height: isMobile ? '80px' : '120px',
            borderRadius: '50%',
            backgroundColor: `${THEME.bg}cc`,
            border: `4px solid ${THEME.gold}`,
            boxShadow: `0 0 40px ${THEME.gold}66, 0 0 80px ${THEME.gold}33`,
          }}>
            <span className="font-bold" style={{
              color: THEME.gold,
              fontSize: isMobile ? '36px' : '56px',
              textShadow: `0 0 20px ${THEME.gold}88`,
            }}>
              {effectiveTimerCountdown}
            </span>
          </div>
        </div>
      )}

      {/* Palm trigger indicator */}
      {palmTriggerEnabled && palm.isRunning && (
        <div className="absolute top-2 right-2 flex items-center gap-1.5 pointer-events-none" style={{ zIndex: 10 }}>
          <div className="flex items-center gap-1 rounded-full px-2 py-1" style={{
            backgroundColor: palm.palmState === 'triggered' ? '#22c55e88' : palm.palmState === 'confirmed' ? '#22c55e88' : palm.palmState === 'hand_detected' ? '#d4af3788' : 'rgba(0,0,0,0.7)',
            border: `1px solid ${palm.palmState === 'triggered' ? '#22c55e' : palm.palmState === 'confirmed' ? '#22c55e' : palm.palmState === 'hand_detected' ? THEME.gold : THEME.border}`,
          }}>
            <Hand className={`size-3 ${palm.palmState === 'hand_detected' ? 'animate-pulse' : ''}`} style={{ color: palm.palmState === 'triggered' ? '#4ade80' : palm.palmState === 'confirmed' ? '#4ade80' : palm.palmState === 'hand_detected' ? THEME.gold : THEME.muted }} />
            <span className="text-[10px] font-bold" style={{ color: palm.palmState === 'confirmed' ? '#4ade80' : THEME.muted }}>
              {palm.palmState === 'triggered' ? 'Timer ✓' : palm.palmState === 'confirmed' ? 'Siap ✓' : palm.palmState === 'hand_detected' ? 'Tangan...' : 'Tangan'}
            </span>
          </div>
        </div>
      )}

      {/* AI detection indicator */}
      {effectiveShutterMode === 'ai' && ai.isRunning && (
        <div className="absolute top-2 right-2 flex items-center gap-1.5 pointer-events-none" style={{ zIndex: 10 }}>
          <div className="flex items-center gap-1 rounded-full px-2 py-1" style={{
            backgroundColor: ai.momentState !== 'idle' ? '#d4af3788' : 'rgba(0,0,0,0.7)',
            border: `1px solid ${ai.momentState !== 'idle' ? THEME.gold : THEME.border}`,
          }}>
            <Sparkles className={`size-3 ${ai.momentState !== 'idle' ? 'animate-pulse' : ''}`} style={{ color: ai.momentState !== 'idle' ? THEME.gold : THEME.muted }} />
            <span className="text-[10px] font-bold" style={{ color: ai.momentState !== 'idle' ? THEME.gold : THEME.muted }}>
              AI {ai.posesDetected > 0 ? `(${ai.posesDetected})` : ''}
            </span>
          </div>
        </div>
      )}

      {/* Flash overlay */}
      <div
        ref={flashRef}
        className="absolute inset-0 transition-opacity duration-150 pointer-events-none"
        style={{
          backgroundColor: '#ffffff',
          opacity: flashVisible ? 0.85 : 0,
          zIndex: 20,
        }}
      />

      {/* NO CAMERA SIGNAL */}
      {!cameraAvailable && (
        <div className="absolute inset-0 flex flex-col items-center justify-center gap-2 bg-black/80" style={{ zIndex: 8 }}>
          <VideoOff className={isMobile ? 'size-8' : 'size-12'} style={{ color: THEME.border }} />
          <p className={`font-semibold tracking-wider ${isMobile ? 'text-xs' : 'text-sm'}`} style={{ color: THEME.muted }}>
            NO CAMERA SIGNAL
          </p>
          <p className="text-[10px]" style={{ color: THEME.border }}>
            Pastikan kamera terhubung dan izin diberikan
          </p>
        </div>
      )}

      {/* Aspect ratio & frame badges */}
      <div className="absolute top-2 left-2 flex gap-1.5" style={{ zIndex: 10 }}>
        <Badge className="text-[9px] px-1.5 py-0.5 border-0" style={{ backgroundColor: 'rgba(0,0,0,0.6)', color: THEME.muted }}>
          {config?.ratio ?? '4:3'}
        </Badge>
        {frameData && (
          <Badge className="text-[9px] px-1.5 py-0.5 border-0" style={{ backgroundColor: 'rgba(0,0,0,0.6)', color: THEME.gold }}>
            <Frame className="size-2.5 mr-0.5" />Frame
          </Badge>
        )}
        {gridlineEnabled && (
          <Badge className="text-[9px] px-1.5 py-0.5 border-0" style={{ backgroundColor: 'rgba(0,0,0,0.6)', color: GRIDLINE_COLORS[gridlineColor]?.stroke ?? '#ffffff' }}>
            <Grid3x3 className="size-2.5 mr-0.5" />{gridlineType === 'thirds' ? '1/3' : gridlineType === 'quarters' ? '1/4' : gridlineType === 'crosshair' ? '+' : 'X'}
          </Badge>
        )}
      </div>

      {/* Mobile: Switch camera button */}
      {isMobile && videoDevices.length > 1 && (
        <button
          onClick={handleSwitchCamera}
          className="absolute top-2 right-2 flex items-center justify-center w-9 h-9 rounded-full bg-black/60 backdrop-blur-sm cursor-pointer active:scale-95 transition-transform"
          style={{ zIndex: 10 }}
          title="Ganti kamera"
        >
          <SwitchCamera className="size-4" style={{ color: THEME.gold }} />
        </button>
      )}

      <canvas ref={canvasRef} className="hidden" />
    </div>
  )

  // ── Capture button (shared) ──────────────────────────────────────────────
  const renderCaptureButton = (size: 'normal' | 'large' | 'xl' = 'normal') => {
    const btnClass = size === 'xl'
      ? 'w-full h-16 sm:h-20 text-lg sm:text-xl font-bold cursor-pointer rounded-xl transition-all duration-200 active:scale-[0.97] shadow-lg'
      : size === 'large'
        ? 'w-full h-14 text-base font-bold cursor-pointer rounded-lg transition-all duration-200 active:scale-[0.97]'
        : 'w-full h-12 text-sm font-bold cursor-pointer rounded-lg transition-all duration-200 hover:scale-[1.01] active:scale-[0.99]'
    // Icon size scales with button size so the shutter is easy to hit
    const iconCls = size === 'xl' ? 'size-6 sm:size-7' : 'size-4'

    if (capturePhase === 'standby') {
      return (
        <Button disabled className={`${btnClass} cursor-not-allowed`} style={{ backgroundColor: THEME.panel, color: THEME.muted, border: `2px solid ${THEME.border}`, opacity: 0.6 }}>
          <Aperture className={`${iconCls} mr-2`} />STANDBY
        </Button>
      )
    }

    // Timer is counting down — show cancel button
    if (effectiveTimerCountdown > 0) {
      return (
        <Button onClick={handleCaptureButtonClick} className={btnClass} style={{
          backgroundColor: '#ef4444',
          color: '#ffffff',
          border: '2px solid #ef4444',
          boxShadow: '0 0 30px #ef444444, 0 0 60px #ef444422',
        }}>
          <X className={`${iconCls} mr-2`} />BATAL ({effectiveTimerCountdown}s)
        </Button>
      )
    }

    if (capturePhase === 'ready-1') {
      const isAutoMode = effectiveShutterMode === 'ai'
      const isTimer = isTimerMode(effectiveShutterMode)

      if (isAutoMode) {
        // AI auto-capture mode: show status button
        const isDetecting = ai.isRunning
        return (
          <Button disabled className={`${btnClass} cursor-default`} style={{
            backgroundColor: isDetecting ? '#22c55e33' : THEME.panel,
            color: isDetecting ? '#4ade80' : THEME.muted,
            border: `2px solid ${isDetecting ? '#22c55e' : THEME.border}`,
          }}>
            {isDetecting ? <Loader2 className={`${iconCls} mr-2 animate-spin`} /> : <Aperture className={`${iconCls} mr-2`} />}
            {isDetecting ? 'AI Mendeteksi Pose...' : 'AI Loading...'}
          </Button>
        )
      }

      if (isTimer) {
        return (
          <Button onClick={handleCaptureButtonClick} className={btnClass} style={{
            backgroundColor: THEME.gold,
            color: THEME.bg,
            border: `2px solid ${THEME.gold}`,
            boxShadow: `0 0 30px ${THEME.gold}44, 0 0 60px ${THEME.gold}22`,
          }}>
            <Timer className={`${iconCls} mr-2`} />
            {photoshoot ? `FOTO (${getTimerDuration(effectiveShutterMode)}s)` : `FOTO 1 — TOGA (${getTimerDuration(effectiveShutterMode)}s)`}
          </Button>
        )
      }

      // Manual mode
      return (
        <Button onClick={handleCaptureButtonClick} className={btnClass} style={photoshoot
          ? { backgroundColor: '#4ade80', color: '#1a0b2e', border: '2px solid #4ade80', boxShadow: '0 0 30px #4ade8044, 0 0 60px #4ade8022' }
          : { backgroundColor: THEME.gold, color: THEME.bg, border: `2px solid ${THEME.gold}`, boxShadow: `0 0 30px ${THEME.gold}44, 0 0 60px ${THEME.gold}22` }
        }>
          <Camera className={`${iconCls} mr-2`} />{photoshoot ? 'FOTO' : 'FOTO 1 — TOGA'}
        </Button>
      )
    }

    if (capturePhase === 'ready-2') {
      const isAutoMode = effectiveShutterMode === 'ai'
      const isTimer = isTimerMode(effectiveShutterMode)

      if (isAutoMode) {
        const isDetecting = ai.isRunning
        return (
          <Button disabled className={`${btnClass} cursor-default`} style={{
            backgroundColor: isDetecting ? '#22c55e33' : THEME.panel,
            color: isDetecting ? '#4ade80' : THEME.muted,
            border: `2px solid ${isDetecting ? '#22c55e' : THEME.border}`,
          }}>
            {isDetecting ? <Loader2 className={`${iconCls} mr-2 animate-spin`} /> : <Aperture className={`${iconCls} mr-2`} />}
            AI Mendeteksi Ijazah...
          </Button>
        )
      }

      if (isTimer) {
        return (
          <Button onClick={handleCaptureButtonClick} className={btnClass} style={{
            backgroundColor: THEME.gold,
            color: THEME.bg,
            border: `2px solid ${THEME.gold}`,
            boxShadow: `0 0 30px ${THEME.gold}44, 0 0 60px ${THEME.gold}22`,
          }}>
            <Timer className={`${iconCls} mr-2`} />
            FOTO 2 — IJAZAH ({getTimerDuration(effectiveShutterMode)}s)
          </Button>
        )
      }

      return (
        <Button onClick={handleCaptureButtonClick} className={btnClass} style={{ backgroundColor: '#22c55e', color: '#ffffff', border: '2px solid #22c55e', boxShadow: '0 0 30px #22c55e44, 0 0 60px #22c55e22' }}>
          <Camera className={`${iconCls} mr-2`} />FOTO 2 — IJAZAH
        </Button>
      )
    }

    if (capturePhase === 'sending') {
      return (
        <Button disabled className={`${btnClass} cursor-not-allowed`} style={{ backgroundColor: THEME.panel, color: THEME.muted, border: `2px solid ${THEME.border}` }}>
          <Loader2 className={`${iconCls} mr-2 animate-spin`} />MENGIRIM...
        </Button>
      )
    }

    return null
  }

  // ── Queue list (shared) ──────────────────────────────────────────────────
  const renderQueueList = (compact = false) => (
    <Card
      className={`${compact ? 'flex-1 min-h-0' : 'flex-1 min-h-0'} border rounded-lg overflow-hidden flex flex-col`}
      style={{ backgroundColor: THEME.card, borderColor: THEME.border }}
    >
      <div
        className="shrink-0 flex items-center justify-between px-3 py-2"
        style={{ borderBottom: `1px solid ${THEME.border}` }}
      >
        <div className="flex items-center gap-2">
          <h3 className="text-xs font-semibold" style={{ color: '#ffffff' }}>
            Antrean
          </h3>
          <span
            className="text-xs font-bold px-2 py-0.5 rounded-full"
            style={{
              backgroundColor: remainingCount > 10 ? `${THEME.red}33` : remainingCount > 0 ? `${THEME.gold}33` : `${THEME.border}44`,
              color: remainingCount > 10 ? THEME.red : remainingCount > 0 ? THEME.gold : THEME.muted,
              border: `1px solid ${remainingCount > 10 ? `${THEME.red}55` : remainingCount > 0 ? `${THEME.gold}55` : THEME.border}`,
            }}
          >
            {remainingCount}
          </span>
        </div>
        <div className="flex items-center gap-1.5">
          <NetworkQualityBadge detailed={true} />
          <span className="text-[10px]" style={{ color: THEME.muted }}>Ch.{myChannel}</span>
        </div>
      </div>

      {!compact && (
        <div
          className="shrink-0 grid grid-cols-[24px_60px_1fr_60px] gap-0.5 px-2 py-1 text-[8px] font-semibold uppercase tracking-wider"
          style={{ backgroundColor: THEME.panel, color: THEME.muted, borderBottom: `1px solid ${THEME.border}` }}
        >
          <span>No</span><span>NIM</span><span>Nama</span><span className="text-right">Status</span>
        </div>
      )}

      <ScrollArea className="flex-1 min-h-0">
        <div className="flex flex-col">
          {channelStudents.length === 0 ? (
            <div className="flex items-center justify-center py-8">
              <p className="text-xs" style={{ color: THEME.muted }}>Tidak ada mahasiswa</p>
            </div>
          ) : (
            channelStudents.map((student, idx) => {
              const isActive = student.status === `active_${myChannel}`
              const isNext = student.id === nextPending?.id && student.status === 'pending'

              if (compact) {
                return (
                  <div
                    key={student.id}
                    ref={isActive ? activeRowRef : isNext ? nextRowRef : undefined}
                    className="flex items-center gap-2 px-3 py-1.5 transition-colors duration-200"
                    style={getRowStyle(student)}
                  >
                    <span className="text-[10px] font-mono w-5 shrink-0" style={{ color: THEME.muted }}>{idx + 1}</span>
                    <span className={`text-xs font-medium truncate flex-1 ${student.status === 'done' ? 'line-through' : ''}`} style={{ color: isActive ? THEME.gold : student.status === 'done' ? THEME.muted : '#ffffff' }}>
                      {student.nama}
                    </span>
                    <div className="shrink-0">{renderStatusBadge(student.status)}</div>
                  </div>
                )
              }

              return (
                <div
                  key={student.id}
                  ref={isActive ? activeRowRef : isNext ? nextRowRef : undefined}
                  className="grid grid-cols-[24px_60px_1fr_60px] gap-0.5 items-center px-2 py-1 transition-colors duration-200"
                  style={getRowStyle(student)}
                >
                  <span className="text-[9px] font-mono" style={{ color: THEME.muted }}>{idx + 1}</span>
                  <span className="text-[9px] font-mono truncate" style={{ color: THEME.muted }}>{student.nim}</span>
                  <span className={`text-[10px] font-medium truncate ${student.status === 'done' ? 'line-through' : ''}`} style={{ color: isActive ? THEME.gold : student.status === 'done' ? THEME.muted : '#ffffff' }}>
                    {student.nama}
                  </span>
                  <div className="flex justify-end">{renderStatusBadge(student.status)}</div>
                </div>
              )
            })
          )}
        </div>
      </ScrollArea>
    </Card>
  )

  // ── Main render ──────────────────────────────────────────────────────────
  if (!currentProject) {
    return (
      <div className="flex items-center justify-center h-full" style={{ backgroundColor: THEME.bg, color: THEME.muted }}>
        <p className="text-sm opacity-60">Belum ada proyek aktif</p>
      </div>
    )
  }

  // ── Floating toolbar toggle button ──────────────────────────────────────
  const renderToolbarButton = (
    icon: React.ReactNode,
    label: string,
    isActive: boolean,
    onClick: () => void,
    badge?: React.ReactNode,
  ) => (
    <button
      onClick={onClick}
      className="flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-[10px] font-semibold uppercase tracking-wider transition-all duration-200 cursor-pointer"
      style={{
        backgroundColor: isActive ? `${THEME.gold}22` : THEME.panel,
        color: isActive ? THEME.gold : THEME.muted,
        border: `1px solid ${isActive ? THEME.gold : THEME.border}`,
        boxShadow: isActive ? `0 0 8px ${THEME.gold}22` : 'none',
      }}
      title={label}
    >
      {icon}
      <span className="hidden lg:inline">{label}</span>
      {badge}
    </button>
  )

  // ── MOBILE LAYOUT ────────────────────────────────────────────────────────
  if (isMobile) {
    return (
      <div className="flex flex-col h-full touch-no-select" style={{ backgroundColor: THEME.bg }}>

        {/* ── Camera Zone ────────────────────────────────────────────────── */}
        <div
          ref={cameraZoneRef}
          className="flex-1 flex items-center justify-center min-h-0 p-1 relative"
        >
          {renderCameraView()}

          {/* ── Floating Toolbar (top-right of camera view) ──────────────── */}
          <div
            className="absolute top-2 right-2 z-20 flex items-center gap-1 rounded-xl px-1.5 py-1"
            style={{
              backgroundColor: `${THEME.panel}dd`,
              border: `1px solid ${THEME.border}`,
              backdropFilter: 'blur(12px)',
            }}
          >
            {renderToolbarButton(
              <Camera className="size-3" />,
              'Shutter',
              showShutterPanel,
              () => { setShowShutterPanel((v) => !v); setShowGridlinePanel(false); setShowQueueOnMobile(false) },
              shutterMode !== 'manual' ? (
                <span className="text-[8px] font-bold px-1 py-0.5 rounded-full" style={{ backgroundColor: `${THEME.gold}33`, color: THEME.gold, border: `1px solid ${THEME.gold}55` }}>
                  {shutterMode === 'ai' ? 'AI' : getTimerDuration(shutterMode) + 's'}
                </span>
              ) : undefined,
            )}
            {renderToolbarButton(
              <Grid3x3 className="size-3" />,
              'Grid',
              showGridlinePanel,
              () => { setShowGridlinePanel((v) => !v); setShowShutterPanel(false); setShowQueueOnMobile(false) },
              gridlineEnabled ? (
                <span className="text-[8px] font-bold px-1 py-0.5 rounded-full" style={{ backgroundColor: `${THEME.gold}33`, color: THEME.gold, border: `1px solid ${THEME.gold}55` }}>
                  {gridlineType === 'thirds' ? '⅓' : gridlineType === 'quarters' ? '¼' : '+'}
                </span>
              ) : undefined,
            )}
            {renderToolbarButton(
              <List className="size-3" />,
              'Antrean',
              showQueueOnMobile,
              () => { setShowQueueOnMobile((v) => !v); setShowShutterPanel(false); setShowGridlinePanel(false) },
              remainingCount > 0 ? (
                <span className="text-[8px] font-bold px-1 py-0.5 rounded-full" style={{
                  backgroundColor: remainingCount > 10 ? `${THEME.red}33` : `${THEME.gold}33`,
                  color: remainingCount > 10 ? THEME.red : THEME.gold,
                  border: `1px solid ${remainingCount > 10 ? `${THEME.red}55` : `${THEME.gold}55`}`,
                }}>
                  {remainingCount}
                </span>
              ) : undefined,
            )}
          </div>
        </div>

        {/* ── Capture Button (centered, directly below camera view) ─────── */}
        <div
          className="shrink-0 w-full px-3 py-2 border-t"
          style={{
            backgroundColor: `${THEME.panel}ee`,
            borderColor: THEME.border,
            backdropFilter: 'blur(12px)',
          }}
        >
          <div className="mx-auto w-full max-w-md">
            {renderCaptureButton('xl')}
          </div>
        </div>

        {/* ── Bottom Control Bar ──────────────────────────────────────────── */}
        <div
          className="shrink-0 border-t safe-bottom"
          style={{
            backgroundColor: `${THEME.panel}ee`,
            borderColor: THEME.border,
            backdropFilter: 'blur(12px)',
          }}
        >
          {/* Target info row */}
          <div className="flex items-center gap-2 px-3 py-2">
            <div
              className="shrink-0 flex items-center justify-center w-8 h-8 rounded-full border-2"
              style={{
                backgroundColor: THEME.bg,
                borderColor: hasActiveTarget ? THEME.gold : THEME.border,
              }}
            >
              <User className="size-3.5" style={{ color: hasActiveTarget ? THEME.gold : THEME.border }} />
            </div>

            <div className="flex-1 min-w-0">
              {hasActiveTarget ? (
                <div className="flex items-center gap-2">
                  <div className="min-w-0">
                    <p className="text-sm font-bold leading-tight truncate" style={{ color: '#ffffff' }}>
                      {opCurrentTarget.nama}
                    </p>
                    <p className="text-[10px] font-mono" style={{ color: THEME.muted }}>
                      {opCurrentTarget.nim}
                    </p>
                  </div>
                  <Badge
                    className={`text-[9px] px-1.5 py-0.5 shrink-0 ${hasActiveTarget && !sending ? 'animate-pulse' : ''}`}
                    style={{
                      backgroundColor:
                        capturePhase === 'ready-1' ? `${THEME.gold}33`
                          : capturePhase === 'ready-2' ? '#22c55e33'
                            : capturePhase === 'sending' ? `${THEME.border}66`
                              : `${THEME.border}44`,
                      color:
                        capturePhase === 'ready-1' ? THEME.gold
                          : capturePhase === 'ready-2' ? '#4ade80'
                            : THEME.muted,
                      border: `1px solid ${capturePhase === 'ready-1' ? `${THEME.gold}66` : capturePhase === 'ready-2' ? '#22c55e66' : THEME.border}`,
                    }}
                  >
                    {capturePhase === 'sending' && <Loader2 className="size-2.5 mr-0.5 animate-spin" />}
                    {capturePhase === 'ready-1' && <Camera className="size-2.5 mr-0.5" />}
                    {capturePhase === 'ready-2' && <CheckCircle2 className="size-2.5 mr-0.5" />}
                    {capturePhase === 'standby' && <Clock className="size-2.5 mr-0.5" />}
                    {progressText}
                  </Badge>
                </div>
              ) : (
                <p className="text-xs italic" style={{ color: THEME.muted }}>
                  Menunggu panggilan MC...
                </p>
              )}
            </div>
          </div>

          {/* Shutter Mode Panel (toggleable) */}
          {showShutterPanel && (
            <div className="px-3 py-1.5 border-t" style={{ borderColor: THEME.border }}>
              {renderShutterModeSelector(true)}
              {videoDevices.length > 1 && (
                <div className="mt-1.5">
                  <Select value={selectedDeviceId} onValueChange={setSelectedDeviceId}>
                    <SelectTrigger
                      className="text-[10px] h-7 w-full"
                      style={{ backgroundColor: THEME.bg, borderColor: THEME.border, color: THEME.muted }}
                    >
                      <Video className="size-3 mr-1 shrink-0" style={{ color: THEME.gold }} />
                      <SelectValue placeholder="Pilih Kamera" />
                    </SelectTrigger>
                    <SelectContent style={{ backgroundColor: THEME.panel, borderColor: THEME.border }}>
                      {videoDevices.map((dev) => (
                        <SelectItem key={dev.deviceId} value={dev.deviceId} style={{ color: '#ffffff' }}>
                          {dev.label}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
              )}
            </div>
          )}

          {/* Gridline Settings Panel (toggleable) */}
          {showGridlinePanel && (
            <div className="px-3 py-1.5 border-t" style={{ borderColor: THEME.border }}>
              {renderGridlineSettings(true)}
            </div>
          )}

          {/* Queue Panel (toggleable) */}
          {showQueueOnMobile && (
            <div className="border-t" style={{ borderColor: THEME.border }}>
              {/* Operator search (photoshoot only) */}
              {renderOpSearch(true)}
              {/* Queue list */}
              <div style={{ maxHeight: '35vh' }}>
                {renderQueueList(true)}
              </div>
            </div>
          )}
        </div>
      </div>
    )
  }

  // ── DESKTOP LAYOUT ──────────────────────────────────────────────────────
  return (
    <div className="flex flex-col h-full overflow-hidden" style={{ backgroundColor: THEME.bg }}>
      {/* Main resizable area */}
      <ResizablePanelGroup direction="horizontal" className="flex-1 min-h-0">
        {/* LEFT: Camera zone + capture button */}
        <ResizablePanel defaultSize={65} minSize={40} maxSize={85}>
          <div className="flex flex-col h-full">
            {/* Camera zone */}
            <div
              ref={cameraZoneRef}
              className="flex-1 flex items-center justify-center min-h-0 p-2 relative"
            >
              {renderCameraView()}

              {/* ── Fullscreen Exit Button (top-left, visible when fullscreen) ── */}
              {isFullscreen && (
                <button
                  onClick={exitFullscreen}
                  className="absolute top-3 left-3 z-30 flex items-center gap-1.5 rounded-xl px-3 py-1.5 text-xs font-semibold cursor-pointer transition-all duration-200 hover:bg-white/20"
                  style={{
                    backgroundColor: 'rgba(0,0,0,0.7)',
                    color: '#ffffff',
                    border: `1px solid ${THEME.border}`,
                    backdropFilter: 'blur(12px)',
                  }}
                  title="Keluar Fullscreen"
                >
                  <X className="size-3.5" />
                  <span>Keluar Fullscreen</span>
                </button>
              )}

            </div>

            {/* Capture Button (full width of left panel) */}
            <div
              className="shrink-0 w-full px-4 py-3 border-t"
              style={{
                backgroundColor: `${THEME.panel}ee`,
                borderColor: THEME.border,
                backdropFilter: 'blur(12px)',
              }}
            >
              <div className="mx-auto w-full max-w-xl">
                {renderCaptureButton('xl')}
              </div>
            </div>
          </div>
        </ResizablePanel>

        {/* Resizable handle */}
        <ResizableHandle
          withHandle
          className="w-1.5 hover:w-2 transition-all duration-150"
          style={{
            backgroundColor: THEME.border,
          }}
        />

        {/* RIGHT: Toolbar + Target info + toggleable panels */}
        <ResizablePanel defaultSize={35} minSize={15} maxSize={60}>
          <div className="flex flex-col h-full overflow-hidden" style={{ backgroundColor: THEME.panel }}>
            {/* ── Toolbar Row (top of right panel, no longer covering camera) ── */}
            <div
              className="shrink-0 flex items-center gap-1.5 px-3 py-2 border-b"
              style={{ borderColor: THEME.border }}
            >
              {renderToolbarButton(
                <Camera className="size-3.5" />,
                'Shutter',
                showShutterPanel,
                () => setShowShutterPanel((v) => !v),
                shutterMode !== 'manual' ? (
                  <span className="text-[9px] font-bold px-1.5 py-0.5 rounded-full" style={{ backgroundColor: `${THEME.gold}33`, color: THEME.gold, border: `1px solid ${THEME.gold}55` }}>
                    {shutterMode === 'ai' ? 'AI' : getTimerDuration(shutterMode) + 's'}
                  </span>
                ) : undefined,
              )}
              {renderToolbarButton(
                <Grid3x3 className="size-3.5" />,
                'Grid',
                showGridlinePanel,
                () => setShowGridlinePanel((v) => !v),
                gridlineEnabled ? (
                  <span className="text-[9px] font-bold px-1.5 py-0.5 rounded-full" style={{ backgroundColor: `${THEME.gold}33`, color: THEME.gold, border: `1px solid ${THEME.gold}55` }}>
                    {gridlineType === 'thirds' ? '⅓' : gridlineType === 'quarters' ? '¼' : gridlineType === 'crosshair' ? '+' : '✕'}
                  </span>
                ) : undefined,
              )}
              {renderToolbarButton(
                <List className="size-3.5" />,
                'Antrean',
                showQueuePanel,
                () => setShowQueuePanel((v) => !v),
                remainingCount > 0 ? (
                  <span className="text-[9px] font-bold px-1.5 py-0.5 rounded-full" style={{
                    backgroundColor: remainingCount > 10 ? `${THEME.red}33` : `${THEME.gold}33`,
                    color: remainingCount > 10 ? THEME.red : THEME.gold,
                    border: `1px solid ${remainingCount > 10 ? `${THEME.red}55` : `${THEME.gold}55`}`,
                  }}>
                    {remainingCount}
                  </span>
                ) : undefined,
              )}
              <div className="flex-1" />
              {renderToolbarButton(
                isFullscreen ? <Minimize className="size-3.5" /> : <Maximize className="size-3.5" />,
                isFullscreen ? 'Keluar FS' : 'Fullscreen',
                isFullscreen,
                toggleFullscreen,
              )}
            </div>

            {/* Target Info Row — compact, always visible */}
            <div className="shrink-0 flex items-center gap-2 px-3 py-2 border-b" style={{ borderColor: THEME.border }}>
              <div
                className="shrink-0 flex items-center justify-center w-8 h-8 rounded-full border-2"
                style={{
                  backgroundColor: THEME.bg,
                  borderColor: hasActiveTarget ? THEME.gold : THEME.border,
                }}
              >
                <User className="size-3.5" style={{ color: hasActiveTarget ? THEME.gold : THEME.border }} />
              </div>

              <div className="flex-1 min-w-0">
                {hasActiveTarget ? (
                  <div className="flex items-center gap-2">
                    <div className="min-w-0">
                      <p className="text-sm font-bold leading-tight truncate" style={{ color: '#ffffff' }}>
                        {opCurrentTarget.nama}
                      </p>
                      <p className="text-[10px] font-mono" style={{ color: THEME.muted }}>
                        {opCurrentTarget.nim}
                      </p>
                    </div>
                    <Badge
                      className={`text-[9px] px-1.5 py-0.5 shrink-0 ${hasActiveTarget && !sending ? 'animate-pulse' : ''}`}
                      style={{
                        backgroundColor: capturePhase === 'ready-1' ? `${THEME.gold}33` : capturePhase === 'ready-2' ? '#22c55e33' : capturePhase === 'sending' ? `${THEME.border}66` : `${THEME.border}44`,
                        color: capturePhase === 'ready-1' ? THEME.gold : capturePhase === 'ready-2' ? '#4ade80' : THEME.muted,
                        border: `1px solid ${capturePhase === 'ready-1' ? `${THEME.gold}66` : capturePhase === 'ready-2' ? '#22c55e66' : THEME.border}`,
                      }}
                    >
                      {capturePhase === 'sending' && <Loader2 className="size-2.5 mr-0.5 animate-spin" />}
                      {capturePhase === 'ready-1' && <Camera className="size-2.5 mr-0.5" />}
                      {capturePhase === 'ready-2' && <CheckCircle2 className="size-2.5 mr-0.5" />}
                      {capturePhase === 'standby' && <Clock className="size-2.5 mr-0.5" />}
                      {progressText}
                    </Badge>
                  </div>
                ) : (
                  <p className="text-xs italic" style={{ color: THEME.muted }}>
                    Menunggu panggilan MC...
                  </p>
                )}
              </div>
            </div>

            {/* Camera selector dropdown */}
            <div className="shrink-0 px-3 py-2 border-b" style={{ borderColor: THEME.border }}>
              <Select value={selectedDeviceId} onValueChange={setSelectedDeviceId}>
                <SelectTrigger className="w-full text-[11px] h-7" style={{ backgroundColor: THEME.bg, borderColor: THEME.border, color: THEME.muted }}>
                  <Video className="size-3 mr-1 shrink-0" style={{ color: THEME.gold }} />
                  <SelectValue placeholder="Pilih Kamera" />
                </SelectTrigger>
                <SelectContent style={{ backgroundColor: THEME.panel, borderColor: THEME.border }}>
                  {videoDevices.length === 0 ? (
                    <SelectItem value="__none" disabled>Tidak ada kamera</SelectItem>
                  ) : (
                    videoDevices.map((dev) => (
                      <SelectItem key={dev.deviceId} value={dev.deviceId} style={{ color: '#ffffff' }}>{dev.label}</SelectItem>
                    ))
                  )}
                </SelectContent>
              </Select>
            </div>

            {/* Scrollable panels area */}
            <div className="flex-1 min-h-0 overflow-y-auto">
              {/* Shutter Mode Panel — toggleable */}
              {showShutterPanel && (
                <div className="px-3 py-2 border-b" style={{ borderColor: THEME.border }}>
                  <Card className="border rounded-lg" style={{ backgroundColor: THEME.card, borderColor: THEME.gold }}>
                    <CardContent className="p-2.5">
                      {renderShutterModeSelector(true)}
                    </CardContent>
                  </Card>
                </div>
              )}

              {/* Gridline Settings Panel — toggleable */}
              {showGridlinePanel && (
                <div className="px-3 py-2 border-b" style={{ borderColor: THEME.border }}>
                  <Card className="border rounded-lg" style={{ backgroundColor: THEME.card, borderColor: THEME.gold }}>
                    <CardContent className="p-2.5">
                      {renderGridlineSettings(true)}
                    </CardContent>
                  </Card>
                </div>
              )}

              {/* Queue Panel — toggleable, fills remaining space */}
              {showQueuePanel && (
                <div className="flex flex-col px-3 py-2" style={{ borderColor: THEME.border }}>
                  {/* Operator search (photoshoot only) */}
                  {renderOpSearch(true)}
                  {/* Queue list in compact mode */}
                  <div className="flex-1 min-h-0">
                    {renderQueueList(true)}
                  </div>
                </div>
              )}
            </div>
          </div>
        </ResizablePanel>
      </ResizablePanelGroup>
    </div>
  )
}

export default OperatorPanel
