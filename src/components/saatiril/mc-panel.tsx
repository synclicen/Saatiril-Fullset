'use client'

import { useEffect, useMemo, useRef, useCallback, useState } from 'react'
import { Card, CardContent } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Megaphone, Users, Clock, CheckCircle2, Loader2, Camera, Monitor } from 'lucide-react'
import { useSaatirilStore, type Student, type StudentStatus, type PhotoHistoryItem, mergeDatabases, stripFrameForSync, preserveFrameOnSync } from '@/store/use-saatiril-store'
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

  const myChannelRef = useRef(myChannel)
  const currentProjectRef = useRef(currentProject)
  useEffect(() => { myChannelRef.current = myChannel }, [myChannel])
  useEffect(() => { currentProjectRef.current = currentProject }, [currentProject])

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

  const isPhotographing = currentlyActive !== null

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
        updateCurrentProject({
          ...curProj,
          database: mergedDb,
          photoHistory: proj.photoHistory?.length ? proj.photoHistory : curProj.photoHistory,
        })
      }
    }

    onLocal('SYNC_DB', handleSyncDb)
    return () => { offLocal('SYNC_DB', handleSyncDb) }
  }, [updateCurrentProject])

  // ── Socket: PHOTOS_SAVED
  useEffect(() => {
    const handlePhotosSaved = (data: PhotosSavedData) => {
      console.log('[SAATIRIL MC] PHOTOS_SAVED received:', data.student?.nama, 'channel:', data.channel, 'myChannel:', myChannelRef.current)

      if (data.channel !== myChannelRef.current) {
        console.log('[SAATIRIL MC] PHOTOS_SAVED ignored: channel mismatch')
        return
      }

      updateStudentStatus(data.student.id, 'done')
      saveProjectsToStorageNow()
      setOpProgressText('')

      const curProj = currentProjectRef.current
      if (curProj) {
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
        const updatedProject = {
          ...curProj,
          database: curProj.database.map((s) =>
            s.id === data.student.id ? { ...s, status: 'done' as StudentStatus } : s
          ),
          photoHistory: newHistory,
        }
        updateCurrentProject(updatedProject)
        console.log('[SAATIRIL MC] Project updated, student marked as done.')
      }
    }

    onLocal('PHOTOS_SAVED', handlePhotosSaved)
    return () => { offLocal('PHOTOS_SAVED', handlePhotosSaved) }
  }, [updateStudentStatus, updateCurrentProject, saveProjectsToStorageNow])

  // ── Socket: OP_PROGRESS
  useEffect(() => {
    const handleOpProgress = (data: OpProgressData) => {
      if (data.channel !== myChannelRef.current) return
      console.log('[SAATIRIL MC] OP_PROGRESS:', data.status)
      setOpProgressText(data.status)
    }

    onLocal('OP_PROGRESS', handleOpProgress)
    return () => { offLocal('OP_PROGRESS', handleOpProgress) }
  }, [])

  // ── Socket: MC_CALL
  useEffect(() => {
    const handleMcCall = (data: { student: Student; channel: number }) => {
      if (data.channel !== myChannelRef.current) return
      updateStudentStatus(data.student.id, data.student.status)
    }

    onLocal('MC_CALL', handleMcCall)
    return () => { offLocal('MC_CALL', handleMcCall) }
  }, [updateStudentStatus])

  // ── Call action
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

    if (isPhotographing) {
      return (
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
    const isActive = student.status === `active_${myChannel}`
    const isNext = student.id === nextPending?.id && student.status === 'pending'
    const isDone = student.status === 'done'

    if (isActive) {
      return {
        backgroundColor: `${THEME.gold}22`,
        borderLeft: `4px solid ${THEME.gold}`,
        boxShadow: `0 0 12px ${THEME.gold}44`,
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
                Semua mahasiswa telah dipanggil
              </p>
            )}

            {renderCallButton()}
          </CardContent>
        </Card>

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
              Antrean: <span style={{ color: THEME.gold }} className="font-bold">{remainingCount}</span>
            </h3>
            <span className="text-[10px]" style={{ color: THEME.muted }}>Ch.{myChannel}</span>
          </div>

          <ScrollArea className="flex-1 min-h-0">
            <div className="flex flex-col">
              {channelStudents.length === 0 ? (
                <div className="flex items-center justify-center py-8">
                  <p className="text-xs" style={{ color: THEME.muted }}>Tidak ada mahasiswa di channel ini</p>
                </div>
              ) : (
                channelStudents.map((student, idx) => {
                  const isActive = student.status === `active_${myChannel}`
                  const isNext = student.id === nextPending?.id && student.status === 'pending'

                  return (
                    <div
                      key={student.id}
                      ref={isActive ? activeRowRef : isNext ? nextRowRef : undefined}
                      className="flex items-center gap-2 px-3 py-2 transition-colors duration-200"
                      style={getRowStyle(student)}
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
                          color: isActive ? THEME.gold : student.status === 'done' ? THEME.muted : '#ffffff',
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
                Semua mahasiswa telah dipanggil
              </p>
            </div>
          )}

          {renderCallButton()}
        </CardContent>
      </Card>

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
            Sisa Antrean: <span style={{ color: THEME.gold }} className="font-bold">{remainingCount}</span>
          </h3>
          <div className="flex items-center gap-2">
            <NetworkQualityBadge />
            <span className="text-xs" style={{ color: THEME.muted }}>Channel {myChannel}</span>
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
                  Tidak ada mahasiswa di channel ini
                </p>
              </div>
            ) : (
              channelStudents.map((student, idx) => {
                const isActive = student.status === `active_${myChannel}`
                const isNext = student.id === nextPending?.id && student.status === 'pending'

                return (
                  <div
                    key={student.id}
                    ref={isActive ? activeRowRef : isNext ? nextRowRef : undefined}
                    className="grid grid-cols-[36px_90px_1fr_80px] gap-2 items-center px-4 py-2 transition-colors duration-200"
                    style={getRowStyle(student)}
                  >
                    <span className="text-xs font-mono" style={{ color: THEME.muted }}>{idx + 1}</span>
                    <span className="text-xs font-mono truncate" style={{ color: THEME.muted }}>{student.nim}</span>
                    <span
                      className={`text-sm font-medium truncate ${student.status === 'done' ? 'line-through' : ''}`}
                      style={{ color: isActive ? THEME.gold : student.status === 'done' ? THEME.muted : '#ffffff' }}
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
