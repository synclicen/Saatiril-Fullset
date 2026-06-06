'use client'

import { useEffect, useMemo, useRef, useCallback, useState } from 'react'
import { Card, CardContent } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Input } from '@/components/ui/input'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Megaphone, Users, Clock, CheckCircle2, Loader2, Camera, Monitor, Search, Send, RotateCcw } from 'lucide-react'
import { useSaatirilStore, type Student, type StudentStatus, type PhotoHistoryItem, type CameraMode, mergeDatabases, stripFrameForSync, preserveFrameOnSync, isPhotoshootMode, isDualPhotoshootMode, channelCount } from '@/store/use-saatiril-store'
import { emitLocal, onLocal, offLocal } from '@/lib/socket'
import { useIsMobile } from '@/hooks/use-mobile'
import { NetworkQualityBadge } from '@/components/saatiril/network-quality-badge'

// ─── Theme tokens ───────────────────────────────────────────────────────────
const THEME = {
  bg: '#1a0b2e',
  panel: '#2a164a',
  card: '#3b2263',
  border: '#533485',
  gold: '#d4af37',
  muted: '#c4b5fd',
  emerald: '#4ade80',
  cyan: '#06b6d4',
} as const

// ─── Helpers ────────────────────────────────────────────────────────────────
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

// ─── Socket event data shapes ───────────────────────────────────────────────
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

interface PhotosSavedData {
  student: Student
  photos: string[]
  channel: number
}

interface OpProgressData {
  channel: number
  status: string
}

// ─── Component ──────────────────────────────────────────────────────────────
export function McPanel({ readOnly = false }: { readOnly?: boolean }) {
  const isMobile = useIsMobile()

  const currentProject = useSaatirilStore((s) => s.currentProject)
  const myChannel = useSaatirilStore((s) => s.myChannel)
  const updateStudentStatus = useSaatirilStore((s) => s.updateStudentStatus)
  const updateCurrentProject = useSaatirilStore((s) => s.updateCurrentProject)
  const saveProjectsToStorageNow = useSaatirilStore((s) => s.saveProjectsToStorageNow)

  const [opProgressText, setOpProgressText] = useState<string>('')
  const [opProgressChannel, setOpProgressChannel] = useState<number>(0)
  // ── Photoshoot mode: search state ─────────────────────────────────────────
  const [searchQuery, setSearchQuery] = useState('')
  const [selectedStudent, setSelectedStudent] = useState<Student | null>(null)

  const myChannelRef = useRef(myChannel)
  const currentProjectRef = useRef(currentProject)
  useEffect(() => { myChannelRef.current = myChannel }, [myChannel])
  useEffect(() => { currentProjectRef.current = currentProject }, [currentProject])

  const mode = currentProject?.config.mode ?? 'single'
  const photoshoot = isPhotoshootMode(mode)
  const dualPhotoshoot = isDualPhotoshootMode(mode)

  // ── For non-photoshoot modes: channel-filtered students ──────────────────
  const channelStudents = useMemo<Student[]>(() => {
    if (!currentProject) return []
    if (photoshoot) {
      // In photoshoot modes, all students are in one pool
      return currentProject.database
    }
    return currentProject.database.filter((s) => s.assignedChannel === myChannel)
  }, [currentProject, myChannel, photoshoot])

  // Non-photoshoot: currently active student for our channel
  const currentlyActive = useMemo<Student | null>(() => {
    if (photoshoot) return null // Not used in photoshoot mode
    const targetStatus: StudentStatus = `active_${myChannel}`
    return channelStudents.find((s) => s.status === targetStatus) ?? null
  }, [channelStudents, myChannel, photoshoot])

  const nextPending = useMemo<Student | null>(() => {
    return channelStudents.find((s) => s.status === 'pending') ?? null
  }, [channelStudents])

  const remainingCount = useMemo<number>(() => {
    return channelStudents.filter((s) => s.status === 'pending').length
  }, [channelStudents])

  const isPhotographing = !photoshoot && currentlyActive !== null

  // ── Photoshoot: students sent to operators ──────────────────────────────
  const sentStudents = useMemo<Student[]>(() => {
    if (!photoshoot) return []
    return channelStudents.filter((s) => s.status === 'sent')
  }, [photoshoot, channelStudents])

  // ── Photoshoot: check per-channel completion from photoHistory ──────────
  const getStudentChannelCompletion = useCallback((studentId: string): Record<number, boolean> => {
    const proj = currentProjectRef.current
    if (!proj) return {}
    const result: Record<number, boolean> = {}
    const chCount = channelCount(proj.config.mode)
    for (let ch = 1; ch <= chCount; ch++) {
      result[ch] = proj.photoHistory.some((h) => h.student.id === studentId && h.channel === ch)
    }
    return result
  }, [])

  // ── Photoshoot: filtered search results ───────────────────────────────────
  const searchResults = useMemo<Student[]>(() => {
    if (!photoshoot || !searchQuery.trim()) return []
    const q = searchQuery.toLowerCase().trim()
    return channelStudents.filter(
      (s) =>
        (s.status === 'pending' || s.status === 'sent') &&
        (s.nim.toLowerCase().includes(q) || s.nama.toLowerCase().includes(q))
    )
  }, [photoshoot, searchQuery, channelStudents])

  const activeRowRef = useRef<HTMLDivElement>(null)
  const nextRowRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const target = activeRowRef.current ?? nextRowRef.current
    if (target) {
      target.scrollIntoView({ behavior: 'smooth', block: 'center' })
    }
  }, [currentlyActive, nextPending])

  // ── Socket: SYNC_DB
  useEffect(() => {
    const handleSyncDb = (data: SyncDbData) => {
      if (!data.project) return
      const proj = data.project
      const curProj = currentProjectRef.current
      if (curProj && proj.id === curProj.id) {
        const mergedDb = mergeDatabases(curProj.database, proj.database)
        const mergedConfig = preserveFrameOnSync(proj.config, curProj.config)
        updateCurrentProject({
          ...curProj,
          database: mergedDb,
          photoHistory: proj.photoHistory?.length ? proj.photoHistory : curProj.photoHistory,
          config: mergedConfig,
        })
      }
    }

    onLocal('SYNC_DB', handleSyncDb)
    return () => { offLocal('SYNC_DB', handleSyncDb) }
  }, [updateCurrentProject])

  // ── Socket: PHOTOS_SAVED
  useEffect(() => {
    const handlePhotosSaved = (data: PhotosSavedData) => {
      console.log('[SAATIRIL MC] PHOTOS_SAVED received:', data.student?.nama, 'channel:', data.channel)

      if (!photoshoot && data.channel !== myChannelRef.current) {
        console.log('[SAATIRIL MC] PHOTOS_SAVED ignored: channel mismatch')
        return
      }

      // For non-photoshoot: mark done immediately
      if (!photoshoot) {
        updateStudentStatus(data.student.id, 'done')
        setOpProgressText('')
        saveProjectsToStorageNow()
        return
      }

      // For photoshoot: add to photoHistory and check per-channel completion
      const curProj = currentProjectRef.current
      if (!curProj) return

      const historyItem: PhotoHistoryItem = {
        student: data.student,
        photos: data.photos,
        channel: data.channel,
      }

      const existing = curProj.photoHistory.findIndex(
        (h) => h.student.id === data.student.id && h.channel === data.channel,
      )
      let newHistory: PhotoHistoryItem[]
      if (existing !== -1) {
        newHistory = [...curProj.photoHistory]
        newHistory[existing] = historyItem
      } else {
        newHistory = [...curProj.photoHistory, historyItem]
      }

      // Check if all required channels have completed
      const chCount = channelCount(curProj.config.mode)
      let allChannelsDone = true
      for (let ch = 1; ch <= chCount; ch++) {
        const chDone = newHistory.some((h) => h.student.id === data.student.id && h.channel === ch)
        if (!chDone) {
          allChannelsDone = false
          break
        }
      }

      const updatedProject = {
        ...curProj,
        database: curProj.database.map((s) =>
          s.id === data.student.id && allChannelsDone ? { ...s, status: 'done' as StudentStatus } : s
        ),
        photoHistory: newHistory,
      }
      updateCurrentProject(updatedProject)
      saveProjectsToStorageNow()

      console.log('[SAATIRIL MC] PHOTOS_SAVED: allChannelsDone =', allChannelsDone, 'for', data.student.nama)
    }

    onLocal('PHOTOS_SAVED', handlePhotosSaved)
    return () => { offLocal('PHOTOS_SAVED', handlePhotosSaved) }
  }, [updateStudentStatus, updateCurrentProject, saveProjectsToStorageNow, photoshoot])

  // ── Socket: OP_PROGRESS
  useEffect(() => {
    const handleOpProgress = (data: OpProgressData) => {
      if (!photoshoot && data.channel !== myChannelRef.current) return
      console.log('[SAATIRIL MC] OP_PROGRESS:', data.status, 'channel:', data.channel)
      setOpProgressText(data.status)
      setOpProgressChannel(data.channel)
    }

    onLocal('OP_PROGRESS', handleOpProgress)
    return () => { offLocal('OP_PROGRESS', handleOpProgress) }
  }, [photoshoot])

  // ── Socket: MC_CALL
  useEffect(() => {
    const handleMcCall = (data: { student: Student; channel: number }) => {
      if (!photoshoot && data.channel !== myChannelRef.current) return
      updateStudentStatus(data.student.id, data.student.status)
    }

    onLocal('MC_CALL', handleMcCall)
    return () => { offLocal('MC_CALL', handleMcCall) }
  }, [updateStudentStatus, photoshoot])

  // ── Call action (non-photoshoot: sequential call)
  const handleCallNow = useCallback(() => {
    if (!nextPending || !currentProject) return

    const newStatus: StudentStatus = `active_${myChannel}`
    updateStudentStatus(nextPending.id, newStatus)
    saveProjectsToStorageNow()

    const latestProject = useSaatirilStore.getState().currentProject
    if (!latestProject) return

    const updatedProject = {
      ...latestProject,
      database: latestProject.database.map((s) =>
        s.id === nextPending.id ? { ...s, status: newStatus } : s
      ),
    }
    updateCurrentProject(updatedProject)
    setOpProgressText('')

    emitLocal('SYNC_DB', { project: stripFrameForSync(updatedProject) })
    emitLocal('MC_CALL', {
      student: { ...nextPending, status: newStatus },
      channel: myChannel,
    })
  }, [
    nextPending,
    currentProject,
    myChannel,
    updateStudentStatus,
    updateCurrentProject,
    saveProjectsToStorageNow,
  ])

  // ── Photoshoot: send selected student to operator(s)
  const handleSendToOperator = useCallback(() => {
    if (!selectedStudent || !currentProject) return

    if (dualPhotoshoot) {
      // Send to BOTH channels
      const newStatus: StudentStatus = 'sent'

      const latestProject = useSaatirilStore.getState().currentProject
      if (!latestProject) return

      // Update student status to 'sent' in database
      const updatedProject = {
        ...latestProject,
        database: latestProject.database.map((s) =>
          s.id === selectedStudent.id ? { ...s, status: newStatus } : s
        ),
      }

      updateStudentStatus(selectedStudent.id, newStatus)
      updateCurrentProject(updatedProject)
      saveProjectsToStorageNow()

      // Send MC_CALL to both channels
      emitLocal('MC_CALL', {
        student: { ...selectedStudent, status: newStatus, assignedChannel: 1 },
        channel: 1,
      })
      emitLocal('MC_CALL', {
        student: { ...selectedStudent, status: newStatus, assignedChannel: 2 },
        channel: 2,
      })
      emitLocal('SYNC_DB', { project: stripFrameForSync(updatedProject) })
    } else {
      // Single photoshoot: send to channel 1
      const newStatus: StudentStatus = 'sent'

      const latestProject = useSaatirilStore.getState().currentProject
      if (!latestProject) return

      const updatedProject = {
        ...latestProject,
        database: latestProject.database.map((s) =>
          s.id === selectedStudent.id ? { ...s, status: newStatus } : s
        ),
      }

      updateStudentStatus(selectedStudent.id, newStatus)
      updateCurrentProject(updatedProject)
      saveProjectsToStorageNow()

      emitLocal('MC_CALL', {
        student: { ...selectedStudent, status: newStatus },
        channel: 1,
      })
      emitLocal('SYNC_DB', { project: stripFrameForSync(updatedProject) })
    }

    setSearchQuery('')
    setSelectedStudent(null)
  }, [selectedStudent, currentProject, dualPhotoshoot, updateStudentStatus, updateCurrentProject, saveProjectsToStorageNow])

  // ── Photoshoot: reset (for retake)
  const handleResetForRetake = useCallback((student: Student) => {
    if (!currentProject) return

    const latestProject = useSaatirilStore.getState().currentProject
    if (!latestProject) return

    const updatedProject = {
      ...latestProject,
      database: latestProject.database.map((s) =>
        s.id === student.id ? { ...s, status: 'pending' as StudentStatus } : s
      ),
    }

    updateStudentStatus(student.id, 'pending')
    updateCurrentProject(updatedProject)
    saveProjectsToStorageNow()

    emitLocal('SYNC_DB', { project: stripFrameForSync(updatedProject) })

    // Pre-select the student for easy re-send
    setSelectedStudent({ ...student, status: 'pending' })
    setSearchQuery(student.nama)
  }, [currentProject, updateStudentStatus, updateCurrentProject, saveProjectsToStorageNow])

  // ── Render helpers
  const renderCallButton = () => {
    if (readOnly) {
      return (
        <Button
          disabled
          className={`w-full font-bold cursor-not-allowed ${isMobile ? 'h-12 text-sm' : 'h-14 text-lg'}`}
          style={{
            backgroundColor: THEME.panel,
            color: THEME.muted,
            border: `2px solid ${THEME.border}`,
            opacity: 0.6,
          }}
        >
          <Monitor className="size-5" />
          MONITOR — HANYA LIHAT
        </Button>
      )
    }

    if (photoshoot) {
      // Photoshoot mode: always allow sending — NO BLOCKING
      return (
        <Button
          disabled={!selectedStudent || selectedStudent.status === 'done'}
          onClick={handleSendToOperator}
          className={`w-full font-bold cursor-pointer transition-all duration-200 active:scale-[0.98] ${isMobile ? 'h-14 text-base' : 'h-14 text-lg hover:scale-[1.02]'}`}
          style={{
            backgroundColor: selectedStudent && selectedStudent.status !== 'done' ? THEME.emerald : THEME.panel,
            color: selectedStudent && selectedStudent.status !== 'done' ? THEME.bg : THEME.muted,
            border: `2px solid ${selectedStudent && selectedStudent.status !== 'done' ? THEME.emerald : THEME.border}`,
            boxShadow: selectedStudent && selectedStudent.status !== 'done' ? `0 0 20px ${THEME.emerald}44` : 'none',
          }}
        >
          <Send className="size-5" />
          {dualPhotoshoot ? 'KIRIM KE 2 KAMERA' : 'KIRIM KE OPERATOR'}
        </Button>
      )
    }

    // Non-photoshoot: existing blocking flow
    if (isPhotographing) {
      return (
        <div className="space-y-2">
          <Button
            disabled
            className={`w-full font-bold cursor-not-allowed ${isMobile ? 'h-12 text-sm' : 'h-14 text-lg'}`}
            style={{
              backgroundColor: THEME.panel,
              color: THEME.muted,
              border: `2px solid ${THEME.border}`,
            }}
          >
            <Loader2 className="size-5 animate-spin" />
            {opProgressText || 'TUNGGU KAMERA...'}
          </Button>
        </div>
      )
    }

    if (nextPending) {
      return (
        <Button
          onClick={handleCallNow}
          className={`w-full font-bold cursor-pointer transition-all duration-200 active:scale-[0.98] ${isMobile ? 'h-14 text-base' : 'h-14 text-lg hover:scale-[1.02]'}`}
          style={{
            backgroundColor: THEME.gold,
            color: THEME.bg,
            border: `2px solid ${THEME.gold}`,
            boxShadow: `0 0 20px ${THEME.gold}44`,
          }}
        >
          <Megaphone className="size-5" />
          PANGGIL SEKARANG
        </Button>
      )
    }

    return (
      <Button
        disabled
        className={`w-full font-bold cursor-not-allowed ${isMobile ? 'h-12 text-sm' : 'h-14 text-lg'}`}
        style={{
          backgroundColor: THEME.panel,
          color: THEME.muted,
          border: `2px solid ${THEME.border}`,
          opacity: 0.6,
        }}
      >
        <Users className="size-5" />
        ANTREAN HABIS
      </Button>
    )
  }

  const getRowStyle = (student: Student): React.CSSProperties => {
    const isActive = isActiveStatus(student.status)
    const isNext = !photoshoot && student.id === nextPending?.id && student.status === 'pending'
    const isDone = student.status === 'done'
    const isSent = student.status === 'sent'
    const isSelected = photoshoot && selectedStudent?.id === student.id

    if (isActive) {
      return {
        backgroundColor: `${THEME.gold}22`,
        borderLeft: `4px solid ${THEME.gold}`,
        boxShadow: `0 0 12px ${THEME.gold}44`,
      }
    }

    if (isSelected) {
      return {
        backgroundColor: `${THEME.emerald}22`,
        borderLeft: `4px solid ${THEME.emerald}`,
        boxShadow: `0 0 12px ${THEME.emerald}44`,
      }
    }

    if (isSent) {
      return {
        backgroundColor: `${THEME.cyan}11`,
        borderLeft: `4px solid ${THEME.cyan}`,
      }
    }

    if (isNext) {
      return {
        backgroundColor: THEME.panel,
        borderLeft: `4px solid ${THEME.gold}`,
      }
    }

    if (isDone) {
      return {
        backgroundColor: '#22c55e0d',
        opacity: 0.55,
        borderLeft: `4px solid #22c55e66`,
      }
    }

    return {
      backgroundColor: THEME.panel,
      borderLeft: `4px solid ${THEME.border}`,
    }
  }

  const renderStatusBadge = (status: StudentStatus) => {
    if (status === 'done') {
      return (
        <Badge
          className="text-[10px] px-1.5 py-0"
          style={{ backgroundColor: '#22c55e33', color: '#4ade80', border: '1px solid #22c55e55' }}
        >
          <CheckCircle2 className="size-3 mr-0.5" />
          Selesai
        </Badge>
      )
    }

    if (status === 'sent') {
      return (
        <Badge
          className="text-[10px] px-1.5 py-0"
          style={{ backgroundColor: `${THEME.cyan}33`, color: THEME.cyan, border: `1px solid ${THEME.cyan}66` }}
        >
          <Camera className="size-3 mr-0.5" />
          Dikirim
        </Badge>
      )
    }

    if (isActiveStatus(status)) {
      return (
        <Badge
          className="text-[10px] px-1.5 py-0 animate-pulse"
          style={{
            backgroundColor: `${THEME.gold}33`,
            color: THEME.gold,
            border: `1px solid ${THEME.gold}66`,
          }}
        >
          <Camera className="size-3 mr-0.5" />
          {statusLabel(status)}
        </Badge>
      )
    }

    return (
      <Badge
        className="text-[10px] px-1.5 py-0"
        style={{
          backgroundColor: `${THEME.border}44`,
          color: THEME.muted,
          border: `1px solid ${THEME.border}`,
        }}
      >
        <Clock className="size-3 mr-0.5" />
        Menunggu
      </Badge>
    )
  }

  // ── Photoshoot: search input + results
  const renderPhotoshootSearch = () => (
    <Card
      className="shrink-0 border-2 rounded-xl"
      style={{
        backgroundColor: THEME.card,
        borderColor: THEME.emerald,
        boxShadow: `0 0 20px ${THEME.emerald}22`,
      }}
    >
      <CardContent className="p-3 space-y-2">
        <p
          className="text-[10px] font-semibold uppercase tracking-widest"
          style={{ color: THEME.emerald }}
        >
          Cari Peserta — Urutan Bebas
        </p>

        <div className="relative">
          <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 size-4" style={{ color: THEME.muted }} />
          <Input
            placeholder="Cari NIM atau Nama..."
            value={searchQuery}
            onChange={(e) => {
              setSearchQuery(e.target.value)
              setSelectedStudent(null)
            }}
            className="pl-8 border-[#533485] bg-[#3b2263] text-white placeholder:text-[#533485] focus-visible:border-[#4ade80] focus-visible:ring-[#4ade80]/30"
          />
        </div>

        {/* Search results */}
        {searchQuery.trim() && (
          <div className="max-h-32 overflow-y-auto rounded-lg border" style={{ borderColor: THEME.border }}>
            {searchResults.length === 0 ? (
              <p className="p-2 text-xs text-center" style={{ color: THEME.muted }}>
                Tidak ditemukan
              </p>
            ) : (
              searchResults.slice(0, 10).map((student) => (
                <button
                  key={student.id}
                  onClick={() => setSelectedStudent(student)}
                  className="w-full flex items-center gap-2 px-3 py-2 text-left transition-colors hover:bg-white/5 cursor-pointer"
                  style={{
                    backgroundColor: selectedStudent?.id === student.id ? `${THEME.emerald}22` : 'transparent',
                    borderLeft: selectedStudent?.id === student.id ? `3px solid ${THEME.emerald}` : `3px solid transparent`,
                  }}
                >
                  <span className="text-xs font-mono truncate w-16 shrink-0" style={{ color: THEME.muted }}>
                    {student.nim}
                  </span>
                  <span className="text-xs font-medium truncate flex-1" style={{ color: '#ffffff' }}>
                    {student.nama}
                  </span>
                  {renderStatusBadge(student.status)}
                </button>
              ))
            )}
          </div>
        )}

        {/* Selected student preview */}
        {selectedStudent && (
          <div className="rounded-lg p-2.5" style={{ backgroundColor: `${THEME.emerald}15`, border: `1px solid ${THEME.emerald}33` }}>
            <p className="text-[10px] font-semibold uppercase tracking-wider" style={{ color: THEME.emerald }}>
              Peserta Dipilih
            </p>
            <p className="text-sm font-bold truncate" style={{ color: '#ffffff' }}>
              {selectedStudent.nama}
            </p>
            <p className="text-xs font-mono" style={{ color: THEME.muted }}>
              {selectedStudent.nim}
            </p>
          </div>
        )}

        {renderCallButton()}
      </CardContent>
    </Card>
  )

  // ── Photoshoot: sent students panel (per-channel progress)
  const renderSentStudents = () => {
    if (!photoshoot || sentStudents.length === 0) return null

    return (
      <Card
        className="shrink-0 border rounded-xl"
        style={{ backgroundColor: THEME.card, borderColor: THEME.cyan }}
      >
        <CardContent className="p-3 space-y-2">
          <p className="text-[10px] font-semibold uppercase tracking-widest" style={{ color: THEME.cyan }}>
            <Camera className="size-3 inline mr-1" />
            Dikirim ke Operator ({sentStudents.length})
          </p>
          <div className="max-h-48 overflow-y-auto space-y-1.5">
            {sentStudents.map((student) => {
              const completion = getStudentChannelCompletion(student.id)
              return (
                <div
                  key={student.id}
                  className="flex items-center gap-2 px-2.5 py-1.5 rounded-lg"
                  style={{ backgroundColor: `${THEME.cyan}0a`, border: `1px solid ${THEME.cyan}22` }}
                >
                  <span className="text-xs font-medium truncate flex-1" style={{ color: '#ffffff' }}>
                    {student.nama}
                  </span>
                  {dualPhotoshoot ? (
                    <div className="flex items-center gap-1 shrink-0">
                      <Badge
                        className="text-[9px] px-1 py-0"
                        style={{
                          backgroundColor: completion[1] ? '#22c55e33' : `${THEME.gold}22`,
                          color: completion[1] ? '#4ade80' : THEME.gold,
                          border: `1px solid ${completion[1] ? '#22c55e55' : `${THEME.gold}44`}`,
                        }}
                      >
                        Ch.1 {completion[1] ? '✓' : '...'}
                      </Badge>
                      <Badge
                        className="text-[9px] px-1 py-0"
                        style={{
                          backgroundColor: completion[2] ? '#22c55e33' : `${THEME.cyan}22`,
                          color: completion[2] ? '#4ade80' : THEME.cyan,
                          border: `1px solid ${completion[2] ? '#22c55e55' : `${THEME.cyan}44`}`,
                        }}
                      >
                        Ch.2 {completion[2] ? '✓' : '...'}
                      </Badge>
                    </div>
                  ) : (
                    <Badge
                      className="text-[9px] px-1 py-0"
                      style={{
                        backgroundColor: completion[1] ? '#22c55e33' : `${THEME.gold}22`,
                        color: completion[1] ? '#4ade80' : THEME.gold,
                        border: `1px solid ${completion[1] ? '#22c55e55' : `${THEME.gold}44`}`,
                      }}
                    >
                      {completion[1] ? '✓ Selesai' : 'Memotret...'}
                    </Badge>
                  )}
                  <Button
                    variant="ghost"
                    size="icon"
                    className="size-6 shrink-0 cursor-pointer"
                    style={{ color: THEME.gold }}
                    onClick={() => handleResetForRetake(student)}
                    title="Reset & Kirim Ulang"
                  >
                    <RotateCcw className="size-3" />
                  </Button>
                </div>
              )
            })}
          </div>
          {opProgressText && (
            <div className="flex items-center gap-1.5 mt-1">
              <Loader2 className="size-3 animate-spin" style={{ color: THEME.cyan }} />
              <span className="text-[10px]" style={{ color: THEME.cyan }}>
                Ch.{opProgressChannel}: {opProgressText}
              </span>
            </div>
          )}
        </CardContent>
      </Card>
    )
  }

  // ── Main render
  if (!currentProject) {
    return (
      <div
        className="flex items-center justify-center h-full"
        style={{ backgroundColor: THEME.bg, color: THEME.muted }}
      >
        <p className="text-sm opacity-60">Belum ada proyek aktif</p>
      </div>
    )
  }

  // ── MOBILE LAYOUT ────────────────────────────────────────────────────────
  if (isMobile) {
    return (
      <div className="flex flex-col gap-2 h-full p-2 touch-no-select" style={{ backgroundColor: THEME.bg }}>
        {/* Call Panel */}
        {photoshoot ? renderPhotoshootSearch() : (
          <Card
            className="shrink-0 border-2 rounded-xl"
            style={{
              backgroundColor: THEME.card,
              borderColor: THEME.gold,
              boxShadow: `0 0 20px ${THEME.gold}22`,
            }}
          >
            <CardContent className="p-3 space-y-2">
              <p
                className="text-[10px] font-semibold uppercase tracking-widest"
                style={{ color: THEME.gold }}
              >
                Target Selanjutnya
              </p>

              {nextPending ? (
                <div className="space-y-0.5">
                  <p className="text-xl font-bold leading-tight truncate" style={{ color: '#ffffff' }}>
                    {nextPending.nama}
                  </p>
                  <p className="text-sm font-mono" style={{ color: THEME.muted }}>
                    {nextPending.nim}
                  </p>
                </div>
              ) : currentlyActive ? (
                <div className="space-y-0.5">
                  <p className="text-sm font-semibold leading-tight" style={{ color: THEME.gold }}>
                    Sedang difoto:
                  </p>
                  <p className="text-xl font-bold leading-tight truncate" style={{ color: '#ffffff' }}>
                    {currentlyActive.nama}
                  </p>
                  <p className="text-sm font-mono" style={{ color: THEME.muted }}>
                    {currentlyActive.nim}
                  </p>
                  {opProgressText && (
                    <div className="flex items-center gap-2 mt-1">
                      <Camera className="size-3.5" style={{ color: THEME.gold }} />
                      <span className="text-xs font-medium" style={{ color: THEME.gold }}>
                        {opProgressText}
                      </span>
                    </div>
                  )}
                </div>
              ) : (
                <p className="text-sm italic" style={{ color: THEME.muted }}>
                  Semua peserta telah dipanggil
                </p>
              )}

              {renderCallButton()}
            </CardContent>
          </Card>
        )}

        {/* Sent Students (photoshoot) */}
        {photoshoot && renderSentStudents()}

        {/* Queue List */}
        <Card
          className="flex-1 min-h-0 border rounded-xl overflow-hidden flex flex-col"
          style={{ backgroundColor: THEME.card, borderColor: THEME.border }}
        >
          <div
            className="shrink-0 flex items-center justify-between px-3 py-2"
            style={{ borderBottom: `1px solid ${THEME.border}` }}
          >
            <h3 className="text-xs font-semibold" style={{ color: '#ffffff' }}>
              {photoshoot ? 'Daftar Peserta' : 'Antrean'}: <span style={{ color: photoshoot ? THEME.emerald : THEME.gold }} className="font-bold">{remainingCount}</span>
            </h3>
            <span className="text-[10px]" style={{ color: THEME.muted }}>
              {photoshoot ? (dualPhotoshoot ? '2 Kamera' : 'Photoshoot') : `Ch.${myChannel}`}
            </span>
          </div>

          <ScrollArea className="flex-1 min-h-0">
            <div className="flex flex-col">
              {channelStudents.length === 0 ? (
                <div className="flex items-center justify-center py-8">
                  <p className="text-xs" style={{ color: THEME.muted }}>Tidak ada peserta</p>
                </div>
              ) : (
                channelStudents.map((student, idx) => {
                  const isActive = isActiveStatus(student.status)
                  const isNext = !photoshoot && student.id === nextPending?.id && student.status === 'pending'

                  return (
                    <div
                      key={student.id}
                      ref={isActive ? activeRowRef : isNext ? nextRowRef : undefined}
                      className="flex items-center gap-2 px-3 py-2 transition-colors duration-200 cursor-pointer"
                      style={getRowStyle(student)}
                      onClick={() => {
                        if (photoshoot && student.status !== 'done') {
                          setSelectedStudent(student)
                          setSearchQuery(student.nama)
                        }
                      }}
                    >
                      <span className="text-[10px] font-mono w-5 shrink-0" style={{ color: THEME.muted }}>
                        {idx + 1}
                      </span>
                      <span className="text-[10px] font-mono truncate w-16 shrink-0" style={{ color: THEME.muted }}>
                        {student.nim}
                      </span>
                      <span
                        className={`text-xs font-medium truncate flex-1 ${student.status === 'done' ? 'line-through' : ''}`}
                        style={{
                          color: isActive ? THEME.gold : selectedStudent?.id === student.id ? THEME.emerald : student.status === 'sent' ? THEME.cyan : student.status === 'done' ? THEME.muted : '#ffffff',
                        }}
                      >
                        {student.nama}
                      </span>
                      <div className="shrink-0">
                        {renderStatusBadge(student.status)}
                      </div>
                    </div>
                  )
                })
              )}
            </div>
          </ScrollArea>
        </Card>
      </div>
    )
  }

  // ── DESKTOP LAYOUT (original)
  return (
    <div className="flex flex-col gap-3 h-full p-3" style={{ backgroundColor: THEME.bg }}>
      {/* Top: Call Panel */}
      {photoshoot ? renderPhotoshootSearch() : (
        <Card
          className="shrink-0 border-2 rounded-xl"
          style={{
            backgroundColor: THEME.card,
            borderColor: THEME.gold,
            boxShadow: `0 0 20px ${THEME.gold}22`,
          }}
        >
          <CardContent className="p-4 space-y-3">
            <p
              className="text-xs font-semibold uppercase tracking-widest"
              style={{ color: THEME.gold }}
            >
              Target Pemanggilan Selanjutnya
            </p>

            {nextPending ? (
              <div className="space-y-1">
                <p className="text-2xl font-bold leading-tight" style={{ color: '#ffffff' }}>
                  {nextPending.nama}
                </p>
                <p className="text-sm font-mono" style={{ color: THEME.muted }}>
                  {nextPending.nim}
                </p>
              </div>
            ) : currentlyActive ? (
              <div className="space-y-1">
                <p className="text-lg font-semibold leading-tight" style={{ color: THEME.gold }}>
                  Sedang difoto:
                </p>
                <p className="text-2xl font-bold leading-tight" style={{ color: '#ffffff' }}>
                  {currentlyActive.nama}
                </p>
                <p className="text-sm font-mono" style={{ color: THEME.muted }}>
                  {currentlyActive.nim}
                </p>
                {opProgressText && (
                  <div className="flex items-center gap-2 mt-1">
                    <Camera className="size-3.5" style={{ color: THEME.gold }} />
                    <span className="text-xs font-medium" style={{ color: THEME.gold }}>
                      {opProgressText}
                    </span>
                  </div>
                )}
              </div>
            ) : (
              <div className="space-y-1">
                <p className="text-lg italic" style={{ color: THEME.muted }}>
                  Semua peserta telah dipanggil
                </p>
              </div>
            )}

            {renderCallButton()}
          </CardContent>
        </Card>
      )}

      {/* Sent Students (photoshoot) */}
      {photoshoot && renderSentStudents()}

      {/* Bottom: Queue List */}
      <Card
        className="flex-1 min-h-0 border rounded-xl overflow-hidden flex flex-col"
        style={{ backgroundColor: THEME.card, borderColor: THEME.border }}
      >
        <div
          className="shrink-0 flex items-center justify-between px-4 py-2.5"
          style={{ borderBottom: `1px solid ${THEME.border}` }}
        >
          <h3 className="text-sm font-semibold" style={{ color: '#ffffff' }}>
            {photoshoot ? 'Daftar Peserta' : 'Sisa Antrean'}: <span style={{ color: photoshoot ? THEME.emerald : THEME.gold }} className="font-bold">{remainingCount}</span>
          </h3>
          <div className="flex items-center gap-2">
            <NetworkQualityBadge />
            <span className="text-xs" style={{ color: THEME.muted }}>
              {photoshoot ? (dualPhotoshoot ? '2 Kamera' : 'Photoshoot') : `Channel ${myChannel}`}
            </span>
          </div>
        </div>

        <div
          className="shrink-0 grid grid-cols-[36px_90px_1fr_80px] gap-2 px-4 py-2 text-[10px] font-semibold uppercase tracking-wider"
          style={{
            backgroundColor: THEME.panel,
            color: THEME.muted,
            borderBottom: `1px solid ${THEME.border}`,
          }}
        >
          <span>No</span>
          <span>NIM</span>
          <span>Nama Lengkap</span>
          <span className="text-right">Status</span>
        </div>

        <ScrollArea className="flex-1 min-h-0">
          <div className="flex flex-col">
            {channelStudents.length === 0 ? (
              <div className="flex items-center justify-center py-12">
                <p className="text-sm" style={{ color: THEME.muted }}>
                  Tidak ada peserta
                </p>
              </div>
            ) : (
              channelStudents.map((student, idx) => {
                const isActive = isActiveStatus(student.status)
                const isNext = !photoshoot && student.id === nextPending?.id && student.status === 'pending'

                return (
                  <div
                    key={student.id}
                    ref={isActive ? activeRowRef : isNext ? nextRowRef : undefined}
                    className="grid grid-cols-[36px_90px_1fr_80px] gap-2 items-center px-4 py-2 transition-colors duration-200 cursor-pointer"
                    style={getRowStyle(student)}
                    onClick={() => {
                      if (photoshoot && student.status !== 'done') {
                        setSelectedStudent(student)
                        setSearchQuery(student.nama)
                      }
                    }}
                  >
                    <span className="text-xs font-mono" style={{ color: THEME.muted }}>{idx + 1}</span>
                    <span className="text-xs font-mono truncate" style={{ color: THEME.muted }}>{student.nim}</span>
                    <span
                      className={`text-sm font-medium truncate ${student.status === 'done' ? 'line-through' : ''}`}
                      style={{ color: isActive ? THEME.gold : selectedStudent?.id === student.id ? THEME.emerald : student.status === 'sent' ? THEME.cyan : student.status === 'done' ? THEME.muted : '#ffffff' }}
                    >
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
    </div>
  )
}

export default McPanel
