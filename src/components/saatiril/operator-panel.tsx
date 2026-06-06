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
} from 'lucide-react'
import {
  useSaatirilStore,
  type Student,
  type StudentStatus,
  type PhotoHistoryItem,
  mergeDatabases,
  stripFrameForSync,
  preserveFrameOnSync,
} from '@/store/use-saatiril-store'
import { emitLocal, onLocal, offLocal } from '@/lib/socket'
import { useIsMobile } from '@/hooks/use-mobile'
import { NetworkQualityBadge } from '@/components/saatiril/network-quality-badge'
import { useAIDetection, type AIMomentEvent } from '@/hooks/use-ai-detection'

// ─── Theme tokens ───────────────────────────────────────────────────────────
const THEME = {
  bg: '#1a0b2e',
  panel: '#2a164a',
  card: '#3b2263',
  border: '#533485',
  gold: '#d4af37',
  muted: '#c4b5fd',
} as const

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
      mode: 'single' | 'dual'
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

  // ── Local state ──────────────────────────────────────────────────────────
  const [videoDevices, setVideoDevices] = useState<VideoDeviceInfo[]>([])
  const [selectedDeviceId, setSelectedDeviceId] = useState<string>('')
  const [cameraAvailable, setCameraAvailable] = useState(false)
  const [flashVisible, setFlashVisible] = useState(false)
  const [sending, setSending] = useState(false)
  const [cameraDims, setCameraDims] = useState({ width: 0, height: 0 })
  const [showQueueOnMobile, setShowQueueOnMobile] = useState(false)
  const isCapturingRef = useRef(false)

  // ── AI auto-capture ──────────────────────────────────────────────────────
  const ai = useAIDetection()
  const [aiAutoCapture, setAiAutoCapture] = useState(false)

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
  const frameData = config?.frame ?? null

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
  const channelStudents = useMemo<Student[]>(() => {
    if (!currentProject) return []
    return currentProject.database.filter((s) => s.assignedChannel === myChannel)
  }, [currentProject, myChannel])

  const currentlyActive = useMemo<Student | null>(() => {
    const targetStatus: StudentStatus = `active_${myChannel}`
    return channelStudents.find((s) => s.status === targetStatus) ?? null
  }, [channelStudents, myChannel])

  const nextPending = useMemo<Student | null>(() => {
    return channelStudents.find((s) => s.status === 'pending') ?? null
  }, [channelStudents])

  const remainingCount = useMemo<number>(() => {
    return channelStudents.filter((s) => s.status === 'pending').length
  }, [channelStudents])

  const hasActiveTarget = opCurrentTarget !== null

  const capturePhase = useMemo<CapturePhase>(() => {
    if (sending) return 'sending'
    if (!hasActiveTarget) return 'standby'
    if (opCapturedPhotos.length === 0) return 'ready-1'
    if (opCapturedPhotos.length === 1) return 'ready-2'
    return 'standby'
  }, [sending, hasActiveTarget, opCapturedPhotos.length])

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
  // Mobile: default to rear camera (facingMode: 'environment')
  // Desktop: default to first available camera
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
        // Specific device selected
        constraints = {
          video: { deviceId: { exact: deviceId }, width: { ideal: 1920 }, height: { ideal: 1080 } },
          audio: false,
        }
      } else if (isMobile) {
        // Mobile: prefer rear camera
        constraints = {
          video: { facingMode: 'environment', width: { ideal: 1920 }, height: { ideal: 1080 } },
          audio: false,
        }
      } else {
        // Desktop: any camera
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
        // Fallback: if facingMode fails, try without it
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

  // ── State recovery ───────────────────────────────────────────────────────
  useEffect(() => {
    if (!currentProject) return
    const activeStudent = currentProject.database.find(
      (s) => s.assignedChannel === myChannel && isActiveStatus(s.status),
    )
    if (activeStudent) {
      setOpCurrentTarget(activeStudent)
    } else if (opCurrentTarget && !isActiveStatus(opCurrentTarget.status)) {
      setOpCurrentTarget(null)
    }
  }, [currentProject, myChannel, setOpCurrentTarget])

  // ── Socket: MC_CALL ─────────────────────────────────────────────────────
  useEffect(() => {
    const handleMcCall = (data: McCallData) => {
      if (data.channel !== myChannelRef.current) return
      setOpCurrentTarget(data.student)
    }
    onLocal('MC_CALL', handleMcCall)
    return () => { offLocal('MC_CALL', handleMcCall) }
  }, [setOpCurrentTarget])

  // ── Socket: SYNC_DB ─────────────────────────────────────────────────────
  useEffect(() => {
    const handleSyncDb = (data: SyncDbData) => {
      const proj = currentProjectRef.current
      if (!proj) return
      const mergedDb = mergeDatabases(proj.database, data.project.database)
      const mergedConfig = preserveFrameOnSync(data.project.config, proj.config)
      updateCurrentProject({ ...proj, database: mergedDb, photoHistory: data.project.photoHistory?.length ? data.project.photoHistory : proj.photoHistory, config: mergedConfig })
      const ch = myChannelRef.current
      const activeStudent = data.project.database.find(
        (s: Student) => s.assignedChannel === ch && isActiveStatus(s.status),
      )
      if (activeStudent) setOpCurrentTarget(activeStudent)
    }
    onLocal('SYNC_DB', handleSyncDb)
    return () => { offLocal('SYNC_DB', handleSyncDb) }
  }, [setOpCurrentTarget, updateCurrentProject])

  // ── Finalize capture ────────────────────────────────────────────────────
  const finalizeCapture = useCallback(
    (canvas: HTMLCanvasElement) => {
      setFlashVisible(true)
      setTimeout(() => setFlashVisible(false), 300)

      const dataUrl = canvas.toDataURL('image/jpeg', 0.95)
      addOpCapturedPhoto(dataUrl)

      const currentPhotos = useSaatirilStore.getState().opCapturedPhotos
      const currentTarget = useSaatirilStore.getState().opCurrentTarget
      const photoCount = currentPhotos.length

      console.log('[SAATIRIL OP] finalizeCapture: photoCount =', photoCount)

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
        updateStudentStatus(student.id, 'done')
        saveProjectsToStorageNow()

        console.log('[SAATIRIL OP] Emitting PHOTOS_SAVED for student:', student.nama, 'channel:', myChannel)

        emitLocal('PHOTOS_SAVED', {
          student: { ...student, status: 'done' },
          photos: allPhotos,
          channel: myChannel,
        })
        emitLocal('OP_PROGRESS', { channel: myChannel, status: 'Selesai — Menunggu target...' })

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
              if (path1 && path2) {
                console.log(`[SAATIRIL OP] Photos saved to disk:\n  → ${path1}\n  → ${path2}`)
              } else {
                console.warn('[SAATIRIL OP] Some photos failed to save to disk')
              }
            }).catch((err) => {
              console.error('[SAATIRIL OP] Error saving photos to disk:', err)
            })
          } else {
            console.log('[SAATIRIL OP] Not running in Electron — photos not saved to disk')
          }
        }
        setTimeout(() => {
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
            const updatedProject = { ...store.currentProject, photoHistory: newHistory }
            store.updateCurrentProject(updatedProject)
            emitLocal('SYNC_DB', { project: stripFrameForSync(updatedProject) })
          }
          setSending(false)
          isCapturingRef.current = false
          setTimeout(() => { resetOpState() }, 300)
        }, 100)
      }
    },
    [myChannel, addOpCapturedPhoto, updateStudentStatus, saveProjectsToStorageNow, resetOpState],
  )

  // ── Photo capture logic ──────────────────────────────────────────────────
  const handleCapture = useCallback(() => {
    if (!opCurrentTarget) return
    if (capturePhase !== 'ready-1' && capturePhase !== 'ready-2') return
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
  }, [opCurrentTarget, capturePhase, aspectRatio, cssFilter, frameData, finalizeCapture])

  // ── AI: stable refs for callback ─────────────────────────────────────────
  const capturePhaseRef = useRef(capturePhase)
  useEffect(() => { capturePhaseRef.current = capturePhase }, [capturePhase])
  const handleCaptureRef = useRef(handleCapture)
  useEffect(() => { handleCaptureRef.current = handleCapture }, [handleCapture])

  // ── AI: Initialize when camera is ready ────────────────────────────────
  useEffect(() => {
    if (cameraAvailable && hasActiveTarget && !ai.scriptsLoaded && ai.status === 'unloaded') {
      ai.initialize().then((ok) => {
        if (ok) console.log('[SAATIRIL OP] AI initialized')
      })
    }
  }, [cameraAvailable, hasActiveTarget])

  // ── AI: Start/stop detection based on auto-capture toggle ────────────
  useEffect(() => {
    if (aiAutoCapture && ai.modelLoaded && cameraAvailable && videoRef.current && hasActiveTarget) {
      ai.startDetection(videoRef.current, (event: AIMomentEvent) => {
        console.log('[SAATIRIL OP] AI moment:', event.type, 'phase:', capturePhaseRef.current)
        const phase = capturePhaseRef.current
        if (event.type === 'toga' && phase === 'ready-1') {
          handleCaptureRef.current()
        } else if (event.type === 'ijazah' && phase === 'ready-2') {
          handleCaptureRef.current()
        }
      })
    } else if (!aiAutoCapture && ai.isRunning) {
      ai.stopDetection()
    }
  }, [aiAutoCapture, ai.modelLoaded, cameraAvailable, hasActiveTarget, capturePhase])

  // ── Progress badge text ──────────────────────────────────────────────────
  const progressText = useMemo(() => {
    if (!hasActiveTarget) return 'Menunggu Arahan MC...'
    if (capturePhase === 'ready-1') return 'Siap Foto 1'
    if (capturePhase === 'ready-2') return 'Pose 1 OK - Siap Foto 2'
    if (capturePhase === 'sending') return 'Mengirim...'
    return 'Menunggu Arahan MC...'
  }, [hasActiveTarget, capturePhase])

  // ── Render helpers ───────────────────────────────────────────────────────
  const getRowStyle = (student: Student): React.CSSProperties => {
    const isActive = student.status === `active_${myChannel}`
    const isNext = student.id === nextPending?.id && student.status === 'pending'
    const isDone = student.status === 'done'
    if (isActive) return { backgroundColor: `${THEME.gold}22`, borderLeft: `4px solid ${THEME.gold}`, boxShadow: `0 0 12px ${THEME.gold}44` }
    if (isNext) return { backgroundColor: THEME.panel, borderLeft: `4px solid ${THEME.gold}` }
    if (isDone) return { backgroundColor: '#22c55e0d', opacity: 0.55, borderLeft: `4px solid #22c55e66` }
    return { backgroundColor: THEME.panel, borderLeft: `4px solid ${THEME.border}` }
  }

  const renderStatusBadge = (status: StudentStatus) => {
    if (status === 'done') return <Badge className="text-[10px] px-1.5 py-0" style={{ backgroundColor: '#22c55e33', color: '#4ade80', border: '1px solid #22c55e55' }}><CheckCircle2 className="size-3 mr-0.5" />Selesai</Badge>
    if (isActiveStatus(status)) return <Badge className="text-[10px] px-1.5 py-0 animate-pulse" style={{ backgroundColor: `${THEME.gold}33`, color: THEME.gold, border: `1px solid ${THEME.gold}66` }}><Loader2 className="size-3 mr-0.5 animate-spin" />{statusLabel(status)}</Badge>
    return <Badge className="text-[10px] px-1.5 py-0" style={{ backgroundColor: `${THEME.border}44`, color: THEME.muted, border: `1px solid ${THEME.border}` }}><Clock className="size-3 mr-0.5" />Menunggu</Badge>
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

    if (capturePhase === 'ready-1') {
      return (
        <Button onClick={handleCapture} className={btnClass} style={{ backgroundColor: THEME.gold, color: THEME.bg, border: `2px solid ${THEME.gold}`, boxShadow: `0 0 30px ${THEME.gold}44, 0 0 60px ${THEME.gold}22` }}>
          <Camera className="size-4 mr-2" />FOTO 1 — TOGA
        </Button>
      )
    }

    if (capturePhase === 'ready-2') {
      return (
        <Button onClick={handleCapture} className={btnClass} style={{ backgroundColor: '#22c55e', color: '#ffffff', border: '2px solid #22c55e', boxShadow: '0 0 30px #22c55e44, 0 0 60px #22c55e22' }}>
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

      {/* Column headers — hide on very compact view */}
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
                // Mobile compact: only show name + status
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

        {/* ── Camera Zone (takes most of the screen) ─────────────────────── */}
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
            {/* Avatar */}
            <div
              className="shrink-0 flex items-center justify-center w-8 h-8 rounded-full border-2"
              style={{
                backgroundColor: THEME.bg,
                borderColor: hasActiveTarget ? THEME.gold : THEME.border,
              }}
            >
              <User className="size-3.5" style={{ color: hasActiveTarget ? THEME.gold : THEME.border }} />
            </div>

            {/* Name & NIM */}
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

            {/* Queue toggle button */}
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

          {/* Queue list (expandable) */}
          {showQueueOnMobile && (
            <div
              className="border-t"
              style={{ borderColor: THEME.border, maxHeight: '35vh' }}
            >
              {renderQueueList(true)}
            </div>
          )}

          {/* Camera selector (compact) */}
          {videoDevices.length > 1 && (
            <div className="px-3 py-1">
              <Select value={selectedDeviceId} onValueChange={setSelectedDeviceId}>
                <SelectTrigger
                  className="flex-1 text-[10px] h-7"
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

          {/* Capture button */}
          <div className="px-3 pb-2 pt-1">
            {renderCaptureButton('large')}
            {/* AI Auto-Capture toggle — mobile */}
            {!readOnly && hasActiveTarget && (
              <Button
                onClick={() => setAiAutoCapture(!aiAutoCapture)}
                className={`w-full h-10 mt-1.5 text-xs font-bold rounded-lg transition-all duration-200 ${
                  aiAutoCapture ? 'animate-pulse' : ''
                }`}
                style={{
                  backgroundColor: aiAutoCapture ? `${THEME.gold}33` : THEME.panel,
                  color: aiAutoCapture ? THEME.gold : THEME.muted,
                  border: `1.5px solid ${aiAutoCapture ? THEME.gold : THEME.border}`,
                }}
              >
                {ai.status === 'loading' ? (
                  <Loader2 className="size-3.5 mr-1.5 animate-spin" />
                ) : aiAutoCapture ? (
                  <Sparkles className="size-3.5 mr-1.5" />
                ) : (
                  <Brain className="size-3.5 mr-1.5" />
                )}
                {ai.status === 'loading' ? 'Memuat AI...' : aiAutoCapture ? 'AI Auto-Capture ON' : 'AI Auto-Capture OFF'}
              </Button>
            )}
          </div>
        </div>
      </div>
    )
  }

  // ── DESKTOP LAYOUT (original with minor tweaks) ──────────────────────────
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

        {/* Queue List */}
        {renderQueueList(false)}

        {/* Capture Button */}
        <div className="shrink-0 space-y-1.5">
          {renderCaptureButton('normal')}
          {/* AI Auto-Capture toggle — desktop */}
          {!readOnly && hasActiveTarget && (
            <Button
              onClick={() => setAiAutoCapture(!aiAutoCapture)}
              className={`w-full h-9 text-xs font-bold rounded-lg transition-all duration-200 ${
                aiAutoCapture ? 'animate-pulse' : ''
              }`}
              style={{
                backgroundColor: aiAutoCapture ? `${THEME.gold}33` : THEME.panel,
                color: aiAutoCapture ? THEME.gold : THEME.muted,
                border: `1.5px solid ${aiAutoCapture ? THEME.gold : THEME.border}`,
              }}
            >
              {ai.status === 'loading' ? (
                <Loader2 className="size-3.5 mr-1.5 animate-spin" />
              ) : aiAutoCapture ? (
                <Sparkles className="size-3.5 mr-1.5" />
              ) : (
                <Brain className="size-3.5 mr-1.5" />
              )}
              {ai.status === 'loading' ? 'Memuat AI...' : aiAutoCapture ? 'AI Auto-Capture ON' : 'AI Auto-Capture OFF'}
            </Button>
          )}
        </div>
      </div>
    </div>
  )
}

export default OperatorPanel
