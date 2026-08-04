'use client'

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  Users,
  CheckCircle2,
  Copy,
  Camera,
  Wifi,
  Image as ImageIcon,
  Clock,
  Cable,
  Zap,
  Download,
  FileSpreadsheet,
  XCircle,
  QrCode,
  X,
  Package,
  Monitor,
} from 'lucide-react'
import { QRCodeSVG } from 'qrcode.react'
import * as XLSX from 'xlsx'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Separator } from '@/components/ui/separator'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
} from '@/components/ui/dialog'
import { useSaatirilStore, type Student, type StudentStatus, type PhotoHistoryItem, type CameraMode, mergeDatabases, preserveFrameOnSync, preservePhotoHistoryOnSync, mergeCaptureVersions, isPhotoshootMode, isDualPhotoshootMode } from '@/store/use-saatiril-store'
import { onLocal, offLocal, getConnectionHealth, onLatencyUpdate, type ConnectionHealth } from '@/lib/socket'
import { useToast } from '@/hooks/use-toast'

// ── Theme constants ──────────────────────────────────────────────
const BG = 'bg-[#1a0b2e]'
const PANEL = 'bg-[#2a164a]'
const BORDER = 'border-[#533485]'
const GOLD = '#d4af37'
const MUTED = 'text-[#c4b5fd]'
const CYAN = '#06b6d4'

// ── Helper: sanitize nama for filenames ──────────────────────────
// DEFENSIVE: nama/nim can be undefined if a photoHistory entry got corrupted
// during reset+retake cycles. Never let .trim() crash the whole app.
function sanitizeNama(nama: string | undefined | null): string {
  return (nama ?? '')
    .trim()
    .replace(/\s+/g, '_')
    .replace(/[^a-zA-Z0-9_]/g, '')
}

function sanitizeNim(nim: string | undefined | null): string {
  return (nim ?? '').toString().trim().replace(/[^a-zA-Z0-9_-]/g, '')
}

/**
 * Build a versioned filename for standard mode (Toga + Ijazah).
 * version: 1 = first capture, 2+ = retake after MC reset.
 */
function buildFilename(nim: string | undefined, nama: string | undefined, suffix: number, type: string, version: number = 1): string {
  const base = `${sanitizeNim(nim)}_${sanitizeNama(nama)}_${suffix}_${type}`
  return version > 1 ? `${base}_v${version}.jpg` : `${base}.jpg`
}

/**
 * Build a versioned filename for photoshoot mode.
 * version: 1 = first capture, 2+ = retake after MC reset.
 */
function buildPhotoshootFilename(nim: string | undefined, nama: string | undefined, channel: number, version: number = 1): string {
  const base = `${sanitizeNim(nim)}_${sanitizeNama(nama)}`
  const withCh = channel > 1 ? `${base}_Ch${channel}` : base
  return version > 1 ? `${withCh}_v${version}.jpg` : `${withCh}.jpg`
}

// ── Helper: human-readable status label for display/export ───────
function statusToLabel(status: StudentStatus): string {
  if (status === 'done') return 'Selesai'
  if (status === 'sent') return 'Dikirim'
  if (status === 'pending') return 'Belum'
  // active_N
  const ch = status.split('_')[1]
  return ch ? `Aktif Ch.${ch}` : 'Aktif'
}

// ── Socket event data shapes ─────────────────────────────────────
interface PhotosSavedData {
  student: Student
  photos: string[]
  channel: number
  /** Version number (1 = first capture, 2+ = retake). Sent by operator. */
  version?: number
  /** Filename chosen by the operator (includes version suffix). */
  filename?: string
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

// ── Component ────────────────────────────────────────────────────
export default function AdminDashboard() {
  const { toast } = useToast()

  // Store
  const currentProject = useSaatirilStore((s) => s.currentProject)
  const updateCurrentProject = useSaatirilStore((s) => s.updateCurrentProject)
  const lastSavedAt = useSaatirilStore((s) => s.lastSavedAt)

  // ── Computed values ──────────────────────────────────────────────
  const mode = currentProject?.config.mode ?? 'single'
  const database = currentProject?.database ?? []
  const photoHistory = currentProject?.photoHistory ?? []

  const totalPeserta = database.length
  const doneCount = useMemo(
    () => database.filter((s) => s.status === 'done').length,
    [database],
  )
  // "Belum" = belum selesai = total - selesai (includes pending, sent, active)
  const belumCount = useMemo(
    () => database.filter((s) => s.status !== 'done').length,
    [database],
  )
  const sentCount = useMemo(
    () => database.filter((s) => s.status === 'sent').length,
    [database],
  )

  // ── Refs for stable event handlers (avoid re-registering on every project change) ──
  const currentProjectRef = useRef(currentProject)
  useEffect(() => { currentProjectRef.current = currentProject }, [currentProject])

  // ── Socket listeners ─────────────────────────────────────────────
  useEffect(() => {
    const handlePhotosSaved = (data: PhotosSavedData) => {
      const proj = currentProjectRef.current
      if (!proj) return

      // ── Determine version + filenames ──────────────────────────────────
      // The operator sends `version` (1 = first capture, 2+ = retake) and the
      // chosen `filename` in the PHOTOS_SAVED event. We prefer the operator's
      // filename to guarantee the admin saves under the EXACT same name (so
      // there is no mismatch between operator's disk and admin's disk).
      const photoshootMode = isPhotoshootMode(proj.config.mode)
      const version = data.version ?? 1

      // ── Save photos to disk ─────────────────────────────────────────────
      // Electron only: photos write to config.targetFolder (folder chosen at
      // PROJECT CREATION) via IPC. In browser mode photos stay in memory/gallery
      // only. The folder is NOT picked by the operator — it's set at project
      // creation. Run via Electron desktop for permanent disk saves.
      const targetFolder = proj.config?.targetFolder
      const hasEnoughPhotos = data.photos?.length >= (photoshootMode ? 1 : 2)

      if (hasEnoughPhotos) {
        const api = window.saatirilAPI
        if (api?.savePhoto && targetFolder) {
          if (photoshootMode) {
            const filename = data.filename ?? buildPhotoshootFilename(data.student.nim, data.student.nama, data.channel, version)
            api.savePhoto({ base64Data: data.photos[0], filename, targetFolder }).then((path: string | null) => {
              if (path) {
                console.log(`[SAATIRIL ADMIN] Photo saved to disk (v${version}): → ${path}`)
              } else {
                console.warn('[SAATIRIL ADMIN] Photo failed to save to disk')
              }
            }).catch((err: Error) => {
              console.error('[SAATIRIL ADMIN] Error saving photo to disk:', err)
            })
          } else {
            const togaFilename = buildFilename(data.student.nim, data.student.nama, 1, 'Toga', version)
            const ijazahFilename = buildFilename(data.student.nim, data.student.nama, 2, 'Ijazah', version)
            Promise.all([
              api.savePhoto({ base64Data: data.photos[0], filename: togaFilename, targetFolder }),
              api.savePhoto({ base64Data: data.photos[1], filename: ijazahFilename, targetFolder }),
            ]).then(([path1, path2]) => {
              if (path1 && path2) {
                console.log(`[SAATIRIL ADMIN] Photos saved to disk (v${version}):\n  → ${path1}\n  → ${path2}`)
              } else {
                console.warn('[SAATIRIL ADMIN] Some photos failed to save to disk')
              }
            }).catch((err) => {
              console.error('[SAATIRIL ADMIN] Error saving photos to disk:', err)
            })
          }
        } else if (!api?.savePhoto) {
          console.warn('[SAATIRIL ADMIN] savePhoto API not available — not running in Electron? Photos stay in memory only.')
        } else if (!targetFolder) {
          console.warn('[SAATIRIL ADMIN] No targetFolder in project config — photos not saved to disk')
        }
      }

      // Build the history item from the data
      const historyItem: PhotoHistoryItem = {
        student: data.student,
        photos: data.photos,
        channel: data.channel,
      }

      // Check if this student already has a history entry
      const existing = proj.photoHistory.findIndex(
        (h) =>
          h.student.id === data.student.id &&
          h.channel === data.channel,
      )
      let newHistory: PhotoHistoryItem[]
      if (existing !== -1) {
        newHistory = [...proj.photoHistory]
        newHistory[existing] = historyItem
      } else {
        newHistory = [...proj.photoHistory, historyItem]
      }

      // Check completion: in dual-photoshoot mode, EITHER camera is sufficient
      // (the participant is considered done after 1 of the 2 cameras takes a photo).
      // In single-photoshoot mode, the single channel is sufficient.
      // In non-photoshoot modes, mark done immediately.
      let allChannelsDone = true
      if (isDualPhotoshootMode(proj.config.mode)) {
        const ch1Done = newHistory.some((h) => h.student.id === data.student.id && h.channel === 1)
        const ch2Done = newHistory.some((h) => h.student.id === data.student.id && h.channel === 2)
        allChannelsDone = ch1Done || ch2Done
      } else if (photoshootMode) {
        // Single photoshoot: one channel is enough
        allChannelsDone = true
      } else {
        // Non-photoshoot modes: mark done immediately
        allChannelsDone = true
      }

      // Update student status: 'done' if all channels complete, keep current status otherwise
      const updatedDatabase = proj.database.map((s) =>
        s.id === data.student.id && allChannelsDone
          ? { ...s, status: 'done' as const }
          : s.id === data.student.id && !allChannelsDone
            ? { ...s, status: s.status } // keep current status (e.g., 'sent')
            : s
      )

      updateCurrentProject({
        ...proj,
        database: updatedDatabase,
        photoHistory: newHistory,
      })
    }

    const handleSyncDb = (data: SyncDbData) => {
      // Read latest state synchronously to avoid stale-ref race
      const proj = useSaatirilStore.getState().currentProject
      if (!proj) return
      // Preserve frame data: if incoming has '__FRAME_SAVED__', keep existing frame
      const mergedConfig = preserveFrameOnSync(data.project.config, proj.config)
      // Merge database with incoming (prevents channel data overwrite in dual mode)
      const mergedDb = mergeDatabases(proj.database, data.project.database)
      // Preserve photos: incoming SYNC_DB may have stripped photos
      const mergedPhotoHistory = preservePhotoHistoryOnSync(
        data.project.photoHistory ?? [],
        proj.photoHistory,
      )
      // Merge captureVersions (MAX per key) so retake version numbers sync
      // across admin/operator/MC without regressing.
      const mergedVersions = mergeCaptureVersions(
        proj.captureVersions,
        (data.project as any).captureVersions,
      )
      updateCurrentProject({
        ...proj,
        database: mergedDb,
        photoHistory: mergedPhotoHistory,
        config: mergedConfig,
        captureVersions: mergedVersions,
      })
    }

    // STUDENT_RESET: explicit reset/retake signal from MC.
    // Bypasses the SYNC_DB merge (which blocks status regression pending<sent)
    // so admin clears the student's photoHistory entries + resets status here.
    const handleStudentReset = (data: { studentId: string; channel: number }) => {
      console.log('[SAATIRIL ADMIN] STUDENT_RESET received — clearing for retake:', data.studentId, 'Ch.', data.channel)
      // Read latest state synchronously to avoid stale-ref race with SYNC_DB
      const proj = useSaatirilStore.getState().currentProject
      if (!proj) return
      const cleanedHistory = proj.photoHistory.filter((h) => h.student.id !== data.studentId)
      const cleanedDb = proj.database.map((s) =>
        s.id === data.studentId ? { ...s, status: 'pending' as StudentStatus } : s,
      )
      updateCurrentProject({ ...proj, database: cleanedDb, photoHistory: cleanedHistory })
    }

    onLocal('PHOTOS_SAVED', handlePhotosSaved)
    onLocal('SYNC_DB', handleSyncDb)
    onLocal('STUDENT_RESET', handleStudentReset)

    return () => {
      offLocal('PHOTOS_SAVED', handlePhotosSaved)
      offLocal('SYNC_DB', handleSyncDb)
      offLocal('STUDENT_RESET', handleStudentReset)
    }
  }, [updateCurrentProject])

  // ── Network quality state ──────────────────────────────────────
  const [networkHealth, setNetworkHealth] = useState<ConnectionHealth>(() => getConnectionHealth())

  useEffect(() => {
    const unsub = onLatencyUpdate((h) => setNetworkHealth({ ...h }))
    return unsub
  }, [])

  // ── LAN info state ────────────────────────────────────────────────
  const [lanInfo, setLanInfo] = useState<{ httpPort: number; socketPort: number; ips: { name: string; address: string }[] } | null>(null)

  // ── QR Code dialog state ───────────────────────────────────────────
  const [qrDialogOpen, setQrDialogOpen] = useState(false)
  const [qrLink, setQrLink] = useState('')
  const [qrLabel, setQrLabel] = useState('')

  // ── Release download state (from GitHub Releases) ──────────────────
  const GITHUB_REPO = 'synclicen/Saatiril-Fullset'
  const [apkInfo, setApkInfo] = useState<{ available: boolean; sizeMB?: string; assetName?: string; lastModified?: string; downloadUrl?: string; error?: string } | null>(null)
  const [portableInfo, setPortableInfo] = useState<{ available: boolean; sizeMB?: string; assetName?: string; lastModified?: string; downloadUrl?: string; error?: string } | null>(null)

  // Check release status from GitHub Releases on mount
  // ── In Electron portable: use IPC (main process fetches GitHub API via Node.js,
  //    no CORS/network issues). In web/dev: use direct client-side fetch.
  useEffect(() => {
    const fetchReleaseInfo = async () => {
      try {
        // ── Electron: use IPC → main process fetch ──
        const api = window.saatirilAPI
        if (api?.isElectron && api.getReleaseInfo) {
          const data = await api.getReleaseInfo()
          setApkInfo(data.apk)
          setPortableInfo(data.portable)
          return
        }

        // ── Web / dev preview: direct client-side fetch ──
        const res = await fetch(`https://api.github.com/repos/${GITHUB_REPO}/releases/tags/latest`, {
          headers: { Accept: 'application/vnd.github+json' },
        })
        if (!res.ok) throw new Error(`GitHub API returned ${res.status}`)
        const release = await res.json()
        const assets = release.assets || []

        const apkAsset = assets.find((a: { name: string }) => a.name.endsWith('.apk'))
        const portableAsset = assets.find((a: { name: string }) => a.name.endsWith('-portable.exe') || a.name === 'saatiril-portable.exe')

        setApkInfo(apkAsset ? {
          available: true,
          sizeMB: (apkAsset.size / (1024 * 1024)).toFixed(1),
          assetName: apkAsset.name,
          lastModified: apkAsset.updated_at || release.published_at,
          downloadUrl: apkAsset.browser_download_url,
        } : { available: false, error: 'No APK asset found in latest release' })

        setPortableInfo(portableAsset ? {
          available: true,
          sizeMB: (portableAsset.size / (1024 * 1024)).toFixed(1),
          assetName: portableAsset.name,
          lastModified: portableAsset.updated_at || release.published_at,
          downloadUrl: portableAsset.browser_download_url,
        } : { available: false, error: 'No Portable asset found in latest release' })
      } catch (err: any) {
        setApkInfo({ available: false, error: err?.message || 'GitHub API error' })
        setPortableInfo({ available: false, error: err?.message || 'GitHub API error' })
      }
    }
    fetchReleaseInfo()
  }, [])

  useEffect(() => {
    const api = window.saatirilAPI
    if (api?.isElectron && api.getLanInfo) {
      api.getLanInfo().then((info: any) => {
        setLanInfo(info)
        console.log('[SAATIRIL] LAN info:', info)
      }).catch(() => {})
    }
  }, [])

  // ── Generate link URL (shared logic for copy & QR) ────────────────────
  // Returns the URL string for a given role+channel combo.
  // Same logic as copyLink but returns the URL instead of copying.
  const generateLink = useCallback(
    async (role: string, channel: number): Promise<string> => {
      const api = window.saatirilAPI
      const isElectron = api?.isElectron

      if (isElectron) {
        try {
          const info = lanInfo || (await api.getLanInfo())
          const ips = info.ips
          const lanIP = ips.length > 0 ? ips[0].address : 'localhost'
          return `http://${lanIP}:${info.httpPort}/?role=${role}&channel=${channel}&socketPort=${info.socketPort}`
        } catch {
          const hostname = window.location.hostname
          const socketPort = new URLSearchParams(window.location.search).get('socketPort') || '3003'
          return `http://${hostname}:3000/?role=${role}&channel=${channel}&socketPort=${socketPort}`
        }
      } else {
        const socketPort = new URLSearchParams(window.location.search).get('socketPort') || '3003'
        let origin = window.location.origin

        const hostname = window.location.hostname
        if (hostname === 'localhost' || hostname === '127.0.0.1') {
          try {
            const pc = new RTCPeerConnection({ iceServers: [] })
            pc.createDataChannel('')
            const offer = await pc.createOffer()
            await pc.setLocalDescription(offer)

            const lanIP = await new Promise<string | null>((resolve) => {
              const timeout = setTimeout(() => { pc.close(); resolve(null) }, 3000)
              pc.onicecandidate = (e) => {
                if (!e.candidate) return
                const parts = e.candidate.candidate.split(' ')
                const ip = parts[4]
                if (ip && /^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$/.test(ip) && !ip.startsWith('0.') && ip !== '0.0.0.0') {
                  clearTimeout(timeout)
                  pc.close()
                  resolve(ip)
                }
              }
            })

            if (lanIP) {
              const port = window.location.port || '3000'
              origin = `http://${lanIP}:${port}`
            }
          } catch {
            // WebRTC not available — keep localhost URL
          }
        }

        return `${origin}/?role=${role}&channel=${channel}&socketPort=${socketPort}`
      }
    },
    [lanInfo],
  )

  // ── Generate download link helper ────────────────────────────────
  // Uses direct GitHub Release URL — works in both dev and portable Electron
  // (no server-side API route needed)
  const generateDownloadLink = useCallback(
    (type: 'apk' | 'portable'): string => {
      const info = type === 'portable' ? portableInfo : apkInfo
      if (info?.downloadUrl) return info.downloadUrl
      // Fallback: construct the GitHub Release URL directly using the tag name
      // NOTE: Use /releases/download/{tag}/ not /releases/latest/download/
      // because the latter only works for non-prerelease releases.
      const filename = type === 'portable' ? 'saatiril-portable.exe' : 'saatiril-operator.apk'
      return `https://github.com/${GITHUB_REPO}/releases/download/latest/${filename}`
    },
    [apkInfo, portableInfo],
  )

  // Convenience wrappers
  const generateApkLink = useCallback(() => generateDownloadLink('apk'), [generateDownloadLink])
  const generatePortableLink = useCallback(() => generateDownloadLink('portable'), [generateDownloadLink])

  // ── Show QR code dialog ─────────────────────────────────────────────
  const showQrCode = useCallback(
    async (role: string, channel: number) => {
      const url = await generateLink(role, channel)
      const label = role === 'mc' ? `MC ${channel > 1 ? channel : ''}`.trim() : `Operator ${channel}`
      setQrLink(url)
      setQrLabel(label)
      setQrDialogOpen(true)
    },
    [generateLink],
  )

  // ── Show APK download QR code ────────────────────────────────────
  const showApkQrCode = useCallback(
    () => {
      const url = generateApkLink()
      setQrLink(url)
      setQrLabel('APK Saatiril Android')
      setQrDialogOpen(true)
    },
    [generateApkLink],
  )

  // ── Show Portable download QR code ───────────────────────────────
  const showPortableQrCode = useCallback(
    () => {
      const url = generatePortableLink()
      setQrLink(url)
      setQrLabel('Saatiril Portable Windows')
      setQrDialogOpen(true)
    },
    [generatePortableLink],
  )

  // ── Copy APK download link ────────────────────────────────────────
  const copyApkLink = useCallback(
    async () => {
      const url = generateApkLink()
      try {
        if (navigator.clipboard) {
          await navigator.clipboard.writeText(url)
        } else {
          const textarea = document.createElement('textarea')
          textarea.value = url
          document.body.appendChild(textarea)
          textarea.select()
          document.execCommand('copy')
          document.body.removeChild(textarea)
        }
        toast({
          title: 'Link APK disalin!',
          description: `APK Saatiril Android — ${url}`,
        })
      } catch {
        toast({
          title: 'Gagal menyalin',
          description: 'Tidak dapat menyalin link. Silakan salin manual.',
          variant: 'destructive',
        })
      }
    },
    [toast, generateApkLink],
  )

  // ── Copy Portable download link ──────────────────────────────────
  const copyPortableLink = useCallback(
    async () => {
      const url = generatePortableLink()
      try {
        if (navigator.clipboard) {
          await navigator.clipboard.writeText(url)
        } else {
          const textarea = document.createElement('textarea')
          textarea.value = url
          document.body.appendChild(textarea)
          textarea.select()
          document.execCommand('copy')
          document.body.removeChild(textarea)
        }
        toast({
          title: 'Link Portable disalin!',
          description: `Saatiril Portable Windows — ${url}`,
        })
      } catch {
        toast({
          title: 'Gagal menyalin',
          description: 'Tidak dapat menyalin link. Silakan salin manual.',
          variant: 'destructive',
        })
      }
    },
    [toast, generatePortableLink],
  )

  // ── Copy link handler ────────────────────────────────────────────
  // All links use HTTP. Operator needs Chrome Flag for camera access.
  //
  // CRITICAL: When the admin accesses via localhost, generated links also
  // use localhost — which doesn't work on other devices (phones, other laptops).
  // We detect the LAN IP via WebRTC and substitute it in the URL when needed.
  const copyLink = useCallback(
    async (role: string, channel: number) => {
      const api = window.saatirilAPI
      const isElectron = api?.isElectron

      let url: string
      if (isElectron) {
        try {
          const info = lanInfo || (await api.getLanInfo())
          const ips = info.ips
          const lanIP = ips.length > 0 ? ips[0].address : 'localhost'
          // Always use HTTP — Operator needs Chrome Flag for camera
          url = `http://${lanIP}:${info.httpPort}/?role=${role}&channel=${channel}&socketPort=${info.socketPort}`
        } catch {
          const hostname = window.location.hostname
          const params = new URLSearchParams(window.location.search)
          const socketPort = params.get('socketPort') || '3003'
          url = `http://${hostname}:3000/?role=${role}&channel=${channel}&socketPort=${socketPort}`
        }
      } else {
        // Web/sandbox mode: include socketPort so LAN clients can connect to the Socket.io server
        const socketPort = new URLSearchParams(window.location.search).get('socketPort') || '3003'
        let origin = window.location.origin

        // CRITICAL FIX: If accessing via localhost/127.0.0.1, the generated link
        // won't work on other devices (phones, other laptops). Try to detect the
        // LAN IP and substitute it so cross-device access works.
        const hostname = window.location.hostname
        if (hostname === 'localhost' || hostname === '127.0.0.1') {
          try {
            // Detect LAN IP via WebRTC (same technique as main-app.tsx)
            const pc = new RTCPeerConnection({ iceServers: [] })
            pc.createDataChannel('')
            const offer = await pc.createOffer()
            await pc.setLocalDescription(offer)

            const lanIP = await new Promise<string | null>((resolve) => {
              const timeout = setTimeout(() => { pc.close(); resolve(null) }, 3000)
              pc.onicecandidate = (e) => {
                if (!e.candidate) return
                const parts = e.candidate.candidate.split(' ')
                const ip = parts[4]
                if (ip && /^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$/.test(ip) && !ip.startsWith('0.') && ip !== '0.0.0.0') {
                  clearTimeout(timeout)
                  pc.close()
                  resolve(ip)
                }
              }
            })

            if (lanIP) {
              const port = window.location.port || '3000'
              origin = `http://${lanIP}:${port}`
              console.log(`[SAATIRIL] LAN IP detected for link: ${lanIP}`)
            }
          } catch {
            // WebRTC not available — keep localhost URL
          }
        }

        url = `${origin}/?role=${role}&channel=${channel}&socketPort=${socketPort}`
      }
      try {
        if (navigator.clipboard) {
          await navigator.clipboard.writeText(url)
        } else {
          const textarea = document.createElement('textarea')
          textarea.value = url
          document.body.appendChild(textarea)
          textarea.select()
          document.execCommand('copy')
          document.body.removeChild(textarea)
        }
        toast({
          title: 'Link disalin!',
          description: `${role.toUpperCase()} ${channel > 0 ? channel : ''} — ${url}`,
        })
      } catch {
        toast({
          title: 'Gagal menyalin',
          description: 'Tidak dapat menyalin link. Silakan salin manual.',
          variant: 'destructive',
        })
      }
    },
    [toast, lanInfo],
  )

  // ── Export participants to Excel ─────────────────────────────────
  // Generates an .xlsx file with No, NIM, Nama, Status, Channel — used by
  // admin as an offline control sheet to verify total/belum/sudah at the
  // end of an event.
  const exportToExcel = useCallback(() => {
    if (!currentProject) {
      toast({
        title: 'Tidak ada proyek aktif',
        description: 'Buka proyek terlebih dahulu sebelum mengekspor.',
        variant: 'destructive',
      })
      return
    }
    if (database.length === 0) {
      toast({
        title: 'Daftar peserta kosong',
        description: 'Belum ada peserta untuk diekspor.',
        variant: 'destructive',
      })
      return
    }

    try {
      const rows = database.map((s, idx) => ({
        No: idx + 1,
        NIM: s.nim,
        Nama: s.nama,
        Status: statusToLabel(s.status),
        Channel: s.assignedChannel,
      }))
      const ws = XLSX.utils.json_to_sheet(rows)
      // Column widths
      ws['!cols'] = [
        { wch: 5 },
        { wch: 20 },
        { wch: 36 },
        { wch: 14 },
        { wch: 10 },
      ]
      const wb = XLSX.utils.book_new()
      XLSX.utils.book_append_sheet(wb, ws, 'Daftar Peserta')

      const dateStr = new Date().toISOString().slice(0, 10)
      const safeName = currentProject.name.replace(/[^a-zA-Z0-9-_]/g, '_').slice(0, 40)
      const filename = `Daftar_Peserta_${safeName}_${dateStr}.xlsx`
      XLSX.writeFile(wb, filename)

      toast({
        title: 'Ekspor berhasil',
        description: `${filename} — ${database.length} peserta (Selesai: ${doneCount}, Belum: ${belumCount}).`,
      })
    } catch (err) {
      console.error('[SAATIRIL ADMIN] Excel export failed:', err)
      toast({
        title: 'Ekspor gagal',
        description: 'Terjadi kesalahan saat membuat file Excel.',
        variant: 'destructive',
      })
    }
  }, [currentProject, database, doneCount, belumCount, toast])

  // ── Render: Daftar Peserta (replaces Status Panel + Live Command Center) ──
  // Shows: Total / Selesai / Belum stats + scrollable participant list +
  // Excel export button. Gives admin a single control panel for monitoring
  // participant progress and exporting data for offline verification.
  const renderDaftarPeserta = () => (
    <Card className={`${PANEL} ${BORDER} shadow-lg flex flex-col`}>
      <CardHeader className="pb-2 shrink-0">
        <CardTitle className="flex items-center justify-between gap-2">
          <div className="flex items-center gap-2 text-sm font-semibold tracking-wide text-[#c4b5fd]">
            <Users className="size-4" style={{ color: GOLD }} />
            Daftar Peserta
          </div>
          <Button
            onClick={exportToExcel}
            disabled={database.length === 0}
            variant="outline"
            size="sm"
            className="h-8 gap-1.5 border-emerald-400/30 bg-emerald-400/10 text-emerald-300 hover:bg-emerald-400/20 hover:text-emerald-200 disabled:opacity-40"
            title="Ekspor daftar peserta & status ke Excel"
          >
            <FileSpreadsheet className="size-3.5" />
            <span className="hidden sm:inline">Excel</span>
            <Download className="size-3" />
          </Button>
        </CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-3 flex-1 min-h-0">
        {/* ── Stats: Total / Selesai / Belum ── */}
        <div className="grid grid-cols-3 gap-2 shrink-0">
          <div className="rounded-lg bg-[#1a0b2e]/60 px-2.5 py-2.5 text-center">
            <div className="flex items-center justify-center gap-1.5 mb-1">
              <Users className="size-3 text-[#c4b5fd]" />
              <span className="text-[10px] uppercase tracking-wider text-[#c4b5fd]/70">Total</span>
            </div>
            <span className="text-2xl font-bold" style={{ color: GOLD }}>
              {totalPeserta}
            </span>
          </div>
          <div className="rounded-lg bg-[#1a0b2e]/60 px-2.5 py-2.5 text-center">
            <div className="flex items-center justify-center gap-1.5 mb-1">
              <CheckCircle2 className="size-3 text-emerald-400" />
              <span className="text-[10px] uppercase tracking-wider text-[#c4b5fd]/70">Selesai</span>
            </div>
            <span className="text-2xl font-bold text-emerald-400">{doneCount}</span>
          </div>
          <div className="rounded-lg bg-[#1a0b2e]/60 px-2.5 py-2.5 text-center">
            <div className="flex items-center justify-center gap-1.5 mb-1">
              <XCircle className="size-3 text-amber-400" />
              <span className="text-[10px] uppercase tracking-wider text-[#c4b5fd]/70">Belum</span>
            </div>
            <span className="text-2xl font-bold text-amber-400">{belumCount}</span>
          </div>
        </div>

        {/* ── Progress bar ── */}
        {totalPeserta > 0 && (
          <div className="shrink-0">
            <div className="flex items-center justify-between mb-1">
              <span className="text-[10px] text-[#c4b5fd]/70">Progres</span>
              <span className="text-[10px] font-mono text-[#c4b5fd]">
                {doneCount}/{totalPeserta} ({Math.round((doneCount / totalPeserta) * 100)}%)
              </span>
            </div>
            <div className="h-1.5 w-full rounded-full bg-[#1a0b2e]/80 overflow-hidden">
              <div
                className="h-full rounded-full bg-emerald-400 transition-all duration-300"
                style={{ width: `${(doneCount / totalPeserta) * 100}%` }}
              />
            </div>
          </div>
        )}

        {/* ── Auto-save indicator ── */}
        {lastSavedAt && (
          <div className="shrink-0 flex items-center gap-1.5 text-[10px] text-[#c4b5fd]/50">
            <CheckCircle2 className="size-3 text-emerald-500/60" />
            <span>Tersimpan otomatis{lastSavedAt ? ` — ${new Date(lastSavedAt).toLocaleTimeString('id-ID')}` : ''}</span>
          </div>
        )}

        <Separator className="bg-[#533485]/40 shrink-0" />

        {/* ── Participant list ── */}
        <div className="flex flex-col min-h-0 flex-1">
          <div className="flex items-center justify-between mb-1.5 shrink-0">
            <span className="text-[10px] font-semibold uppercase tracking-wider text-[#c4b5fd]/70">
              Detail Peserta
            </span>
            {sentCount > 0 && (
              <span className="text-[10px] text-cyan-300/80">
                {sentCount} sedang dipotret
              </span>
            )}
          </div>

          {/* Column header */}
          <div
            className="shrink-0 grid grid-cols-[28px_70px_1fr_70px] gap-1.5 px-2 py-1.5 text-[9px] font-semibold uppercase tracking-wider rounded-t-md"
            style={{ backgroundColor: '#1a0b2e80', color: '#c4b5fd99' }}
          >
            <span>No</span>
            <span>NIM</span>
            <span>Nama</span>
            <span className="text-right">Status</span>
          </div>

          <ScrollArea className="flex-1 min-h-0 max-h-72">
            <div className="flex flex-col">
              {database.length === 0 ? (
                <div className="flex items-center justify-center py-8">
                  <p className="text-xs text-[#c4b5fd]/50">Belum ada peserta</p>
                </div>
              ) : (
                database.map((student, idx) => {
                  const isDone = student.status === 'done'
                  const isSent = student.status === 'sent'
                  const isActive = student.status.startsWith('active')
                  return (
                    <div
                      key={student.id}
                      className="grid grid-cols-[28px_70px_1fr_70px] gap-1.5 items-center px-2 py-1.5 border-b border-[#533485]/20 transition-colors hover:bg-white/5"
                      style={{
                        backgroundColor: isDone
                          ? 'rgba(34,197,94,0.05)'
                          : isSent
                            ? 'rgba(6,182,212,0.05)'
                            : isActive
                              ? 'rgba(212,175,55,0.05)'
                              : 'transparent',
                      }}
                    >
                      <span className="text-[10px] font-mono text-[#c4b5fd]/50">{idx + 1}</span>
                      <span className="text-[10px] font-mono truncate text-[#c4b5fd]/80">{student.nim}</span>
                      <span
                        className={`text-xs font-medium truncate ${isDone ? 'line-through text-[#c4b5fd]/50' : 'text-[#e0e0ff]'}`}
                      >
                        {student.nama}
                      </span>
                      <div className="flex justify-end">
                        {isDone ? (
                          <Badge
                            className="text-[9px] px-1 py-0"
                            style={{
                              backgroundColor: 'rgba(74,222,128,0.15)',
                              color: '#4ade80',
                              border: '1px solid rgba(74,222,128,0.3)',
                            }}
                          >
                            <CheckCircle2 className="size-2.5 mr-0.5" />
                            Selesai
                          </Badge>
                        ) : isSent ? (
                          <Badge
                            className="text-[9px] px-1 py-0 animate-pulse"
                            style={{
                              backgroundColor: 'rgba(6,182,212,0.15)',
                              color: CYAN,
                              border: '1px solid rgba(6,182,212,0.3)',
                            }}
                          >
                            <Camera className="size-2.5 mr-0.5" />
                            Proses
                          </Badge>
                        ) : isActive ? (
                          <Badge
                            className="text-[9px] px-1 py-0 animate-pulse"
                            style={{
                              backgroundColor: 'rgba(212,175,55,0.15)',
                              color: GOLD,
                              border: '1px solid rgba(212,175,55,0.3)',
                            }}
                          >
                            <Camera className="size-2.5 mr-0.5" />
                            Aktif
                          </Badge>
                        ) : (
                          <Badge
                            className="text-[9px] px-1 py-0"
                            style={{
                              backgroundColor: 'rgba(251,191,36,0.1)',
                              color: '#fbbf24',
                              border: '1px solid rgba(251,191,36,0.25)',
                            }}
                          >
                            <Clock className="size-2.5 mr-0.5" />
                            Belum
                          </Badge>
                        )}
                      </div>
                    </div>
                  )
                })
              )}
            </div>
          </ScrollArea>
        </div>
      </CardContent>
    </Card>
  )

  // ── Render: LAN Access Distribution ──────────────────────────────
  const renderLanAccess = () => {
    const lanIP = lanInfo?.ips?.[0]?.address ?? ''

    return (
    <Card className={`${PANEL} ${BORDER} shadow-lg`}>
      <CardHeader className="pb-2">
        <CardTitle className="flex items-center gap-2 text-sm font-semibold tracking-wide text-[#c4b5fd]">
          <Wifi className="size-4" style={{ color: GOLD }} />
          LAN Access Distribution
        </CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        {/* ── Section A: APK Android (Native App) ── */}
        <div className="rounded-md p-3 text-xs" style={{ backgroundColor: '#22c55e15', border: '1px solid #22c55e33', color: '#86efac' }}>
          <p className="font-semibold mb-1.5" style={{ color: '#4ade80' }}>📱 Operator Android — APK Saatiril:</p>
          <p className="mb-1.5 opacity-80">Cara paling mudah untuk operator kamera di HP Android. Tidak perlu Chrome Flag. Langsung install dan jalankan.</p>
          <ol className="space-y-1 pl-1">
            <li><strong>1. Download & install APK</strong> — Gunakan tombol "📱 APK Android" di bawah untuk download, salin link, atau scan QR Code</li>
            <li><strong>2. Aktifkan USB OTG</strong> — Buka Pengaturan → Cari "OTG" → Aktifkan (beberapa HP menyebutnya "USB Host" atau "Koneksi USB")</li>
            <li><strong>3. Hubungkan USB capture card</strong> ke HP menggunakan kabel OTG. Pastikan HDMI terhubung ke kamera DSLR</li>
            <li><strong>4. Periksa notifikasi USB</strong> — Saat colok USB, akan muncul notifikasi. Tap dan izinkan akses</li>
            <li><strong>5. Izinkan akses kamera</strong> — Pengaturan → Aplikasi → Saatiril Operator → Izin → Kamera → Izinkan</li>
            <li><strong>6. Buka aplikasi Saatiril Operator</strong> — Pilih kamera USB dari daftar kamera yang tersedia</li>
          </ol>
          <div className="mt-2 p-2 rounded" style={{ backgroundColor: 'rgba(239,68,68,0.1)', border: '1px solid rgba(239,68,68,0.2)' }}>
            <p className="font-semibold" style={{ color: '#f87171' }}>⚠️ Jika USB capture card tidak terdeteksi:</p>
            <ul className="space-y-0.5 mt-1 pl-3" style={{ color: '#fca5a5' }}>
              <li>• Pastikan USB OTG sudah aktif di Pengaturan HP</li>
              <li>• Cabut dan pasang ulang USB capture card</li>
              <li>• Tutup aplikasi sepenuhnya (swipe close) lalu buka kembali</li>
              <li>• Pastikan tidak ada aplikasi lain yang menggunakan USB camera</li>
              <li>• Coba gunakan kabel OTG yang berbeda</li>
              <li>• Jika masih tidak terdeteksi, gunakan kamera HP (depan/belakang) sebagai alternatif</li>
            </ul>
          </div>
        </div>

        {/* ── Section B: Google Chrome Browser ── */}
        <div className="rounded-md p-3 text-xs" style={{ backgroundColor: '#f59e0b15', border: '1px solid #f59e0b33', color: '#fde68a' }}>
          <p className="font-semibold mb-1.5" style={{ color: GOLD }}>💻 Operator Chrome (PC / Laptop / Android):</p>
          <p className="mb-1.5 opacity-80">Jika menggunakan browser Chrome, WAJIB aktifkan Chrome Flag agar kamera bisa diakses melalui HTTP.</p>
          <ol className="space-y-1 pl-1">
            <li><strong>1. Buka Chrome</strong> — Di PC, laptop, atau HP Android</li>
            <li><strong>2. Aktifkan Chrome Flag</strong> (WAJIB untuk akses kamera via HTTP):
              <ul className="mt-0.5 ml-3 space-y-0.5" style={{ color: '#fbbf24' }}>
                <li>• Buka tab baru → ketik <code className="px-1 py-0.5 rounded" style={{ backgroundColor: 'rgba(255,255,255,0.1)' }}>chrome://flags</code></li>
                <li>• Cari <code className="px-1 py-0.5 rounded" style={{ backgroundColor: 'rgba(255,255,255,0.1)' }}>insecure origin</code></li>
                <li>• Pada "Insecure origins treated as secure", masukkan URL server (contoh: <code className="px-1 py-0.5 rounded" style={{ backgroundColor: 'rgba(255,255,255,0.1)' }}>http://192.168.x.x:3000</code>)</li>
                <li>• Pilih <strong>Enabled</strong> → Klik/Tap <strong>Relaunch</strong></li>
              </ul>
            </li>
            <li><strong>3. Jika di Android</strong> — Aktifkan USB OTG, hubungkan USB capture card via kabel OTG, izinkan akses kamera Chrome di Pengaturan</li>
            <li><strong>4. Jika di PC/Laptop</strong> — Hubungkan USB capture card langsung ke port USB, Chrome akan otomatis mendeteksi</li>
            <li><strong>5. Buka link Operator</strong> — Gunakan tombol "Copy Link Operator" di bawah untuk mendapatkan link</li>
            <li><strong>6. Izinkan akses kamera</strong> — Saat diminta oleh Chrome, klik/tap "Izinkan"</li>
          </ol>
          <div className="mt-2 p-2 rounded" style={{ backgroundColor: 'rgba(245,158,11,0.1)', border: '1px solid rgba(245,158,11,0.2)' }}>
            <p className="font-semibold" style={{ color: '#fbbf24' }}>💡 Tips Chrome:</p>
            <ul className="space-y-0.5 mt-1 pl-3 opacity-80">
              <li>• Chrome Flag hanya perlu diatur sekali per perangkat</li>
              <li>• Jika kamera tidak muncul, pastikan Chrome Flag sudah di-enable dan Chrome sudah di-relaunch</li>
              <li>• Di PC/Laptop, cek juga Pengaturan Privasi Windows → izinkan Chrome akses kamera</li>
              <li>• Pastikan tidak ada tab lain yang sedang menggunakan kamera</li>
            </ul>
          </div>
        </div>

        <p className="text-xs opacity-60 px-1">📌 MC tidak perlu USB OTG atau kamera — cukup buka link MC di browser saja. Hanya Operator yang perlu kamera.</p>
        {/* Session Password Display */}
        {currentProject?.config?.sessionPassword && currentProject.config.sessionPassword !== '__PASSWORD_SET__' && (
          <div className="rounded-md p-3 text-xs" style={{ backgroundColor: '#22c55e15', border: '1px solid #22c55e33', color: '#86efac' }}>
            <p className="font-semibold mb-1" style={{ color: '#4ade80' }}>🔐 Password Sesi LAN:</p>
            <p className="font-mono text-sm font-bold tracking-wider" style={{ color: '#4ade80' }}>
              {currentProject.config.sessionPassword}
            </p>
            <p className="mt-1 opacity-70">Berikan password ini kepada Operator dan MC agar bisa bergabung ke sesi.</p>
          </div>
        )}
        {mode === 'single' || mode === 'single-photoshoot' ? (
          <div className="flex flex-col gap-2">
            <div className="flex gap-2">
              <Button
                variant="outline"
                className="flex-1 justify-start gap-2 border-[#533485] bg-[#1a0b2e]/60 text-[#c4b5fd] hover:bg-[#3b2263] hover:text-[#d4af37]"
                onClick={() => copyLink('mc', 1)}
              >
                <Copy className="size-3.5" />
                Copy Link MC
              </Button>
              <Button
                variant="outline"
                className="shrink-0 gap-1.5 border-[#533485] bg-[#1a0b2e]/60 text-[#c4b5fd] hover:bg-[#3b2263] hover:text-[#d4af37]"
                onClick={() => showQrCode('mc', 1)}
                title="Tampilkan Kode QR untuk MC"
              >
                <QrCode className="size-3.5" />
              </Button>
            </div>
            <div className="flex gap-2">
              <Button
                variant="outline"
                className="flex-1 justify-start gap-2 border-[#533485] bg-[#1a0b2e]/60 text-[#c4b5fd] hover:bg-[#3b2263] hover:text-[#d4af37]"
                onClick={() => copyLink('operator', 1)}
              >
                <Copy className="size-3.5" />
                Copy Link Operator
              </Button>
              <Button
                variant="outline"
                className="shrink-0 gap-1.5 border-[#533485] bg-[#1a0b2e]/60 text-[#c4b5fd] hover:bg-[#3b2263] hover:text-[#d4af37]"
                onClick={() => showQrCode('operator', 1)}
                title="Tampilkan Kode QR untuk Operator"
              >
                <QrCode className="size-3.5" />
              </Button>
            </div>
            {/* ── Download Section: APK + Portable ── */}
            <Separator className="bg-[#533485]/40" />
            <div className="mb-1 text-xs font-semibold uppercase tracking-wider" style={{ color: '#4ade80' }}>
              <Package className="size-3 inline mr-1" />APK Saatiril Android
            </div>
            {apkInfo?.available ? (
              <>
                <div className="text-xs mb-1.5 opacity-70" style={{ color: '#86efac' }}>
                  ✅ APK tersedia ({apkInfo.sizeMB} MB)
                </div>
                <div className="flex gap-2">
                  <Button
                    variant="outline"
                    className="flex-1 justify-start gap-2 border-[#4ade80]/40 bg-[#22c55e10] text-[#4ade80] hover:bg-[#3b2263] hover:text-[#86efac]"
                    onClick={() => {
                      const url = apkInfo?.downloadUrl || generateApkLink()
                      if (url) window.open(url, '_blank')
                    }}
                  >
                    <Download className="size-3.5" />
                    Download APK
                  </Button>
                  <Button
                    variant="outline"
                    className="shrink-0 gap-1.5 border-[#4ade80]/40 bg-[#22c55e10] text-[#4ade80] hover:bg-[#3b2263] hover:text-[#86efac]"
                    onClick={copyApkLink}
                    title="Salin Link APK"
                  >
                    <Copy className="size-3.5" />
                  </Button>
                  <Button
                    variant="outline"
                    className="shrink-0 gap-1.5 border-[#4ade80]/40 bg-[#22c55e10] text-[#4ade80] hover:bg-[#3b2263] hover:text-[#86efac]"
                    onClick={showApkQrCode}
                    title="QR Code APK Android"
                  >
                    <QrCode className="size-3.5" />
                  </Button>
                </div>
              </>
            ) : (
              <div className="text-xs mb-1.5 opacity-70" style={{ color: '#fca5a5' }}>
                ⚠️ APK belum tersedia
              </div>
            )}

            {/* Portable Windows download */}
            <Separator className="bg-[#533485]/40" />
            <div className="mb-1 text-xs font-semibold uppercase tracking-wider" style={{ color: CYAN }}>
              <Monitor className="size-3 inline mr-1" />Saatiril Portable Windows
            </div>
            {portableInfo?.available ? (
              <>
                <div className="text-xs mb-1.5 opacity-70" style={{ color: '#67e8f9' }}>
                  ✅ Portable tersedia ({portableInfo.sizeMB} MB)
                </div>
                <div className="flex gap-2">
                  <Button
                    variant="outline"
                    className="flex-1 justify-start gap-2 border-[#06b6d4]/40 bg-[#06b6d410] text-[#06b6d4] hover:bg-[#3b2263] hover:text-[#67e8f9]"
                    onClick={() => {
                      const url = portableInfo?.downloadUrl || generatePortableLink()
                      if (url) window.open(url, '_blank')
                    }}
                  >
                    <Download className="size-3.5" />
                    Download Portable
                  </Button>
                  <Button
                    variant="outline"
                    className="shrink-0 gap-1.5 border-[#06b6d4]/40 bg-[#06b6d410] text-[#06b6d4] hover:bg-[#3b2263] hover:text-[#67e8f9]"
                    onClick={copyPortableLink}
                    title="Salin Link Portable"
                  >
                    <Copy className="size-3.5" />
                  </Button>
                  <Button
                    variant="outline"
                    className="shrink-0 gap-1.5 border-[#06b6d4]/40 bg-[#06b6d410] text-[#06b6d4] hover:bg-[#3b2263] hover:text-[#67e8f9]"
                    onClick={showPortableQrCode}
                    title="QR Code Portable Windows"
                  >
                    <QrCode className="size-3.5" />
                  </Button>
                </div>
              </>
            ) : (
              <div className="text-xs mb-1.5 opacity-70" style={{ color: '#fca5a5' }}>
                ⚠️ Portable belum tersedia
              </div>
            )}
          </div>
        ) : mode === 'dual-photoshoot' ? (
          /* Dual Photoshoot: 1 MC + 2 Operators */
          <div className="flex flex-col gap-4">
            <div>
              <div className="mb-2 text-xs font-semibold uppercase tracking-wider" style={{ color: '#4ade80' }}>
                MC (1 orang)
              </div>
              <div className="flex gap-2">
                <Button
                  variant="outline"
                  className="flex-1 justify-start gap-2 border-emerald-400/30 bg-[#1a0b2e]/60 text-[#c4b5fd] hover:bg-[#3b2263] hover:text-[#4ade80]"
                  onClick={() => copyLink('mc', 1)}
                >
                  <Copy className="size-3.5" />
                  MC
                </Button>
                <Button
                  variant="outline"
                  className="shrink-0 gap-1.5 border-emerald-400/30 bg-[#1a0b2e]/60 text-[#c4b5fd] hover:bg-[#3b2263] hover:text-[#4ade80]"
                  onClick={() => showQrCode('mc', 1)}
                  title="QR Code MC"
                >
                  <QrCode className="size-3.5" />
                </Button>
              </div>
            </div>

            <Separator className="bg-[#533485]/40" />

            <div>
              <div className="mb-2 text-xs font-semibold uppercase tracking-wider" style={{ color: GOLD }}>
                Operator Kamera 1
              </div>
              <div className="flex gap-2">
                <Button
                  variant="outline"
                  className="flex-1 justify-start gap-2 border-[#d4af37]/30 bg-[#1a0b2e]/60 text-[#c4b5fd] hover:bg-[#3b2263] hover:text-[#d4af37]"
                  onClick={() => copyLink('operator', 1)}
                >
                  <Copy className="size-3.5" />
                  Operator 1
                </Button>
                <Button
                  variant="outline"
                  className="shrink-0 gap-1.5 border-[#d4af37]/30 bg-[#1a0b2e]/60 text-[#c4b5fd] hover:bg-[#3b2263] hover:text-[#d4af37]"
                  onClick={() => showQrCode('operator', 1)}
                  title="QR Code Operator 1"
                >
                  <QrCode className="size-3.5" />
                </Button>
              </div>
            </div>

            <div>
              <div className="mb-2 text-xs font-semibold uppercase tracking-wider" style={{ color: CYAN }}>
                Operator Kamera 2
              </div>
              <div className="flex gap-2">
                <Button
                  variant="outline"
                  className="flex-1 justify-start gap-2 border-[#06b6d4]/30 bg-[#1a0b2e]/60 text-[#c4b5fd] hover:bg-[#3b2263] hover:text-[#06b6d4]"
                  onClick={() => copyLink('operator', 2)}
                >
                  <Copy className="size-3.5" />
                  Operator 2
                </Button>
                <Button
                  variant="outline"
                  className="shrink-0 gap-1.5 border-[#06b6d4]/30 bg-[#1a0b2e]/60 text-[#c4b5fd] hover:bg-[#3b2263] hover:text-[#06b6d4]"
                  onClick={() => showQrCode('operator', 2)}
                  title="QR Code Operator 2"
                >
                  <QrCode className="size-3.5" />
                </Button>
              </div>
            </div>

            <Separator className="bg-[#533485]/40" />

            {/* ── Download Section: APK + Portable ── */}
            <div>
              <div className="mb-2 text-xs font-semibold uppercase tracking-wider" style={{ color: '#4ade80' }}>
                <Package className="size-3 inline mr-1" />APK Saatiril Android
              </div>
              {apkInfo?.available ? (
                <>
                  <div className="text-xs mb-1.5 opacity-70" style={{ color: '#86efac' }}>
                    ✅ APK tersedia ({apkInfo.sizeMB} MB)
                  </div>
                  <div className="flex gap-2">
                    <Button
                      variant="outline"
                      className="flex-1 justify-start gap-2 border-[#4ade80]/40 bg-[#22c55e10] text-[#4ade80] hover:bg-[#3b2263] hover:text-[#86efac]"
                      onClick={() => {
                        const url = apkInfo?.downloadUrl || generateApkLink()
                        if (url) window.open(url, '_blank')
                      }}
                    >
                      <Download className="size-3.5" />
                      Download APK
                    </Button>
                    <Button
                      variant="outline"
                      className="shrink-0 gap-1.5 border-[#4ade80]/40 bg-[#22c55e10] text-[#4ade80] hover:bg-[#3b2263] hover:text-[#86efac]"
                      onClick={copyApkLink}
                      title="Salin Link APK"
                    >
                      <Copy className="size-3.5" />
                    </Button>
                    <Button
                      variant="outline"
                      className="shrink-0 gap-1.5 border-[#4ade80]/40 bg-[#22c55e10] text-[#4ade80] hover:bg-[#3b2263] hover:text-[#86efac]"
                      onClick={showApkQrCode}
                      title="QR Code APK Android"
                    >
                      <QrCode className="size-3.5" />
                    </Button>
                  </div>
                </>
              ) : (
                <div className="text-xs mb-1.5 opacity-70" style={{ color: '#fca5a5' }}>
                  ⚠️ APK belum tersedia
                </div>
              )}
            </div>

            {/* Portable Windows download */}
            <div>
              <div className="mb-2 text-xs font-semibold uppercase tracking-wider" style={{ color: CYAN }}>
                <Monitor className="size-3 inline mr-1" />Saatiril Portable Windows
              </div>
              {portableInfo?.available ? (
                <>
                  <div className="text-xs mb-1.5 opacity-70" style={{ color: '#67e8f9' }}>
                    ✅ Portable tersedia ({portableInfo.sizeMB} MB)
                  </div>
                  <div className="flex gap-2">
                    <Button
                      variant="outline"
                      className="flex-1 justify-start gap-2 border-[#06b6d4]/40 bg-[#06b6d410] text-[#06b6d4] hover:bg-[#3b2263] hover:text-[#67e8f9]"
                      onClick={() => {
                        const url = portableInfo?.downloadUrl || generatePortableLink()
                        if (url) window.open(url, '_blank')
                      }}
                    >
                      <Download className="size-3.5" />
                      Download Portable
                    </Button>
                    <Button
                      variant="outline"
                      className="shrink-0 gap-1.5 border-[#06b6d4]/40 bg-[#06b6d410] text-[#06b6d4] hover:bg-[#3b2263] hover:text-[#67e8f9]"
                      onClick={copyPortableLink}
                      title="Salin Link Portable"
                    >
                      <Copy className="size-3.5" />
                    </Button>
                    <Button
                      variant="outline"
                      className="shrink-0 gap-1.5 border-[#06b6d4]/40 bg-[#06b6d410] text-[#06b6d4] hover:bg-[#3b2263] hover:text-[#67e8f9]"
                      onClick={showPortableQrCode}
                      title="QR Code Portable Windows"
                    >
                      <QrCode className="size-3.5" />
                    </Button>
                  </div>
                </>
              ) : (
                <div className="text-xs mb-1.5 opacity-70" style={{ color: '#fca5a5' }}>
                  ⚠️ Portable belum tersedia
                </div>
              )}
            </div>
          </div>
        ) : (
          <div className="flex flex-col gap-4">
            {/* Jalur Kiri */}
            <div>
              <div className="mb-2 text-xs font-semibold uppercase tracking-wider" style={{ color: GOLD }}>
                Jalur Kiri
              </div>
              <div className="flex flex-col gap-2">
                <div className="flex gap-2">
                  <Button
                    variant="outline"
                    className="flex-1 justify-start gap-2 border-[#d4af37]/30 bg-[#1a0b2e]/60 text-[#c4b5fd] hover:bg-[#3b2263] hover:text-[#d4af37]"
                    onClick={() => copyLink('mc', 1)}
                  >
                    <Copy className="size-3.5" />
                    MC 1
                  </Button>
                  <Button
                    variant="outline"
                    className="shrink-0 gap-1.5 border-[#d4af37]/30 bg-[#1a0b2e]/60 text-[#c4b5fd] hover:bg-[#3b2263] hover:text-[#d4af37]"
                    onClick={() => showQrCode('mc', 1)}
                    title="QR Code MC 1"
                  >
                    <QrCode className="size-3.5" />
                  </Button>
                </div>
                <div className="flex gap-2">
                  <Button
                    variant="outline"
                    className="flex-1 justify-start gap-2 border-[#d4af37]/30 bg-[#1a0b2e]/60 text-[#c4b5fd] hover:bg-[#3b2263] hover:text-[#d4af37]"
                    onClick={() => copyLink('operator', 1)}
                  >
                    <Copy className="size-3.5" />
                    Operator 1
                  </Button>
                  <Button
                    variant="outline"
                    className="shrink-0 gap-1.5 border-[#d4af37]/30 bg-[#1a0b2e]/60 text-[#c4b5fd] hover:bg-[#3b2263] hover:text-[#d4af37]"
                    onClick={() => showQrCode('operator', 1)}
                    title="QR Code Operator 1"
                  >
                    <QrCode className="size-3.5" />
                  </Button>
                </div>
              </div>
            </div>

            <Separator className="bg-[#533485]/40" />

            {/* Jalur Kanan */}
            <div>
              <div className="mb-2 text-xs font-semibold uppercase tracking-wider" style={{ color: CYAN }}>
                Jalur Kanan
              </div>
              <div className="flex flex-col gap-2">
                <div className="flex gap-2">
                  <Button
                    variant="outline"
                    className="flex-1 justify-start gap-2 border-[#06b6d4]/30 bg-[#1a0b2e]/60 text-[#c4b5fd] hover:bg-[#3b2263] hover:text-[#06b6d4]"
                    onClick={() => copyLink('mc', 2)}
                  >
                    <Copy className="size-3.5" />
                    MC 2
                  </Button>
                  <Button
                    variant="outline"
                    className="shrink-0 gap-1.5 border-[#06b6d4]/30 bg-[#1a0b2e]/60 text-[#c4b5fd] hover:bg-[#3b2263] hover:text-[#06b6d4]"
                    onClick={() => showQrCode('mc', 2)}
                    title="QR Code MC 2"
                  >
                    <QrCode className="size-3.5" />
                  </Button>
                </div>
                <div className="flex gap-2">
                  <Button
                    variant="outline"
                    className="flex-1 justify-start gap-2 border-[#06b6d4]/30 bg-[#1a0b2e]/60 text-[#c4b5fd] hover:bg-[#3b2263] hover:text-[#06b6d4]"
                    onClick={() => copyLink('operator', 2)}
                  >
                    <Copy className="size-3.5" />
                    Operator 2
                  </Button>
                  <Button
                    variant="outline"
                    className="shrink-0 gap-1.5 border-[#06b6d4]/30 bg-[#1a0b2e]/60 text-[#c4b5fd] hover:bg-[#3b2263] hover:text-[#06b6d4]"
                    onClick={() => showQrCode('operator', 2)}
                    title="QR Code Operator 2"
                  >
                    <QrCode className="size-3.5" />
                  </Button>
                </div>
              </div>
            </div>

            <Separator className="bg-[#533485]/40" />

            {/* ── Download Section: APK + Portable ── */}
            <div>
              <div className="mb-2 text-xs font-semibold uppercase tracking-wider" style={{ color: '#4ade80' }}>
                <Package className="size-3 inline mr-1" />APK Saatiril Android
              </div>
              {apkInfo?.available ? (
                <>
                  <div className="text-xs mb-1.5 opacity-70" style={{ color: '#86efac' }}>
                    ✅ APK tersedia ({apkInfo.sizeMB} MB)
                  </div>
                  <div className="flex gap-2">
                    <Button
                      variant="outline"
                      className="flex-1 justify-start gap-2 border-[#4ade80]/40 bg-[#22c55e10] text-[#4ade80] hover:bg-[#3b2263] hover:text-[#86efac]"
                      onClick={() => {
                        const url = apkInfo?.downloadUrl || generateApkLink()
                        if (url) window.open(url, '_blank')
                      }}
                    >
                      <Download className="size-3.5" />
                      Download APK
                    </Button>
                    <Button
                      variant="outline"
                      className="shrink-0 gap-1.5 border-[#4ade80]/40 bg-[#22c55e10] text-[#4ade80] hover:bg-[#3b2263] hover:text-[#86efac]"
                      onClick={copyApkLink}
                      title="Salin Link APK"
                    >
                      <Copy className="size-3.5" />
                    </Button>
                    <Button
                      variant="outline"
                      className="shrink-0 gap-1.5 border-[#4ade80]/40 bg-[#22c55e10] text-[#4ade80] hover:bg-[#3b2263] hover:text-[#86efac]"
                      onClick={showApkQrCode}
                      title="QR Code APK Android"
                    >
                      <QrCode className="size-3.5" />
                    </Button>
                  </div>
                </>
              ) : (
                <div className="text-xs mb-1.5 opacity-70" style={{ color: '#fca5a5' }}>
                  ⚠️ APK belum tersedia
                </div>
              )}
            </div>

            {/* Portable Windows download */}
            <div>
              <div className="mb-2 text-xs font-semibold uppercase tracking-wider" style={{ color: CYAN }}>
                <Monitor className="size-3 inline mr-1" />Saatiril Portable Windows
              </div>
              {portableInfo?.available ? (
                <>
                  <div className="text-xs mb-1.5 opacity-70" style={{ color: '#67e8f9' }}>
                    ✅ Portable tersedia ({portableInfo.sizeMB} MB)
                  </div>
                  <div className="flex gap-2">
                    <Button
                      variant="outline"
                      className="flex-1 justify-start gap-2 border-[#06b6d4]/40 bg-[#06b6d410] text-[#06b6d4] hover:bg-[#3b2263] hover:text-[#67e8f9]"
                      onClick={() => {
                        const url = portableInfo?.downloadUrl || generatePortableLink()
                        if (url) window.open(url, '_blank')
                      }}
                    >
                      <Download className="size-3.5" />
                      Download Portable
                    </Button>
                    <Button
                      variant="outline"
                      className="shrink-0 gap-1.5 border-[#06b6d4]/40 bg-[#06b6d410] text-[#06b6d4] hover:bg-[#3b2263] hover:text-[#67e8f9]"
                      onClick={copyPortableLink}
                      title="Salin Link Portable"
                    >
                      <Copy className="size-3.5" />
                    </Button>
                    <Button
                      variant="outline"
                      className="shrink-0 gap-1.5 border-[#06b6d4]/40 bg-[#06b6d410] text-[#06b6d4] hover:bg-[#3b2263] hover:text-[#67e8f9]"
                      onClick={showPortableQrCode}
                      title="QR Code Portable Windows"
                    >
                      <QrCode className="size-3.5" />
                    </Button>
                  </div>
                </>
              ) : (
                <div className="text-xs mb-1.5 opacity-70" style={{ color: '#fca5a5' }}>
                  ⚠️ Portable belum tersedia
                </div>
              )}
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  )
  }

  // ── Render: Network Tips ──────────────────────────────────────────
  const renderNetworkTips = () => {
    const quality = networkHealth.networkQuality
    const avgLatency = networkHealth.avgLatencyMs
    const isPoor = quality === 'poor' || quality === 'fair'

    return (
      <Card className={`${PANEL} ${BORDER} shadow-lg`}>
        <CardHeader className="pb-2">
          <CardTitle className="flex items-center gap-2 text-sm font-semibold tracking-wide text-[#c4b5fd]">
            <Zap className="size-4" style={{ color: isPoor ? '#f87171' : GOLD }} />
            Tips Jaringan
            {avgLatency >= 0 && (
              <Badge
                className="text-[9px] ml-auto px-1.5 py-0.5 border-0"
                style={{
                  backgroundColor: quality === 'excellent' ? 'rgba(74,222,128,0.2)'
                    : quality === 'good' ? 'rgba(163,230,53,0.2)'
                    : quality === 'fair' ? 'rgba(251,191,36,0.2)'
                    : quality === 'poor' ? 'rgba(248,113,113,0.2)'
                    : 'rgba(196,181,253,0.15)',
                  color: quality === 'excellent' ? '#4ade80'
                    : quality === 'good' ? '#a3e635'
                    : quality === 'fair' ? '#fbbf24'
                    : quality === 'poor' ? '#f87171'
                    : '#c4b5fd',
                }}
              >
                {avgLatency}ms
              </Badge>
            )}
          </CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-2">
          {/* Best practice */}
          <div className="rounded-md p-2.5 text-xs" style={{ backgroundColor: 'rgba(74,222,128,0.08)', border: '1px solid rgba(74,222,128,0.2)', color: '#bbf7d0' }}>
            <p className="font-semibold mb-1.5" style={{ color: '#4ade80' }}>✅ Rekomendasi Terbaik</p>
            <ul className="space-y-1 pl-1">
              <li className="flex items-start gap-1.5">
                <Cable className="size-3 shrink-0 mt-0.5" style={{ color: '#4ade80' }} />
                <span>Gunakan <strong>kabel LAN</strong> untuk semua perangkat (Admin, MC, Operator) — koneksi ke <strong>switch/router yang sama</strong></span>
              </li>
              <li className="flex items-start gap-1.5">
                <Cable className="size-3 shrink-0 mt-0.5" style={{ color: '#4ade80' }} />
                <span>Latency kabel LAN: {'<1ms'} — <strong>hampir tanpa lag</strong></span>
              </li>
            </ul>
          </div>

          {/* WiFi alternative */}
          <div className="rounded-md p-2.5 text-xs" style={{ backgroundColor: 'rgba(251,191,36,0.08)', border: '1px solid rgba(251,191,36,0.2)', color: '#fef3c7' }}>
            <p className="font-semibold mb-1.5" style={{ color: '#fbbf24' }}>⚠️ WiFi — Bisa Digunakan Tapi...</p>
            <ul className="space-y-1 pl-1">
              <li>• Semua perangkat harus terhubung ke <strong>router WiFi yang sama</strong></li>
              <li>• Letakkan perangkat <strong>sedekat mungkin</strong> dengan router</li>
              <li>• Latency WiFi: 5-30ms — <strong>mungkin ada lag sesekali</strong></li>
              <li>• Hindari WiFi public/campus — terlalu banyak interferensi</li>
            </ul>
          </div>

          {/* Current status */}
          {isPoor && (
            <div className="rounded-md p-2.5 text-xs" style={{ backgroundColor: 'rgba(248,113,113,0.08)', border: '1px solid rgba(248,113,113,0.2)', color: '#fecaca' }}>
              <p className="font-semibold mb-1" style={{ color: '#f87171' }}>🔴 Jaringan Anda Saat Ini: {quality.toUpperCase()}</p>
              <p>Pertimbangkan untuk beralih ke kabel LAN untuk koneksi yang lebih stabil.</p>
            </div>
          )}
        </CardContent>
      </Card>
    )
  }

  // ── Render: Photo History Item ───────────────────────────────────
  const renderPhotoItem = (item: PhotoHistoryItem, index: number) => {
    // GUARD: skip corrupted entries (missing student object) instead of
    // crashing the entire gallery render. This can happen if a photoHistory
    // entry got malformed during reset+retake cycles or cross-client sync.
    if (!item || !item.student || !item.student.id) {
      console.warn('[SAATIRIL ADMIN] Skipping corrupted photoHistory item at index', index, item)
      return null
    }
    const { student, channel, photos } = item
    const photoshoot = isPhotoshootMode(mode)

    // Look up the version from captureVersions (synced via SYNC_DB).
    // version 1 = first capture, 2+ = retake after MC reset. This makes the
    // gallery show the versioned filename (e.g. `NIM_Nama_v2.jpg`) matching
    // the actual file on disk.
    const versionKey = `${student.id}_${channel}`
    const version = currentProject?.captureVersions?.[versionKey] ?? 1

    const channelLabel = mode === 'dual' ? (channel === 1 ? 'Kiri' : 'Kanan')
      : mode === 'dual-photoshoot' ? (channel === 1 ? 'Cam 1' : 'Cam 2')
      : 'Ch.1'
    const channelColor = mode === 'dual' ? (channel === 1 ? GOLD : CYAN)
      : mode === 'dual-photoshoot' ? (channel === 1 ? '#4ade80' : CYAN)
      : GOLD

    // Photoshoot: 1 photo with data-based name
    if (photoshoot) {
      const filename = buildPhotoshootFilename(student.nim, student.nama, channel, version)
      return (
        <div
          key={`${student.id}-${channel}-${index}`}
          className="rounded-lg border border-[#533485]/50 bg-[#1a0b2e]/50 p-3"
        >
          <div className="mb-2 flex items-center gap-2">
            <CheckCircle2 className="size-3.5 text-emerald-400 shrink-0" />
            <span className="truncate text-sm font-medium text-[#c4b5fd]">
              {student.nama}
            </span>
          </div>
          <div className="mb-2 flex items-center gap-1.5">
            <Badge
              className="text-[10px]"
              style={{
                backgroundColor: channel === 1 && mode === 'dual-photoshoot' ? 'rgba(74,222,128,0.15)' : channel === 1 ? 'rgba(212,175,55,0.15)' : 'rgba(6,182,212,0.15)',
                color: channelColor,
                borderColor: channel === 1 && mode === 'dual-photoshoot' ? 'rgba(74,222,128,0.3)' : channel === 1 ? 'rgba(212,175,55,0.3)' : 'rgba(6,182,212,0.3)',
              }}
            >
              {channelLabel}
            </Badge>
            {version > 1 && (
              <Badge className="text-[10px]" style={{ backgroundColor: 'rgba(212,175,55,0.2)', color: GOLD, borderColor: 'rgba(212,175,55,0.4)' }}>
                FOTO ULANG v{version}
              </Badge>
            )}
          </div>
          <div className="flex gap-2">
            <div className="flex flex-1 flex-col gap-1">
              <div className="flex h-20 items-center justify-center overflow-hidden rounded-md bg-[#2a164a]/80 border border-[#533485]/30">
                {photos[0] ? (
                  <img src={photos[0]} alt="Foto" className="h-full w-full object-cover" />
                ) : (
                  <ImageIcon className="size-5 text-[#533485]" />
                )}
              </div>
              <span className="truncate text-[10px] text-[#c4b5fd]/60" title={filename}>
                {filename}
              </span>
            </div>
          </div>
        </div>
      )
    }

    // Standard mode: 2 photos (Toga + Ijazah)
    const togaFilename = buildFilename(student.nim, student.nama, 1, 'Toga', version)
    const ijazahFilename = buildFilename(student.nim, student.nama, 2, 'Ijazah', version)

    return (
      <div
        key={`${student.id}-${channel}-${index}`}
        className="rounded-lg border border-[#533485]/50 bg-[#1a0b2e]/50 p-3"
      >
        {/* Student name row */}
        <div className="mb-2 flex items-center gap-2">
          <CheckCircle2 className="size-3.5 text-emerald-400 shrink-0" />
          <span className="truncate text-sm font-medium text-[#c4b5fd]">
            {student.nama}
          </span>
        </div>

        {/* Channel badge + version badge */}
        <div className="mb-2 flex items-center gap-1.5">
          <Badge
            className="text-[10px]"
            style={{
              backgroundColor:
                channel === 1 ? 'rgba(212,175,55,0.15)' : 'rgba(6,182,212,0.15)',
              color: channelColor,
              borderColor:
                channel === 1 ? 'rgba(212,175,55,0.3)' : 'rgba(6,182,212,0.3)',
            }}
          >
            {channelLabel}
          </Badge>
          {version > 1 && (
            <Badge className="text-[10px]" style={{ backgroundColor: 'rgba(212,175,55,0.2)', color: GOLD, borderColor: 'rgba(212,175,55,0.4)' }}>
              FOTO ULANG v{version}
            </Badge>
          )}
        </div>

        {/* Photo thumbnails */}
        <div className="flex gap-2">
          {/* Toga */}
          <div className="flex flex-1 flex-col gap-1">
            <div className="flex h-16 items-center justify-center overflow-hidden rounded-md bg-[#2a164a]/80 border border-[#533485]/30">
              {photos[0] ? (
                <img src={photos[0]} alt="Toga" className="h-full w-full object-cover" />
              ) : (
                <ImageIcon className="size-5 text-[#533485]" />
              )}
            </div>
            <span className="truncate text-[10px] text-[#c4b5fd]/60" title={togaFilename}>
              {togaFilename}
            </span>
          </div>
          {/* Ijazah */}
          <div className="flex flex-1 flex-col gap-1">
            <div className="flex h-16 items-center justify-center overflow-hidden rounded-md bg-[#2a164a]/80 border border-[#533485]/30">
              {photos[1] ? (
                <img src={photos[1]} alt="Ijazah" className="h-full w-full object-cover" />
              ) : (
                <ImageIcon className="size-5 text-[#533485]" />
              )}
            </div>
            <span className="truncate text-[10px] text-[#c4b5fd]/60" title={ijazahFilename}>
              {ijazahFilename}
            </span>
          </div>
        </div>
      </div>
    )
  }

  // ── Render: Photo Gallery ────────────────────────────────────────
  const renderPhotoGallery = () => (
    <Card className={`${PANEL} ${BORDER} shadow-lg flex flex-col h-full`}>
      <CardHeader className="pb-2 shrink-0">
        <CardTitle className="flex items-center justify-between">
          <div className="flex items-center gap-2 text-sm font-semibold tracking-wide text-[#c4b5fd]">
            <ImageIcon className="size-4" style={{ color: GOLD }} />
            Log Render & Penyimpanan
          </div>
          <Badge
            className="text-[10px] border-[#533485]/50"
            style={{ backgroundColor: 'rgba(212,175,55,0.15)', color: GOLD }}
          >
            {photoHistory.length} foto
          </Badge>
        </CardTitle>
      </CardHeader>
      <CardContent className="flex-1 min-h-0 pt-0 overflow-hidden">
        {photoHistory.length === 0 ? (
          <div className="flex h-full min-h-[200px] flex-col items-center justify-center gap-3 py-8">
            <div className="flex size-16 items-center justify-center rounded-full bg-[#3b2263]/50">
              <Camera className="size-7 text-[#533485]" />
            </div>
            <p className="max-w-[240px] text-center text-sm text-[#c4b5fd]/60">
              Server aktif. Menunggu jepretan dari Operator Kamera...
            </p>
            <div className="flex items-center gap-1.5 text-xs text-[#533485]">
              <Clock className="size-3" />
              <span>Menunggu aktivitas</span>
            </div>
          </div>
        ) : (
          <ScrollArea className="h-full">
            <div className="grid grid-cols-1 gap-3 pr-2 sm:grid-cols-2">
              {photoHistory.map((item, idx) => renderPhotoItem(item, idx))}
            </div>
          </ScrollArea>
        )}
      </CardContent>
    </Card>
  )

  // ── Main render ──────────────────────────────────────────────────
  return (
    <div className={`${BG} h-full w-full p-2 sm:p-4 md:p-6 overflow-hidden`}>
      <div className="mx-auto flex h-full max-w-7xl flex-col gap-3 sm:gap-4 md:flex-row md:gap-6">
        {/* ── Left Column (1/3 on desktop, full width on mobile) ── */}
        <div className="flex w-full flex-col gap-3 sm:gap-4 md:w-1/3 shrink-0 overflow-y-auto custom-scroll max-h-[50vh] md:max-h-none">
          {renderDaftarPeserta()}
          {renderLanAccess()}
          {renderNetworkTips()}
        </div>

        {/* ── Right Column (2/3 on desktop, full width on mobile) ── */}
        <div className="w-full md:w-2/3 min-h-0 flex-1 max-h-[45vh] md:max-h-none">
          {renderPhotoGallery()}
        </div>
      </div>

      {/* ── QR Code Dialog ─────────────────────────────────────────────────── */}
      <Dialog open={qrDialogOpen} onOpenChange={setQrDialogOpen}>
        <DialogContent className="sm:max-w-md border-[#533485] bg-[#2a164a] text-white">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2 text-white">
              <QrCode className="size-5" style={{ color: GOLD }} />
              Kode QR — {qrLabel}
            </DialogTitle>
            <DialogDescription className="text-[#c4b5fd]">
              Scan kode QR ini dengan perangkat yang akan digunakan untuk bergabung ke sesi.
            </DialogDescription>
          </DialogHeader>
          <div className="flex flex-col items-center gap-4 py-4">
            {/* QR Code */}
            <div className="rounded-xl bg-white p-4 shadow-lg">
              <QRCodeSVG
                value={qrLink}
                size={220}
                level="M"
                bgColor="#ffffff"
                fgColor="#1a0b2e"
                includeMargin={false}
              />
            </div>
            {/* Link text */}
            <div className="w-full rounded-md bg-[#1a0b2e]/60 border border-[#533485]/50 p-3">
              <p className="break-all text-center text-xs font-mono" style={{ color: '#c4b5fd' }}>
                {qrLink}
              </p>
            </div>
            {/* Copy button */}
            <Button
              className="w-full font-semibold"
              style={{ backgroundColor: GOLD, color: '#1a0b2e' }}
              onClick={async () => {
                try {
                  if (navigator.clipboard) {
                    await navigator.clipboard.writeText(qrLink)
                  } else {
                    const textarea = document.createElement('textarea')
                    textarea.value = qrLink
                    document.body.appendChild(textarea)
                    textarea.select()
                    document.execCommand('copy')
                    document.body.removeChild(textarea)
                  }
                  toast({ title: 'Link disalin!', description: qrLink })
                } catch {
                  toast({ title: 'Gagal menyalin', variant: 'destructive' })
                }
              }}
            >
              <Copy className="mr-2 size-4" />
              Salin Link
            </Button>
          </div>
        </DialogContent>
      </Dialog>
    </div>
  )
}
