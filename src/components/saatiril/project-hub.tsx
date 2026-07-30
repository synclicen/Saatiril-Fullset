'use client'

import { useCallback, useMemo, useState } from 'react'
import {
  FolderOpen,
  Plus,
  Trash2,
  Camera,
  Sparkles,
  Inbox,
  Play,
  CheckCircle2,
  Clock,
  Users,
  RotateCcw,
} from 'lucide-react'
import {
  Card,
  CardContent,
} from '@/components/ui/card'
import { SaatirilFooterLines } from '@/components/saatiril/saatiril-footer'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from '@/components/ui/alert-dialog'
import { useSaatirilStore, type Project, type CameraMode, isPhotoshootMode, isDualMode } from '@/store/use-saatiril-store'
import { useToast } from '@/hooks/use-toast'

export function ProjectHub() {
  const projects = useSaatirilStore((s) => s.projects)
  const setCurrentProject = useSaatirilStore((s) => s.setCurrentProject)
  const setCurrentScreen = useSaatirilStore((s) => s.setCurrentScreen)
  const deleteProject = useSaatirilStore((s) => s.deleteProject)
  const saveProjectsToStorageNow = useSaatirilStore((s) => s.saveProjectsToStorageNow)

  const { toast } = useToast()

  const [deletingProjectId, setDeletingProjectId] = useState<string | null>(null)

  const handleCreateProject = useCallback(() => {
    setCurrentScreen('setup')
  }, [setCurrentScreen])

  const handleOpenProject = useCallback(
    (project: Project) => {
      setCurrentProject(project)
      setCurrentScreen('app')
    },
    [setCurrentProject, setCurrentScreen]
  )

  const handleDeleteProject = useCallback(
    (id: string) => {
      deleteProject(id)
      saveProjectsToStorageNow()
      setDeletingProjectId(null)
      toast({
        title: 'Proyek dihapus',
        description: 'Proyek berhasil dihapus dari daftar.',
      })
    },
    [deleteProject, saveProjectsToStorageNow, toast]
  )

  const getCompletedCount = useCallback((project: Project) => {
    return project.database.filter((s) => s.status === 'done').length
  }, [])

  // Find the most recent project with progress (for "Lanjutkan" section)
  const lastProjectWithProgress = useMemo(() => {
    if (projects.length === 0) return null
    // Find the last project that has some progress but isn't complete
    const withProgress = projects.filter((p) => {
      const completed = p.database.filter((s) => s.status === 'done').length
      const total = p.database.length
      return completed > 0 && completed < total
    })
    // Return the most recent one (last in array)
    return withProgress.length > 0 ? withProgress[withProgress.length - 1] : null
  }, [projects])

  return (
    <div className="h-screen flex flex-col bg-[#1a0b2e] bg-[radial-gradient(#3b2263_1px,transparent_1px)] bg-[length:20px_20px]">
      {/* Header */}
      <header className="sticky top-0 z-10 border-b border-[#533485] bg-[#2a164a]/95 backdrop-blur-sm">
        <div className="mx-auto flex max-w-4xl items-center justify-between px-4 py-4 sm:px-6 sm:py-5">
          <div className="flex items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-[#d4af37]/20 sm:h-12 sm:w-12">
              <Camera className="h-5 w-5 text-[#d4af37] sm:h-6 sm:w-6" />
            </div>
            <div>
              <h1 className="text-xl font-bold tracking-wide text-[#d4af37] sm:text-2xl">
                SAATIRIL
              </h1>
              <p className="text-xs text-[#c4b5fd]/70 sm:text-sm">
                Sistem Auto Track Input, Raw Into Live
              </p>
            </div>
          </div>

          <Button
            onClick={handleCreateProject}
            className="bg-[#d4af37] text-[#1a0b2e] hover:bg-[#d4af37]/90 font-semibold shadow-md transition-all hover:shadow-lg active:scale-95"
            size="sm"
          >
            <Plus className="h-4 w-4" />
            <span className="hidden sm:inline">Buat Proyek Baru</span>
            <span className="sm:hidden">Baru</span>
          </Button>
        </div>
      </header>

      {/* Main Content */}
      <main className="flex-1 px-4 py-6 sm:px-6 sm:py-8">
        <div className="mx-auto max-w-4xl">

          {/* ── Lanjutkan Proyek Terakhir (Resume Last Project) ── */}
          {lastProjectWithProgress && (
            <div className="mb-6">
              <Card
                className="group cursor-pointer border-[#d4af37]/40 bg-gradient-to-r from-[#3b2263]/80 to-[#2a164a]/80 shadow-lg shadow-[#d4af37]/5 transition-all duration-200 hover:border-[#d4af37]/70 hover:shadow-xl hover:shadow-[#d4af37]/10 active:scale-[0.99]"
                onClick={() => handleOpenProject(lastProjectWithProgress)}
              >
                <CardContent className="py-4 sm:py-5">
                  <div className="flex items-center gap-3 sm:gap-4">
                    {/* Play Icon */}
                    <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-xl bg-[#d4af37]/20 transition-colors group-hover:bg-[#d4af37]/30 sm:h-14 sm:w-14">
                      <Play className="h-6 w-6 text-[#d4af37] sm:h-7 sm:w-7" />
                    </div>

                    {/* Project Info */}
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-2">
                        <h3 className="truncate text-sm font-bold text-[#d4af37] sm:text-base">
                          Lanjutkan Proyek
                        </h3>
                        <Badge className="border-0 bg-emerald-600/30 text-emerald-300 text-[10px] shrink-0">
                          <RotateCcw className="h-3 w-3 mr-0.5" />
                          Resume
                        </Badge>
                      </div>
                      <p className="truncate text-[#c4b5fd] font-semibold text-sm sm:text-base mt-0.5">
                        {lastProjectWithProgress.name}
                      </p>
                      <div className="mt-2 flex items-center gap-3">
                        <div className="flex items-center gap-1.5 text-xs text-[#c4b5fd]/70">
                          <Users className="h-3.5 w-3.5" />
                          <span>{lastProjectWithProgress.database.length} peserta</span>
                        </div>
                        <div className="flex items-center gap-1.5 text-xs text-emerald-400/80">
                          <CheckCircle2 className="h-3.5 w-3.5" />
                          <span>{getCompletedCount(lastProjectWithProgress)} selesai</span>
                        </div>
                        <div className="flex items-center gap-1.5 text-xs text-[#d4af37]/70">
                          <Clock className="h-3.5 w-3.5" />
                          <span>{lastProjectWithProgress.database.length - getCompletedCount(lastProjectWithProgress)} belum</span>
                        </div>
                      </div>
                      {/* Progress Bar */}
                      <div className="mt-2.5 flex items-center gap-2">
                        <div className="h-2 flex-1 rounded-full bg-[#2a164a] overflow-hidden">
                          <div
                            className="h-full rounded-full bg-gradient-to-r from-[#d4af37] to-emerald-500 transition-all duration-500"
                            style={{
                              width: `${lastProjectWithProgress.database.length > 0
                                ? Math.round((getCompletedCount(lastProjectWithProgress) / lastProjectWithProgress.database.length) * 100)
                                : 0}%`
                            }}
                          />
                        </div>
                        <span className="text-xs font-semibold text-[#d4af37] shrink-0">
                          {lastProjectWithProgress.database.length > 0
                            ? Math.round((getCompletedCount(lastProjectWithProgress) / lastProjectWithProgress.database.length) * 100)
                            : 0}%
                        </span>
                      </div>
                    </div>
                  </div>
                </CardContent>
              </Card>
            </div>
          )}

          {/* Section Title */}
          <div className="mb-4 flex items-center gap-2">
            <Sparkles className="h-5 w-5 text-[#d4af37]" />
            <h2 className="text-lg font-semibold text-[#c4b5fd]">
              Proyek Anda
            </h2>
            <Badge
              variant="outline"
              className="border-[#533485] bg-[#3b2263]/50 text-[#c4b5fd] ml-auto"
            >
              {projects.length} proyek
            </Badge>
          </div>

          {/* Auto-save Notice */}
          {projects.length > 0 && (
            <div className="mb-4 flex items-center gap-2 rounded-lg border border-[#533485]/50 bg-[#3b2263]/30 px-3 py-2">
              <CheckCircle2 className="h-4 w-4 text-emerald-400/70 shrink-0" />
              <p className="text-xs text-[#c4b5fd]/60">
                Progres proyek tersimpan otomatis — tutup dan buka kembali untuk melanjutkan dari posisi terakhir.
              </p>
            </div>
          )}

          {projects.length === 0 ? (
            /* Empty State */
            <Card className="border-[#533485] bg-[#2a164a]/80 shadow-lg">
              <CardContent className="flex flex-col items-center justify-center py-16 sm:py-24">
                <div className="mb-6 flex h-20 w-20 items-center justify-center rounded-full bg-[#3b2263]/60 sm:h-24 sm:w-24">
                  <Inbox className="h-10 w-10 text-[#c4b5fd]/40 sm:h-12 sm:w-12" />
                </div>
                <h3 className="mb-2 text-lg font-semibold text-[#c4b5fd] sm:text-xl">
                  Belum Ada Proyek
                </h3>
                <p className="mb-6 max-w-sm text-center text-sm text-[#c4b5fd]/60 sm:text-base">
                  Buat proyek baru untuk mulai mengelola acara fotografi Anda.
                </p>
                <Button
                  onClick={handleCreateProject}
                  className="bg-[#d4af37] text-[#1a0b2e] hover:bg-[#d4af37]/90 font-semibold shadow-md transition-all hover:shadow-lg active:scale-95"
                >
                  <Plus className="h-4 w-4" />
                  Buat Proyek Baru
                </Button>
              </CardContent>
            </Card>
          ) : (
            /* Project List */
            <div className="max-h-[calc(100vh-380px)] space-y-3 overflow-y-auto pr-1 sm:max-h-[calc(100vh-420px)] sm:space-y-4 sm:pr-2
              [&::-webkit-scrollbar]:w-2
              [&::-webkit-scrollbar-track]:rounded-full
              [&::-webkit-scrollbar-track]:bg-[#2a164a]
              [&::-webkit-scrollbar-thumb]:rounded-full
              [&::-webkit-scrollbar-thumb]:bg-[#533485]
              [&::-webkit-scrollbar-thumb]:hover:bg-[#d4af37]/60"
            >
              {projects.map((project) => {
                const completed = getCompletedCount(project)
                const total = project.database.length
                const progressPercent = total > 0 ? Math.round((completed / total) * 100) : 0
                const isInProgress = completed > 0 && completed < total
                const isComplete = completed > 0 && completed === total

                return (
                  <Card
                    key={project.id}
                    className={`group cursor-pointer border-[#533485] shadow-md transition-all duration-200 hover:shadow-lg active:scale-[0.99] ${
                      isInProgress
                        ? 'bg-[#3b2263]/70 hover:border-[#d4af37]/50 hover:bg-[#3b2263]/90'
                        : isComplete
                          ? 'bg-emerald-900/20 border-emerald-700/30 hover:border-emerald-500/50 hover:bg-emerald-900/30'
                          : 'bg-[#3b2263]/60 hover:border-[#d4af37]/50 hover:bg-[#3b2263]/90'
                    }`}
                    onClick={() => handleOpenProject(project)}
                  >
                    <CardContent className="py-4 sm:py-5">
                      <div className="flex items-center gap-3 sm:gap-4">
                        {/* Folder Icon */}
                        <div className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-lg transition-colors sm:h-12 sm:w-12 ${
                          isInProgress
                            ? 'bg-[#d4af37]/15 group-hover:bg-[#d4af37]/25'
                            : isComplete
                              ? 'bg-emerald-500/15 group-hover:bg-emerald-500/25'
                              : 'bg-[#2a164a] group-hover:bg-[#d4af37]/20'
                        }`}>
                          {isComplete ? (
                            <CheckCircle2 className="h-5 w-5 text-emerald-400 group-hover:text-emerald-300 sm:h-6 sm:w-6" />
                          ) : (
                            <FolderOpen className="h-5 w-5 text-[#d4af37]/80 group-hover:text-[#d4af37] sm:h-6 sm:w-6" />
                          )}
                        </div>

                        {/* Project Info */}
                        <div className="min-w-0 flex-1">
                          <h3 className={`truncate text-sm font-semibold transition-colors sm:text-base ${
                            isInProgress
                              ? 'text-[#d4af37] group-hover:text-[#d4af37]'
                              : isComplete
                                ? 'text-emerald-300 group-hover:text-emerald-200'
                                : 'text-[#c4b5fd] group-hover:text-[#d4af37]'
                          }`}>
                            {project.name}
                          </h3>
                          <div className="mt-1 flex items-center gap-2 flex-wrap">
                            <Badge
                              variant="outline"
                              className={`border-[#533485] bg-[#2a164a]/60 text-[10px] sm:text-xs ${isPhotoshootMode(project.config.mode) ? 'text-emerald-400/80' : 'text-[#c4b5fd]/80'}`}
                            >
                              {isPhotoshootMode(project.config.mode)
                                ? (isDualMode(project.config.mode) ? 'Photoshoot 2 Cam' : 'Photoshoot')
                                : (isDualMode(project.config.mode) ? 'Dual Channel' : 'Single Channel')}
                            </Badge>
                            <Badge
                              variant="outline"
                              className="border-[#533485] bg-[#2a164a]/60 text-[#c4b5fd]/80 text-[10px] sm:text-xs"
                            >
                              {project.config.ratio}
                            </Badge>
                          </div>
                          {/* Progress Bar */}
                          {total > 0 && (
                            <div className="mt-2.5 flex items-center gap-2">
                              <div className="h-1.5 flex-1 rounded-full bg-[#2a164a] overflow-hidden">
                                <div
                                  className={`h-full rounded-full transition-all duration-500 ${
                                    isComplete
                                      ? 'bg-emerald-500'
                                      : 'bg-gradient-to-r from-[#d4af37] to-emerald-500'
                                  }`}
                                  style={{ width: `${progressPercent}%` }}
                                />
                              </div>
                              <span className={`text-[10px] font-semibold shrink-0 ${
                                isComplete ? 'text-emerald-400' : 'text-[#d4af37]/70'
                              }`}>
                                {progressPercent}%
                              </span>
                            </div>
                          )}
                        </div>

                        {/* Progress Badge */}
                        <Badge
                          className={`shrink-0 border-0 font-semibold text-xs shadow-sm transition-colors sm:text-sm ${
                            total === 0
                              ? 'bg-[#533485]/60 text-[#c4b5fd]/50'
                              : isComplete
                                ? 'bg-emerald-600/80 text-white'
                                : isInProgress
                                  ? 'bg-[#d4af37]/20 text-[#d4af37]'
                                  : 'bg-[#533485]/60 text-[#c4b5fd]/70'
                          }`}
                        >
                          {completed} / {total}
                        </Badge>

                        {/* Delete Button */}
                        <AlertDialog
                          open={deletingProjectId === project.id}
                          onOpenChange={(open) => {
                            if (!open) setDeletingProjectId(null)
                          }}
                        >
                          <AlertDialogTrigger asChild>
                            <Button
                              variant="ghost"
                              size="icon"
                              className="h-8 w-8 shrink-0 text-[#c4b5fd]/40 opacity-0 transition-all hover:bg-red-500/20 hover:text-red-400 group-hover:opacity-100 sm:h-9 sm:w-9"
                              onClick={(e) => {
                                e.stopPropagation()
                                setDeletingProjectId(project.id)
                              }}
                              aria-label={`Hapus proyek ${project.name}`}
                            >
                              <Trash2 className="h-4 w-4" />
                            </Button>
                          </AlertDialogTrigger>
                          <AlertDialogContent className="border-[#533485] bg-[#2a164a] text-[#c4b5fd]">
                            <AlertDialogHeader>
                              <AlertDialogTitle className="text-[#d4af37]">
                                Hapus Proyek
                              </AlertDialogTitle>
                              <AlertDialogDescription className="text-[#c4b5fd]/70">
                                Apakah Anda yakin ingin menghapus proyek{' '}
                                <span className="font-semibold text-[#c4b5fd]">
                                  &quot;{project.name}&quot;
                                </span>
                                ? Tindakan ini tidak dapat dibatalkan dan semua data
                                dalam proyek akan hilang.
                              </AlertDialogDescription>
                            </AlertDialogHeader>
                            <AlertDialogFooter>
                              <AlertDialogCancel className="border-[#533485] bg-[#3b2263] text-[#c4b5fd] hover:bg-[#3b2263]/80 hover:text-[#c4b5fd]">
                                Batal
                              </AlertDialogCancel>
                              <AlertDialogAction
                                className="bg-red-600 text-white hover:bg-red-700"
                                onClick={(e) => {
                                  e.stopPropagation()
                                  handleDeleteProject(project.id)
                                }}
                              >
                                Hapus
                              </AlertDialogAction>
                            </AlertDialogFooter>
                          </AlertDialogContent>
                        </AlertDialog>
                      </div>
                    </CardContent>
                  </Card>
                )
              })}
            </div>
          )}
        </div>
      </main>

      {/* Footer */}
      <footer className="mt-auto border-t border-[#533485]/50 bg-[#2a164a]/60 backdrop-blur-sm">
        <div className="mx-auto max-w-4xl space-y-0.5 px-4 py-2 sm:px-6 sm:py-2.5">
          <SaatirilFooterLines />
        </div>
      </footer>
    </div>
  )
}
