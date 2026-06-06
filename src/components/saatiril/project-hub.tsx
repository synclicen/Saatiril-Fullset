'use client'

import { useCallback, useState } from 'react'
import {
  FolderOpen,
  Plus,
  Trash2,
  Camera,
  Sparkles,
  Inbox,
} from 'lucide-react'
import {
  Card,
  CardContent,
} from '@/components/ui/card'
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
                Manajemen Acara Foto
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
          {/* Section Title */}
          <div className="mb-6 flex items-center gap-2">
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
            <div className="max-h-[calc(100vh-280px)] space-y-3 overflow-y-auto pr-1 sm:max-h-[calc(100vh-320px)] sm:space-y-4 sm:pr-2
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

                return (
                  <Card
                    key={project.id}
                    className="group cursor-pointer border-[#533485] bg-[#3b2263]/60 shadow-md transition-all duration-200 hover:border-[#d4af37]/50 hover:bg-[#3b2263]/90 hover:shadow-lg active:scale-[0.99]"
                    onClick={() => handleOpenProject(project)}
                  >
                    <CardContent className="flex items-center gap-3 py-4 sm:gap-4">
                      {/* Folder Icon */}
                      <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-[#2a164a] transition-colors group-hover:bg-[#d4af37]/20 sm:h-12 sm:w-12">
                        <FolderOpen className="h-5 w-5 text-[#d4af37]/80 group-hover:text-[#d4af37] sm:h-6 sm:w-6" />
                      </div>

                      {/* Project Info */}
                      <div className="min-w-0 flex-1">
                        <h3 className="truncate text-sm font-semibold text-[#c4b5fd] group-hover:text-[#d4af37] transition-colors sm:text-base">
                          {project.name}
                        </h3>
                        <div className="mt-1 flex items-center gap-2">
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
                      </div>

                      {/* Progress Badge */}
                      <Badge
                        className={`shrink-0 border-0 font-semibold text-xs shadow-sm transition-colors sm:text-sm ${
                          total === 0
                            ? 'bg-[#533485]/60 text-[#c4b5fd]/50'
                            : completed === total
                              ? 'bg-emerald-600/80 text-white'
                              : 'bg-[#d4af37]/20 text-[#d4af37]'
                        }`}
                      >
                        {completed} / {total} Selesai
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
                                "{project.name}"
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
        <div className="mx-auto max-w-4xl px-4 py-3 sm:px-6 sm:py-4">
          <p className="text-center text-[10px] text-[#c4b5fd]/40 sm:text-xs">
            Saatiril - Made by Fajrianor - Pusat Humas dan Keterbukaan Informasi
            2026
          </p>
        </div>
      </footer>
    </div>
  )
}
