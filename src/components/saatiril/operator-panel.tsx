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
  Monitor,
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
  isPhotoshootMode,
  isDualPhotoshootMode,
} from '@/store/use-saatiril-store'
import { emitLocal, onLocal, offLocal } from '@/lib/socket'
import { useIsMobile } from '@/hooks/use-mobile'
import { NetworkQualityBadge } from '@/components/saatiril/network-quality-badge'
import { useAIDetection, type AIMomentEvent } from '@/hooks/use-ai-detection'
import { useFingerDetection } from '@/hooks/use-finger-detection'

// ─── Theme tokens ───────────────────────────────────────────────────────────
const THEME = {
  bg: '#1a0b2e',
  panel: '#2a164a',
  card: '#3b2263',
  border: '#533485',
  gold: '#d4af37',
  muted: '#c4b5fd',
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
function sanitizeNama(nama: string): string {
  return nama.trim().replace(/\s+/g, '_').replace(/[^a-zA-Z0-9_]/g, '')
}

function buildFilename(nim: string, nama: string, suffix: number, type: string): string {
  return `${nim}_${sanitizeNama(nama)}_${suffix}_${type}.jpg`
}

function buildPhotoshootFilename(nim: string, nama: string, channel: number): string {
  return channel > 1
    ? `${nim}_${sanitizeNama(nama)}_Ch${channel}.jpg`
    : `${nim}_${sanitizeNama(nama)}.jpg`
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
export function OperatorPanel({ readOnly = false }: { readOnly?: boolean }) {
  const isMobile = useIsMobile()

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
  const saveProjectsToStorage = useSaatirilStore((s) => s.saveProjectsToStorage)

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

  // ── Shutter mode state ───────────────────────────────────────────────────
  const [shutterMode, setShutterMode] = useState<ShutterMode>('manual')
  const [timerCountdown, setTimerCountdown] = useState<number>(0)
  const [timerActive, setTimerActive] = useState(false)
  const timerIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null)

  // ── AI auto-capture ──────────────────────────────────────────────────────
  const ai = useAIDetection()

  // ── Finger detection ─────────────────────────────────────────────────────
  const finger = useFingerDetection()

  // ── Finger gesture triggered timer feedback ───────────────────────────────
  const [fingerTriggeredTimer, setFingerTriggeredTimer] = useState(false)

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
  const frameData = (config?.frame && config.frame !== '__FRAME_SAVED__') ? config.frame : null

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

  const hasActiveTarget = opCurrentTarget !== null

  // Finger gesture auto-trigger is available when a timer mode is selected
  // NOTE: hasActiveTarget must be declared BEFORE fingerGestureActive to avoid TDZ crash
  const fingerGestureActive = isTimerMode(effectiveShutterMode) && cameraAvailable && hasActiveTarget && !timerActive

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

  // hasActiveTarget is declared earlier (before fingerGestureActive) to avoid TDZ crash

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
      if (data.channel !== myChannelRef.current) return
      if (photoshoot) {
        setMcCallBuffer((prev) => {
          if (prev.some((s) => s.id === data.student.id)) return prev
          return [...prev, data.student]
        })
      } else {
        setOpCurrentTarget(data.student)
      }
    }
    onLocal('MC_CALL', handleMcCall)
    return () => { offLocal('MC_CALL', handleMcCall) }
  }, [setOpCurrentTarget, photoshoot])

  // ── Socket: SYNC_DB ─────────────────────────────────────────────────────
  useEffect(() => {
    const handleSyncDb = (data: SyncDbData) => {
      const proj = currentProjectRef.current
      if (!proj) return
      const mergedDb = mergeDatabases(proj.database, data.project.database)
      const mergedConfig = preserveFrameOnSync(data.project.config, proj.config)
      updateCurrentProject({ ...proj, database: mergedDb, photoHistory: data.project.photoHistory?.length ? data.project.photoHistory : proj.photoHistory, config: mergedConfig })

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
    }
    onLocal('SYNC_DB', handleSyncDb)
    return () => { offLocal('SYNC_DB', handleSyncDb) }
  }, [setOpCurrentTarget, updateCurrentProject])

  // ── Finalize capture (optimized: no redundant SYNC_DB, minimal delays) ────
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

      // Photoshoot mode: save after 1 photo
      if (isPhotoshoot && photoCount >= 1) {
        if (!currentTarget) {
          console.warn('[SAATIRIL OP] finalizeCapture: opCurrentTarget is null — aborting')
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

        // Update project with photoHistory + mark student done if all channels complete
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

          // Check if all channels done for this student
          const chCount = channelCount(store.currentProject.config.mode)
          let allChannelsDone = true
          for (let ch = 1; ch <= chCount; ch++) {
            if (!newHistory.some((h) => h.student.id === student.id && h.channel === ch)) {
              allChannelsDone = false
              break
            }
          }

          const updatedProject = {
            ...store.currentProject,
            database: store.currentProject.database.map((s) =>
              s.id === student.id && allChannelsDone ? { ...s, status: 'done' as StudentStatus } : s
            ),
            photoHistory: newHistory,
          }
          store.updateCurrentProject(updatedProject)
          // Send SYNC_DB with updated project (includes photoHistory + status)
          emitLocal('SYNC_DB', { project: stripFrameForSync(updatedProject) })
        }

        setMcCallBuffer((prev) => prev.filter((s) => s.id !== student.id))
        saveProjectsToStorage()

        console.log('[SAATIRIL OP] Emitting PHOTOS_SAVED for student:', student.nama, 'channel:', myChannel)

        emitLocal('PHOTOS_SAVED', {
          student: { ...student, status: student.status },
          photos: allPhotos,
          channel: myChannel,
        })
        emitLocal('OP_PROGRESS', { channel: myChannel, status: 'Selesai — Menunggu target...' })

        // Save to disk in background (non-blocking)
        const projConfig = useSaatirilStore.getState().currentProject?.config
        if (projConfig) {
          const api = window.saatirilAPI
          if (api?.savePhoto) {
            const targetFolder = projConfig.targetFolder
            const filename = buildPhotoshootFilename(student.nim, student.nama, myChannel)
            api.savePhoto({ base64Data: allPhotos[0], filename, targetFolder }).then((path: string | null) => {
              if (path) console.log(`[SAATIRIL OP] Photo saved to disk: → ${path}`)
              else console.warn('[SAATIRIL OP] Photo failed to save to disk')
            }).catch((err: Error) => {
              console.error('[SAATIRIL OP] Error saving photo to disk:', err)
            })
          }
        }

        // Reset immediately — no artificial delay needed
        setSending(false)
        isCapturingRef.current = false
        resetOpState()
        return
      }

      // Standard mode (single/dual): 2 photos
      if (photoCount === 1) {
        isCapturingRef.current = false
        emitLocal('OP_PROGRESS', { channel: myChannel, status: 'Pose 1 OK — Siap Foto 2' })
      } else if (photoCount >= 2) {
        if (!currentTarget) {
          console.warn('[SAATIRIL OP] finalizeCapture: opCurrentTarget is null — aborting')
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

        // Single Zustand update: mark done + add photoHistory
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
            database: store.currentProject.database.map((s) =>
              s.id === student.id ? { ...s, status: 'done' as StudentStatus } : s
            ),
            photoHistory: newHistory,
          }
          store.updateCurrentProject(updatedProject)
          // Send SYNC_DB with updated project (includes photoHistory + done status)
          emitLocal('SYNC_DB', { project: stripFrameForSync(updatedProject) })
        }

        saveProjectsToStorage()

        console.log('[SAATIRIL OP] Emitting PHOTOS_SAVED for student:', student.nama, 'channel:', myChannel)

        emitLocal('PHOTOS_SAVED', {
          student: { ...student, status: 'done' },
          photos: allPhotos,
          channel: myChannel,
        })
        emitLocal('OP_PROGRESS', { channel: myChannel, status: 'Selesai — Menunggu target...' })

        // Save to disk in background (non-blocking)
        const projConfig = useSaatirilStore.getState().currentProject?.config
        if (projConfig) {
          const api = window.saatirilAPI
          if (api?.savePhoto) {
            const targetFolder = projConfig.targetFolder
            const togaFilename = buildFilename(student.nim, student.nama, 1, 'Toga')
            const ijazahFilename = buildFilename(student.nim, student.nama, 2, 'Ijazah')
            Promise.all([
              api.savePhoto({ base64Data: allPhotos[0], filename: togaFilename, targetFolder }),
              api.savePhoto({ base64Data: allPhotos[1], filename: ijazahFilename, targetFolder }),
            ]).then(([path1, path2]) => {
              if (path1 && path2) console.log(`[SAATIRIL OP] Photos saved to disk:\n  → ${path1}\n  → ${path2}`)
              else console.warn('[SAATIRIL OP] Some photos failed to save to disk')
            }).catch((err) => {
              console.error('[SAATIRIL OP] Error saving photos to disk:', err)
            })
          } else {
            console.log('[SAATIRIL OP] Not running in Electron — photos not saved to disk')
          }
        }

        // Reset immediately — no artificial delay needed
        setSending(false)
        isCapturingRef.current = false
        resetOpState()
      }
    },
    [myChannel, addOpCapturedPhoto, saveProjectsToStorage, resetOpState],
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

  const cancelTimer = useCallback(() => {
    if (timerIntervalRef.current) {
      clearInterval(timerIntervalRef.current)
      timerIntervalRef.current = null
    }
    setTimerActive(false)
    setTimerCountdown(0)
    setFingerTriggeredTimer(false)
  }, [])

  // Cancel timer when capture phase changes away from ready (cleanup only)
  useEffect(() => {
    if (capturePhase !== 'ready-1' && capturePhase !== 'ready-2') {
      if (timerIntervalRef.current) {
        clearInterval(timerIntervalRef.current)
        timerIntervalRef.current = null
      }
      // Use microtask to avoid setState in effect body
      queueMicrotask(() => {
        setTimerActive((prev) => prev ? false : prev)
      })
    }
  }, [capturePhase])

  // Derived: only show countdown when in ready phase
  const effectiveTimerCountdown = (capturePhase === 'ready-1' || capturePhase === 'ready-2') ? timerCountdown : 0

  const startTimer = useCallback(() => {
    if (!isTimerMode(effectiveShutterMode)) return
    if (capturePhase !== 'ready-1' && capturePhase !== 'ready-2') return
    if (timerActive) {
      // Cancel if already running
      cancelTimer()
      return
    }

    const duration = getTimerDuration(effectiveShutterMode)
    let remaining = duration
    setTimerActive(true)
    setTimerCountdown(remaining)

    timerIntervalRef.current = setInterval(() => {
      remaining -= 1
      if (remaining <= 0) {
        // Time's up — capture!
        if (timerIntervalRef.current) {
          clearInterval(timerIntervalRef.current)
          timerIntervalRef.current = null
        }
        setTimerActive(false)
        setTimerCountdown(0)
        setFingerTriggeredTimer(false)
        handleCaptureRef.current()
      } else {
        setTimerCountdown(remaining)
      }
    }, 1000)
  }, [effectiveShutterMode, capturePhase, cancelTimer, timerActive])

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

  // ── Shutter: Finger gesture auto-trigger for Timer modes ────────────────
  // Initialize finger detection when timer mode is active (finger gesture as alternative trigger)
  useEffect(() => {
    if (fingerGestureActive && finger.status === 'unloaded') {
      finger.initialize().then((ok) => {
        if (ok) console.log('[SAATIRIL OP] Finger detection initialized for timer gesture')
      })
    }
  }, [fingerGestureActive, finger.status])

  // Start/stop finger detection based on timer mode
  useEffect(() => {
    if (fingerGestureActive && (finger.status === 'model_ready' || finger.status === 'stopped') && videoRef.current) {
      finger.startDetection(videoRef.current, () => {
        console.log('[SAATIRIL OP] 5 fingers sustained — starting timer')
        setFingerTriggeredTimer(true)
        startTimer()
      })
    } else if (!fingerGestureActive && finger.isRunning) {
      finger.stopDetection()
    }
  }, [fingerGestureActive, finger.status, cameraAvailable, hasActiveTarget])

  // ── Cleanup on unmount ───────────────────────────────────────────────────
  useEffect(() => {
    return () => {
      cancelTimer()
      if (ai.isRunning) ai.stopDetection()
      if (finger.isRunning) finger.stopDetection()
    }
  }, [])

  // ── Shutter mode: determine if capture button should trigger timer or direct capture
  const handleCaptureButtonClick = useCallback(() => {
    if (isTimerMode(effectiveShutterMode)) {
      if (timerActive) {
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
  }, [effectiveShutterMode, startTimer, cancelTimer, handleCapture, timerActive])

  // ── Progress badge text ──────────────────────────────────────────────────
  const progressText = useMemo(() => {
    if (!hasActiveTarget) return 'Menunggu Arahan MC...'
    if (effectiveTimerCountdown > 0) return `Timer: ${effectiveTimerCountdown}s`
    if (fingerGestureActive && finger.isRunning && finger.fingerCount >= 5 && finger.sustainProgress > 0) return `Jari: ${finger.fingerCount}/5`
    if (effectiveShutterMode === 'ai' && ai.isRunning) {
      if (ai.momentState === 'toga_possible' || ai.momentState === 'toga_sustained') return 'AI: Toga terdeteksi...'
      if (ai.momentState === 'ijazah_possible' || ai.momentState === 'ijazah_sustained') return 'AI: Ijazah terdeteksi...'
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
  }, [hasActiveTarget, capturePhase, photoshoot, effectiveTimerCountdown, effectiveShutterMode, fingerGestureActive, finger.isRunning, finger.fingerCount, finger.sustainProgress, ai.isRunning, ai.momentState])

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
      <div className={`flex flex-col gap-1 ${compact ? '' : ''}`}>
        <p className={`font-semibold uppercase tracking-widest ${compact ? 'text-[8px]' : 'text-[9px]'}`} style={{ color: THEME.muted }}>
          Mode Shutter
        </p>
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
        {/* Finger gesture hint for timer modes */}
        {isTimerMode(effectiveShutterMode) && (
          <div className="flex items-center gap-1 mt-0.5">
            <Hand className="size-2.5" style={{ color: finger.isRunning ? THEME.gold : THEME.muted }} />
            <span className="text-[8px]" style={{ color: finger.isRunning ? THEME.gold : THEME.muted }}>
              {finger.isRunning ? 'Gesture aktif — tahan 5 jari' : 'Memuat gesture...'}
            </span>
          </div>
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

      {/* Rule of thirds grid */}
      <div className="absolute inset-0 pointer-events-none" style={{ zIndex: 6 }}>
        <div className="absolute top-0 bottom-0 left-[33.333%] w-px bg-white/[0.12]" />
        <div className="absolute top-0 bottom-0 left-[66.666%] w-px bg-white/[0.12]" />
        <div className="absolute left-0 right-0 top-[33.333%] h-px bg-white/[0.12]" />
        <div className="absolute left-0 right-0 top-[66.666%] h-px bg-white/[0.12]" />
      </div>

      {/* Timer countdown overlay */}
      {effectiveTimerCountdown > 0 && (
        <div className="absolute inset-0 flex items-center justify-center pointer-events-none" style={{ zIndex: 15 }}>
          <div className="flex flex-col items-center gap-2">
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
            {fingerTriggeredTimer && (
              <div className="flex items-center gap-1.5 rounded-full px-3 py-1 animate-pulse" style={{
                backgroundColor: '#22c55e88',
                border: '1px solid #4ade80',
              }}>
                <Hand className="size-3.5" style={{ color: '#4ade80' }} />
                <span className="text-[11px] font-bold" style={{ color: '#ffffff' }}>
                  Timer dimulai! Turunkan tangan
                </span>
              </div>
            )}
          </div>
        </div>
      )}

      {/* Finger gesture indicator for Timer modes */}
      {fingerGestureActive && finger.isRunning && (
        <div className="absolute bottom-3 left-1/2 -translate-x-1/2 flex flex-col items-center gap-1 pointer-events-none" style={{ zIndex: 10 }}>
          {/* Instruction text */}
          <div className="flex items-center gap-1.5 rounded-full px-3 py-1" style={{
            backgroundColor: finger.fingerCount >= 5 ? '#22c55e88' : 'rgba(0,0,0,0.7)',
            border: `1px solid ${finger.fingerCount >= 5 ? '#22c55e' : THEME.border}`,
          }}>
            <Hand className="size-3.5" style={{ color: finger.fingerCount >= 5 ? '#4ade80' : THEME.gold }} />
            <span className="text-[11px] font-semibold" style={{ color: finger.fingerCount >= 5 ? '#4ade80' : THEME.muted }}>
              {finger.fingerCount >= 5 ? 'Tahan jari...' : `Tunjukkan 5 jari (${finger.fingerCount}/5)`}
            </span>
          </div>
          {/* Progress bar */}
          {finger.fingerCount >= 5 && finger.sustainProgress > 0 && (
            <div className="w-32 h-2 rounded-full overflow-hidden" style={{ backgroundColor: 'rgba(0,0,0,0.6)', border: '1px solid #22c55e44' }}>
              <div
                className="h-full rounded-full transition-all duration-75"
                style={{
                  width: `${finger.sustainProgress * 100}%`,
                  backgroundColor: finger.sustainProgress >= 1 ? '#4ade80' : THEME.gold,
                  boxShadow: finger.sustainProgress >= 1 ? '0 0 8px #4ade8066' : `0 0 8px ${THEME.gold}66`,
                }}
              />
            </div>
          )}
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
  const renderCaptureButton = (size: 'normal' | 'large' = 'normal') => {
    const btnClass = size === 'large'
      ? 'w-full h-14 text-base font-bold cursor-pointer rounded-lg transition-all duration-200 active:scale-[0.97]'
      : 'w-full h-12 text-sm font-bold cursor-pointer rounded-lg transition-all duration-200 hover:scale-[1.01] active:scale-[0.99]'

    if (readOnly) {
      return (
        <Button disabled className={`${btnClass} cursor-not-allowed`} style={{ backgroundColor: THEME.panel, color: THEME.muted, border: `2px solid ${THEME.border}`, opacity: 0.6 }}>
          <Monitor className="size-4 mr-2" />MODE MONITOR
        </Button>
      )
    }

    if (capturePhase === 'standby') {
      return (
        <Button disabled className={`${btnClass} cursor-not-allowed`} style={{ backgroundColor: THEME.panel, color: THEME.muted, border: `2px solid ${THEME.border}`, opacity: 0.6 }}>
          <Aperture className="size-4 mr-2" />STANDBY
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
          <X className="size-4 mr-2" />BATAL ({effectiveTimerCountdown}s)
        </Button>
      )
    }

    if (capturePhase === 'ready-1') {
      const isAutoMode = effectiveShutterMode === 'ai'
      const isTimer = isTimerMode(effectiveShutterMode)

      if (isAutoMode) {
        // Auto-capture modes: show status button
        const isDetecting = effectiveShutterMode === 'ai' && ai.isRunning
        return (
          <Button disabled className={`${btnClass} cursor-default`} style={{
            backgroundColor: isDetecting ? '#22c55e33' : THEME.panel,
            color: isDetecting ? '#4ade80' : THEME.muted,
            border: `2px solid ${isDetecting ? '#22c55e' : THEME.border}`,
          }}>
            {isDetecting ? <Loader2 className="size-4 mr-2 animate-spin" /> : <Aperture className="size-4 mr-2" />}
            {effectiveShutterMode === 'ai' ? (isDetecting ? 'AI Mendeteksi Pose...' : 'AI Loading...') : ''}
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
            <Timer className="size-4 mr-2" />
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
          <Camera className="size-4 mr-2" />{photoshoot ? 'FOTO' : 'FOTO 1 — TOGA'}
        </Button>
      )
    }

    if (capturePhase === 'ready-2') {
      const isAutoMode = effectiveShutterMode === 'ai'
      const isTimer = isTimerMode(effectiveShutterMode)

      if (isAutoMode) {
        const isDetecting = effectiveShutterMode === 'ai' && ai.isRunning
        return (
          <Button disabled className={`${btnClass} cursor-default`} style={{
            backgroundColor: isDetecting ? '#22c55e33' : THEME.panel,
            color: isDetecting ? '#4ade80' : THEME.muted,
            border: `2px solid ${isDetecting ? '#22c55e' : THEME.border}`,
          }}>
            {isDetecting ? <Loader2 className="size-4 mr-2 animate-spin" /> : <Aperture className="size-4 mr-2" />}
            {effectiveShutterMode === 'ai' ? 'AI Mendeteksi Ijazah...' : ''}
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
            <Timer className="size-4 mr-2" />
            FOTO 2 — IJAZAH ({getTimerDuration(effectiveShutterMode)}s)
          </Button>
        )
      }

      return (
        <Button onClick={handleCaptureButtonClick} className={btnClass} style={{ backgroundColor: '#22c55e', color: '#ffffff', border: '2px solid #22c55e', boxShadow: '0 0 30px #22c55e44, 0 0 60px #22c55e22' }}>
          <Camera className="size-4 mr-2" />FOTO 2 — IJAZAH
        </Button>
      )
    }

    if (capturePhase === 'sending') {
      return (
        <Button disabled className={`${btnClass} cursor-not-allowed`} style={{ backgroundColor: THEME.panel, color: THEME.muted, border: `2px solid ${THEME.border}` }}>
          <Loader2 className="size-4 mr-2 animate-spin" />MENGIRIM...
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
        <h3 className="text-xs font-semibold" style={{ color: '#ffffff' }}>
          Antrean: <span style={{ color: THEME.gold }} className="font-bold">{remainingCount}</span>
        </h3>
        <span className="text-[10px]" style={{ color: THEME.muted }}>Ch.{myChannel}</span>
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

  // ── MOBILE LAYOUT ────────────────────────────────────────────────────────
  if (isMobile) {
    return (
      <div className="flex flex-col h-full touch-no-select" style={{ backgroundColor: THEME.bg }}>

        {/* ── Camera Zone ────────────────────────────────────────────────── */}
        <div
          ref={cameraZoneRef}
          className="flex-1 flex items-center justify-center min-h-0 p-1"
        >
          {renderCameraView()}
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

            <button
              onClick={() => setShowQueueOnMobile(!showQueueOnMobile)}
              className="shrink-0 flex items-center justify-center w-9 h-9 rounded-lg border cursor-pointer active:scale-95 transition-transform"
              style={{
                backgroundColor: showQueueOnMobile ? THEME.gold : THEME.card,
                borderColor: showQueueOnMobile ? THEME.gold : THEME.border,
              }}
              title="Lihat antrean"
            >
              {showQueueOnMobile ? (
                <X className="size-4" style={{ color: showQueueOnMobile ? THEME.bg : THEME.muted }} />
              ) : (
                <List className="size-4" style={{ color: THEME.muted }} />
              )}
            </button>
          </div>

          {/* Operator search (photoshoot only) */}
          {showQueueOnMobile && renderOpSearch(true)}

          {/* Queue list (expandable) */}
          {showQueueOnMobile && (
            <div
              className="border-t"
              style={{ borderColor: THEME.border, maxHeight: '35vh' }}
            >
              {renderQueueList(true)}
            </div>
          )}

          {/* Camera selector (compact) + Shutter mode selector */}
          <div className="px-3 py-1 flex items-center gap-2">
            {videoDevices.length > 1 && (
              <Select value={selectedDeviceId} onValueChange={setSelectedDeviceId}>
                <SelectTrigger
                  className="text-[10px] h-7 w-32"
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
            )}
            <div className="flex-1 min-w-0">
              {renderShutterModeSelector(true)}
            </div>
          </div>

          {/* Capture button */}
          <div className="px-3 pb-2 pt-1">
            {renderCaptureButton('large')}
          </div>
        </div>
      </div>
    )
  }

  // ── DESKTOP LAYOUT ──────────────────────────────────────────────────────
  return (
    <div className="flex h-full overflow-hidden" style={{ backgroundColor: THEME.bg }}>

      {/* CAMERA ZONE */}
      <div
        ref={cameraZoneRef}
        className="flex-1 flex items-center justify-center h-full min-w-0 p-2"
      >
        {renderCameraView()}
      </div>

      {/* SIDEBAR */}
      <div className="flex flex-col gap-2 p-2 w-[300px] shrink-0 min-h-0">

        {/* Target Info Panel */}
        <Card
          className="shrink-0 border-2 rounded-lg transition-all duration-300"
          style={{
            backgroundColor: THEME.card,
            borderColor: hasActiveTarget ? THEME.gold : THEME.border,
            opacity: hasActiveTarget ? 1 : 0.5,
            boxShadow: hasActiveTarget ? `0 0 20px ${THEME.gold}22` : 'none',
          }}
        >
          <CardContent className="p-2.5">
            <div className="flex items-center gap-2.5">
              <div
                className="shrink-0 flex items-center justify-center w-9 h-9 rounded-full border-2"
                style={{ backgroundColor: THEME.panel, borderColor: hasActiveTarget ? THEME.gold : THEME.border }}
              >
                <User className="size-4" style={{ color: hasActiveTarget ? THEME.gold : THEME.border }} />
              </div>

              <div className="flex-1 min-w-0">
                {hasActiveTarget ? (
                  <>
                    <p className="text-sm font-bold leading-tight truncate" style={{ color: '#ffffff' }}>{opCurrentTarget.nama}</p>
                    <p className="text-[11px] font-mono" style={{ color: THEME.muted }}>{opCurrentTarget.nim}</p>
                  </>
                ) : (
                  <p className="text-xs italic" style={{ color: THEME.muted }}>Menunggu panggilan MC...</p>
                )}
              </div>

              <Badge
                className={`text-[10px] px-2 py-0.5 shrink-0 ${hasActiveTarget && !sending ? 'animate-pulse' : ''}`}
                style={{
                  backgroundColor: capturePhase === 'ready-1' ? `${THEME.gold}33` : capturePhase === 'ready-2' ? '#22c55e33' : capturePhase === 'sending' ? `${THEME.border}66` : `${THEME.border}44`,
                  color: capturePhase === 'ready-1' ? THEME.gold : capturePhase === 'ready-2' ? '#4ade80' : THEME.muted,
                  border: `1px solid ${capturePhase === 'ready-1' ? `${THEME.gold}66` : capturePhase === 'ready-2' ? '#22c55e66' : THEME.border}`,
                }}
              >
                {capturePhase === 'sending' && <Loader2 className="size-3 mr-0.5 animate-spin" />}
                {capturePhase === 'ready-1' && <Camera className="size-3 mr-0.5" />}
                {capturePhase === 'ready-2' && <CheckCircle2 className="size-3 mr-0.5" />}
                {capturePhase === 'standby' && <Clock className="size-3 mr-0.5" />}
                {progressText}
              </Badge>
            </div>

            <div className="mt-2 flex items-center gap-2">
              <Select value={selectedDeviceId} onValueChange={setSelectedDeviceId}>
                <SelectTrigger className="flex-1 text-[11px] h-7" style={{ backgroundColor: THEME.panel, borderColor: THEME.border, color: THEME.muted }}>
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
          </CardContent>
        </Card>

        {/* Shutter Mode Selector */}
        {!readOnly && (
          <Card className="shrink-0 border rounded-lg" style={{ backgroundColor: THEME.card, borderColor: THEME.border }}>
            <CardContent className="p-2.5">
              {renderShutterModeSelector(false)}
            </CardContent>
          </Card>
        )}

        {/* Operator search (photoshoot only) */}
        {renderOpSearch(false)}

        {/* Queue List */}
        {renderQueueList(false)}

        {/* Capture Button */}
        <div className="shrink-0">
          {renderCaptureButton('normal')}
        </div>
      </div>
    </div>
  )
}

export default OperatorPanel
