'use client'

import React, { useCallback, useRef, useState } from 'react'
import * as XLSX from 'xlsx'
import {
  ArrowLeft,
  Camera,
  FileSpreadsheet,
  FolderOpen,
  Frame,
  ImagePlus,
  Monitor,
  Upload,
  X,
} from 'lucide-react'

import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'

import { useSaatirilStore, type Student, stripFrameForSync } from '@/store/use-saatiril-store'
import { emitLocal } from '@/lib/socket'
import { useToast } from '@/hooks/use-toast'

// ─── Parsed data from Excel ────────────────────────────────────────────────────
interface ParsedExcelData {
  fileName: string
  students: Student[]
}

// ─── Component ─────────────────────────────────────────────────────────────────
export default function ProjectSetup() {
  const { toast } = useToast()
  const {
    addProject,
    setCurrentProject,
    setCurrentScreen,
    saveProjectsToStorageNow,
  } = useSaatirilStore()

  // ── Form state ─────────────────────────────────────────────────────────────
  const [projectName, setProjectName] = useState('')
  const [cameraMode, setCameraMode] = useState<'single' | 'dual'>('single')
  const [ratio, setRatio] = useState('4:3')
  const [preset, setPreset] = useState('original')
  const [targetFolder, setTargetFolder] = useState('C:\\SAATIRIL_System_Out')
  const [frameData, setFrameData] = useState<string | null>(null)
  const [frameFileName, setFrameFileName] = useState<string>('')

  // Excel data: single mode uses index 0; dual uses 0 (kiri) & 1 (kanan)
  const [excelData, setExcelData] = useState<[ParsedExcelData | null, ParsedExcelData | null]>([null, null])

  // Loading states for file parsing
  const [parsingExcel, setParsingExcel] = useState<[boolean, boolean]>([false, false])
  const [parsingFrame, setParsingFrame] = useState(false)

  // Refs for hidden file inputs
  const excelInputRef0 = useRef<HTMLInputElement>(null)
  const excelInputRef1 = useRef<HTMLInputElement>(null)
  const frameInputRef = useRef<HTMLInputElement>(null)

  // ── Excel parsing ──────────────────────────────────────────────────────────
  const parseExcelFile = useCallback(
    (file: File, channelIndex: number) => {
      setParsingExcel((prev) => {
        const next = [...prev] as [boolean, boolean]
        next[channelIndex] = true
        return next
      })

      const reader = new FileReader()
      reader.onload = (e) => {
        try {
          const data = new Uint8Array(e.target?.result as ArrayBuffer)
          const workbook = XLSX.read(data, { type: 'array' })
          const firstSheet = workbook.Sheets[workbook.SheetNames[0]]
          const jsonData = XLSX.utils.sheet_to_json<Record<string, unknown>>(firstSheet, {
            defval: '',
          })

          if (jsonData.length === 0) {
            toast({
              title: 'File Kosong',
              description: 'File Excel tidak memiliki data.',
              variant: 'destructive',
            })
            setParsingExcel((prev) => {
              const next = [...prev] as [boolean, boolean]
              next[channelIndex] = false
              return next
            })
            return
          }

          // Identify NIM and Nama columns
          const headers = Object.keys(jsonData[0])
          const nimCol = headers.find((h) => {
            const lower = h.toLowerCase().trim()
            return lower.includes('nim') || lower.includes('nis') || lower.includes('id')
          })
          const namaCol = headers.find((h) => {
            const lower = h.toLowerCase().trim()
            return lower.includes('nama') || lower.includes('name')
          })

          if (!nimCol || !namaCol) {
            toast({
              title: 'Kolom Tidak Ditemukan',
              description: `Tidak dapat menemukan kolom NIM/ID dan Nama. Kolom yang ditemukan: ${headers.join(', ')}`,
              variant: 'destructive',
            })
            setParsingExcel((prev) => {
              const next = [...prev] as [boolean, boolean]
              next[channelIndex] = false
              return next
            })
            return
          }

          const channelNumber = channelIndex + 1
          const students: Student[] = jsonData
            .filter((row) => {
              const nim = String(row[nimCol] ?? '').trim()
              const nama = String(row[namaCol] ?? '').trim()
              return nim !== '' && nama !== ''
            })
            .map((row) => ({
              id: `ID_${Date.now()}_${Math.random().toString(36).substring(2, 9)}`,
              nim: String(row[nimCol] ?? '').trim(),
              nama: String(row[namaCol] ?? '').trim(),
              status: 'pending' as const,
              assignedChannel: channelNumber,
            }))

          setExcelData((prev) => {
            const next = [...prev] as [ParsedExcelData | null, ParsedExcelData | null]
            next[channelIndex] = { fileName: file.name, students }
            return next
          })

          toast({
            title: 'Data Berhasil Dimuat',
            description: `${students.length} peserta terbaca dari "${file.name}"`,
          })
        } catch (err) {
          console.error('[SAATIRIL] Excel parse error:', err)
          toast({
            title: 'Gagal Membaca File',
            description: 'Pastikan file berformat .xlsx atau .xls yang valid.',
            variant: 'destructive',
          })
        } finally {
          setParsingExcel((prev) => {
            const next = [...prev] as [boolean, boolean]
            next[channelIndex] = false
            return next
          })
        }
      }
      reader.onerror = () => {
        toast({
          title: 'Gagal Membaca File',
          description: 'Terjadi kesalahan saat membaca file.',
          variant: 'destructive',
        })
        setParsingExcel((prev) => {
          const next = [...prev] as [boolean, boolean]
          next[channelIndex] = false
          return next
        })
      }
      reader.readAsArrayBuffer(file)
    },
    [toast],
  )

  // ── Frame overlay parsing ──────────────────────────────────────────────────
  const parseFrameFile = useCallback(
    (file: File) => {
      if (!file.name.toLowerCase().endsWith('.png')) {
        toast({
          title: 'Format Salah',
          description: 'Frame overlay harus berformat .PNG (transparan).',
          variant: 'destructive',
        })
        return
      }

      setParsingFrame(true)
      const reader = new FileReader()
      reader.onload = (e) => {
        const dataUrl = e.target?.result as string
        setFrameData(dataUrl)
        setFrameFileName(file.name)
        setParsingFrame(false)
        toast({
          title: 'Frame Dimuat',
          description: `"${file.name}" berhasil dimuat sebagai overlay.`,
        })
      }
      reader.onerror = () => {
        toast({
          title: 'Gagal Membaca Frame',
          description: 'Terjadi kesalahan saat membaca file frame.',
          variant: 'destructive',
        })
        setParsingFrame(false)
      }
      reader.readAsDataURL(file)
    },
    [toast],
  )

  // ── Drop zone handlers ─────────────────────────────────────────────────────
  const handleExcelDrop = useCallback(
    (e: React.DragEvent, channelIndex: number) => {
      e.preventDefault()
      e.stopPropagation()
      const file = e.dataTransfer.files[0]
      if (file) parseExcelFile(file, channelIndex)
    },
    [parseExcelFile],
  )

  const handleFrameDrop = useCallback(
    (e: React.DragEvent) => {
      e.preventDefault()
      e.stopPropagation()
      const file = e.dataTransfer.files[0]
      if (file) parseFrameFile(file)
    },
    [parseFrameFile],
  )

  const handleDragOver = useCallback((e: React.DragEvent) => {
    e.preventDefault()
    e.stopPropagation()
  }, [])

  // ── Form validation ────────────────────────────────────────────────────────
  const isNameValid = projectName.trim().length > 0
  const isSingleDataReady = cameraMode === 'single' && excelData[0] !== null && excelData[0]!.students.length > 0
  const isDualDataReady =
    cameraMode === 'dual' &&
    excelData[0] !== null &&
    excelData[0]!.students.length > 0 &&
    excelData[1] !== null &&
    excelData[1]!.students.length > 0
  const isDataReady = isSingleDataReady || isDualDataReady
  const canStart = isNameValid && isDataReady

  // ── Submit / create project ────────────────────────────────────────────────
  const handleCreateProject = useCallback(() => {
    if (!canStart) return

    const allStudents: Student[] = []

    if (cameraMode === 'single') {
      allStudents.push(...(excelData[0]?.students ?? []))
    } else {
      allStudents.push(...(excelData[0]?.students ?? []))
      allStudents.push(...(excelData[1]?.students ?? []))
    }

    // Re-generate unique IDs to avoid collision from parallel parsing
    const finalStudents: Student[] = allStudents.map((s, i) => ({
      ...s,
      id: `ID_${Date.now()}_${i}_${Math.random().toString(36).substring(2, 9)}`,
    }))

    const projectId = `PRJ_${Date.now()}_${Math.random().toString(36).substring(2, 9)}`

    // Create subfolder based on project name under target folder
    const sanitizedName = projectName.trim().replace(/[^a-zA-Z0-9_\-\s]/g, '').replace(/\s+/g, '_')
    const finalTargetFolder = targetFolder
      ? `${targetFolder.replace(/[\\/]+$/, '')}\\${sanitizedName}`
      : `C:\\SAATIRIL_System_Out\\${sanitizedName}`

    const project = {
      id: projectId,
      name: projectName.trim(),
      config: {
        mode: cameraMode,
        ratio,
        preset,
        targetFolder: finalTargetFolder,
        frame: frameData,
      },
      database: finalStudents,
      photoHistory: [],
    }

    addProject(project)
    setCurrentProject(project)

    // IMMEDIATE save before navigation to prevent data loss on first launch
    saveProjectsToStorageNow()

    // ── Create project folder on disk (Electron only) ────────────────────
    const api = window.saatirilAPI
    if (api?.createFolder) {
      api.createFolder(finalTargetFolder).then((result: { success: boolean; path?: string; error?: string }) => {
        if (result.success) {
          console.log(`[SAATIRIL] ✅ Project folder created: ${result.path}`)
          toast({
            title: 'Proyek Dibuat!',
            description: `"${projectName.trim()}" — ${finalStudents.length} peserta dimuat. Folder: ${finalTargetFolder}`,
          })
        } else {
          console.error('[SAATIRIL] Failed to create project folder:', result.error)
          toast({
            title: 'Proyek Dibuat!',
            description: `"${projectName.trim()}" — ${finalStudents.length} peserta. ⚠️ Folder gagal: ${result.error}`,
            variant: 'destructive',
          })
        }
      }).catch((err: Error) => {
        console.error('[SAATIRIL] createFolder IPC error:', err)
        toast({
          title: 'Proyek Dibuat!',
          description: `"${projectName.trim()}" — ${finalStudents.length} peserta dimuat.`,
        })
      })
    } else {
      toast({
        title: 'Proyek Dibuat!',
        description: `"${projectName.trim()}" — ${finalStudents.length} peserta dimuat.`,
      })
    }

    // Sync database over LAN — strip frame data to save bandwidth
    emitLocal('SYNC_DB', { project: stripFrameForSync(project) })

    // Small delay to ensure state is persisted before navigation
    setTimeout(() => {
      setCurrentScreen('app')
    }, 50)
  }, [
    canStart,
    cameraMode,
    excelData,
    projectName,
    ratio,
    preset,
    targetFolder,
    frameData,
    addProject,
    setCurrentProject,
    saveProjectsToStorageNow,
    setCurrentScreen,
    toast,
  ])

  // ── Upload zone component ──────────────────────────────────────────────────
  const renderUploadZone = (
    channelIndex: number,
    label: string,
    accentColor: string,
    accentBorder: string,
  ) => {
    const data = excelData[channelIndex]
    const isLoading = parsingExcel[channelIndex]
    const inputRef = channelIndex === 0 ? excelInputRef0 : excelInputRef1

    return (
      <div className="space-y-2">
        <Label className="text-sm font-medium" style={{ color: accentColor }}>
          {label}
        </Label>
        <div
          onDrop={(e) => handleExcelDrop(e, channelIndex)}
          onDragOver={handleDragOver}
          onClick={() => inputRef.current?.click()}
          className={`
            relative cursor-pointer rounded-lg border-2 border-dashed p-6
            transition-all duration-200
            hover:border-opacity-100 hover:bg-white/5
            ${data ? accentBorder : 'border-[#533485]'}
          `}
        >
          <input
            ref={inputRef}
            type="file"
            accept=".xlsx,.xls,.csv"
            className="hidden"
            onChange={(e) => {
              const file = e.target.files?.[0]
              if (file) parseExcelFile(file, channelIndex)
              // Reset so same file can be re-selected
              e.target.value = ''
            }}
          />

          <div className="flex flex-col items-center gap-2 text-center">
            {isLoading ? (
              <>
                <div className="h-8 w-8 animate-spin rounded-full border-2 border-[#533485] border-t-[#d4af37]" />
                <p className="text-xs text-[#c4b5fd]">Membaca file...</p>
              </>
            ) : data ? (
              <>
                <FileSpreadsheet className="h-8 w-8" style={{ color: accentColor }} />
                <p className="text-sm font-medium" style={{ color: accentColor }}>
                  Data siap: {data.students.length} Peserta Terbaca
                </p>
                <p className="text-xs text-[#c4b5fd]">{data.fileName}</p>
                <button
                  onClick={(e) => {
                    e.stopPropagation()
                    setExcelData((prev) => {
                      const next = [...prev] as [ParsedExcelData | null, ParsedExcelData | null]
                      next[channelIndex] = null
                      return next
                    })
                  }}
                  className="mt-1 rounded-full p-1 text-[#c4b5fd] transition-colors hover:bg-white/10 hover:text-red-400"
                >
                  <X className="h-4 w-4" />
                </button>
              </>
            ) : (
              <>
                <Upload className="h-8 w-8 text-[#533485]" />
                <p className="text-sm text-[#c4b5fd]">
                  Drag & drop file Excel di sini
                </p>
                <p className="text-xs text-[#533485]">.xlsx / .xls / .csv</p>
              </>
            )}
          </div>
        </div>
      </div>
    )
  }

  // ── Render ─────────────────────────────────────────────────────────────────
  return (
    <div className="relative h-screen bg-[#1a0b2e] backdrop-blur-sm overflow-hidden">
      {/* Subtle gradient overlay */}
      <div className="pointer-events-none absolute inset-0 bg-gradient-to-br from-[#1a0b2e] via-[#2a164a]/30 to-[#1a0b2e]" />

      <div className="relative z-10 flex h-full flex-col">
        {/* ── Header ─────────────────────────────────────────────────────────── */}
        <header className="flex items-center gap-3 border-b border-[#533485]/50 px-5 py-2.5">
          <Button
            variant="ghost"
            size="icon"
            onClick={() => setCurrentScreen('hub')}
            className="text-[#c4b5fd] hover:bg-white/10 hover:text-[#d4af37]"
          >
            <ArrowLeft className="h-4 w-4" />
          </Button>
          <div>
            <h1 className="text-base font-bold text-white">
              <span className="text-[#d4af37]">SAATIRIL</span> — Buat Proyek Baru
            </h1>
            <p className="text-xs text-[#c4b5fd]">
              Konfigurasi sistem fotografi event Anda
            </p>
          </div>
        </header>

        {/* ── Main Content: 2-Column Layout ──────────────────────────────────── */}
        <main className="flex-1 overflow-y-auto p-4">
          <div className="mx-auto grid max-w-6xl gap-4 lg:grid-cols-2">
            {/* ── LEFT COLUMN ──────────────────────────────────────────────── */}
            <div className="flex flex-col gap-3">
              {/* Project Name */}
              <Card className="border-[#533485] bg-[#2a164a] shadow-lg">
                <CardHeader className="pb-2">
                  <CardTitle className="flex items-center gap-2 text-base text-white">
                    <Monitor className="h-5 w-5 text-[#d4af37]" />
                    Nama Proyek
                  </CardTitle>
                </CardHeader>
                <CardContent>
                  <Input
                    placeholder="Masukkan nama event / proyek..."
                    value={projectName}
                    onChange={(e) => setProjectName(e.target.value)}
                    className="border-[#533485] bg-[#3b2263] text-white placeholder:text-[#533485] focus-visible:border-[#d4af37] focus-visible:ring-[#d4af37]/30"
                  />
                </CardContent>
              </Card>

              {/* Camera Mode */}
              <Card className="border-[#533485] bg-[#2a164a] shadow-lg">
                <CardHeader className="pb-2">
                  <CardTitle className="flex items-center gap-2 text-base text-white">
                    <Camera className="h-5 w-5 text-[#d4af37]" />
                    Mode Kamera
                  </CardTitle>
                </CardHeader>
                <CardContent className="space-y-3">
                  <Select
                    value={cameraMode}
                    onValueChange={(val) => {
                      setCameraMode(val as 'single' | 'dual')
                      // Reset kanan data when switching to single
                      if (val === 'single') {
                        setExcelData((prev) => [prev[0], null])
                      }
                    }}
                  >
                    <SelectTrigger className="w-full border-[#533485] bg-[#3b2263] text-white focus:ring-[#d4af37]/30">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent className="border-[#533485] bg-[#2a164a]">
                      <SelectItem value="single" className="text-white focus:bg-[#3b2263] focus:text-[#d4af37]">
                        Single Mode — 1 MC & 1 Kamera
                      </SelectItem>
                      <SelectItem value="dual" className="text-white focus:bg-[#3b2263] focus:text-[#d4af37]">
                        Dual Mode — 2 MC & 2 Kamera Bersamaan
                      </SelectItem>
                    </SelectContent>
                  </Select>

                  {cameraMode === 'single' ? (
                    <Badge
                      variant="outline"
                      className="border-[#d4af37]/40 text-[#d4af37]"
                    >
                      Single Channel
                    </Badge>
                  ) : (
                    <div className="flex gap-2">
                      <Badge
                        variant="outline"
                        className="border-[#d4af37]/40 text-[#d4af37]"
                      >
                        JALUR KIRI
                      </Badge>
                      <Badge
                        variant="outline"
                        className="border-cyan-400/40 text-cyan-400"
                      >
                        JALUR KANAN
                      </Badge>
                    </div>
                  )}
                </CardContent>
              </Card>

              {/* Excel Upload */}
              <Card className="border-[#533485] bg-[#2a164a] shadow-lg">
                <CardHeader className="pb-2">
                  <CardTitle className="flex items-center gap-2 text-base text-white">
                    <FileSpreadsheet className="h-5 w-5 text-[#d4af37]" />
                    Upload Data Peserta
                  </CardTitle>
                </CardHeader>
                <CardContent className="space-y-4">
                  {cameraMode === 'single' ? (
                    renderUploadZone(
                      0,
                      'Data Peserta',
                      '#d4af37',
                      'border-[#d4af37]/50',
                    )
                  ) : (
                    <div className="grid gap-4 sm:grid-cols-2">
                      {renderUploadZone(
                        0,
                        'Data JALUR KIRI',
                        '#d4af37',
                        'border-[#d4af37]/50',
                      )}
                      {renderUploadZone(
                        1,
                        'Data JALUR KANAN',
                        '#22d3ee',
                        'border-cyan-400/50',
                      )}
                    </div>
                  )}

                  <p className="text-xs text-[#533485]">
                    Kolom harus mengandung kata "nim"/"nis"/"id" untuk NIM
                    dan "nama"/"name" untuk Nama.
                  </p>
                </CardContent>
              </Card>
            </div>

            {/* ── RIGHT COLUMN ─────────────────────────────────────────────── */}
            <div className="flex flex-col gap-3">
              {/* Visual Camera Settings */}
              <Card className="border-[#533485] bg-[#2a164a] shadow-lg">
                <CardHeader className="pb-2">
                  <CardTitle className="flex items-center gap-2 text-base text-white">
                    <Camera className="h-5 w-5 text-[#d4af37]" />
                    Pengaturan Visual Kamera
                  </CardTitle>
                </CardHeader>
                <CardContent className="space-y-5">
                  {/* Photo Ratio */}
                  <div className="space-y-2">
                    <Label className="text-sm font-medium text-[#c4b5fd]">
                      Photo Ratio
                    </Label>
                    <Select value={ratio} onValueChange={setRatio}>
                      <SelectTrigger className="w-full border-[#533485] bg-[#3b2263] text-white focus:ring-[#d4af37]/30">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent className="border-[#533485] bg-[#2a164a]">
                        <SelectItem value="4:3" className="text-white focus:bg-[#3b2263] focus:text-[#d4af37]">
                          4:3 — Standard
                        </SelectItem>
                        <SelectItem value="16:9" className="text-white focus:bg-[#3b2263] focus:text-[#d4af37]">
                          16:9 — Widescreen
                        </SelectItem>
                        <SelectItem value="3:4" className="text-white focus:bg-[#3b2263] focus:text-[#d4af37]">
                          3:4 — Portrait
                        </SelectItem>
                        <SelectItem value="2:3" className="text-white focus:bg-[#3b2263] focus:text-[#d4af37]">
                          2:3 — Pass Photo Portrait
                        </SelectItem>
                        <SelectItem value="4:6" className="text-white focus:bg-[#3b2263] focus:text-[#d4af37]">
                          4:6 — Pass Photo Portrait
                        </SelectItem>
                      </SelectContent>
                    </Select>
                  </div>

                  {/* Filter Preset */}
                  <div className="space-y-2">
                    <Label className="text-sm font-medium text-[#c4b5fd]">
                      Filter Preset
                    </Label>
                    <Select value={preset} onValueChange={setPreset}>
                      <SelectTrigger className="w-full border-[#533485] bg-[#3b2263] text-white focus:ring-[#d4af37]/30">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent className="border-[#533485] bg-[#2a164a]">
                        <SelectItem value="original" className="text-white focus:bg-[#3b2263] focus:text-[#d4af37]">
                          Original Sensor — Tanpa Filter
                        </SelectItem>
                        <SelectItem value="studio" className="text-white focus:bg-[#3b2263] focus:text-[#d4af37]">
                          Studio Bright — Cahaya Studio Hangat
                        </SelectItem>
                        <SelectItem value="cinematic" className="text-white focus:bg-[#3b2263] focus:text-[#d4af37]">
                          Cinematic Gold — Tone Sinematik Emas
                        </SelectItem>
                        <SelectItem value="pro" className="text-white focus:bg-[#3b2263] focus:text-[#d4af37]">
                          Preset Pro — High Contrast + Sharpening
                        </SelectItem>
                        <SelectItem value="vivid" className="text-white focus:bg-[#3b2263] focus:text-[#d4af37]">
                          Vivid — Warna Cerah & Kontras Tinggi
                        </SelectItem>
                        <SelectItem value="softPortrait" className="text-white focus:bg-[#3b2263] focus:text-[#d4af37]">
                          Soft Portrait — Kulit Lembut & Hangat
                        </SelectItem>
                        <SelectItem value="classicFilm" className="text-white focus:bg-[#3b2263] focus:text-[#d4af37]">
                          Classic Film — Nuansa Film Vintage
                        </SelectItem>
                        <SelectItem value="dramaticBW" className="text-white focus:bg-[#3b2263] focus:text-[#d4af37]">
                          Dramatic B&W — Hitam Putih Dramatis
                        </SelectItem>
                        <SelectItem value="warmSunset" className="text-white focus:bg-[#3b2263] focus:text-[#d4af37]">
                          Warm Sunset — Tone Emas Sore Hari
                        </SelectItem>
                      </SelectContent>
                    </Select>
                  </div>

                  {/* Frame Overlay */}
                  <div className="space-y-2">
                    <Label className="flex items-center gap-2 text-sm font-medium text-[#c4b5fd]">
                      <Frame className="h-4 w-4" />
                      Frame Overlay (PNG Transparan)
                    </Label>
                    <div
                      onDrop={handleFrameDrop}
                      onDragOver={handleDragOver}
                      onClick={() => frameInputRef.current?.click()}
                      className={`
                        relative cursor-pointer rounded-lg border-2 border-dashed p-4
                        transition-all duration-200 hover:bg-white/5
                        ${frameData ? 'border-[#d4af37]/50' : 'border-[#533485]'}
                      `}
                    >
                      <input
                        ref={frameInputRef}
                        type="file"
                        accept=".png"
                        className="hidden"
                        onChange={(e) => {
                          const file = e.target.files?.[0]
                          if (file) parseFrameFile(file)
                          e.target.value = ''
                        }}
                      />

                      <div className="flex flex-col items-center gap-2">
                        {parsingFrame ? (
                          <div className="h-8 w-8 animate-spin rounded-full border-2 border-[#533485] border-t-[#d4af37]" />
                        ) : frameData ? (
                          <div className="flex flex-col items-center gap-2">
                            <div className="relative h-24 w-24 overflow-hidden rounded-md border border-[#533485]">
                                <img
                                src={frameData}
                                alt="Frame preview"
                                className="h-full w-full object-contain"
                              />
                            </div>
                            <p className="text-xs text-[#d4af37]">{frameFileName}</p>
                            <button
                              onClick={(e) => {
                                e.stopPropagation()
                                setFrameData(null)
                                setFrameFileName('')
                              }}
                              className="rounded-full p-1 text-[#c4b5fd] transition-colors hover:bg-white/10 hover:text-red-400"
                            >
                              <X className="h-4 w-4" />
                            </button>
                          </div>
                        ) : (
                          <>
                            <ImagePlus className="h-8 w-8 text-[#533485]" />
                            <p className="text-sm text-[#c4b5fd]">
                              Upload frame overlay PNG
                            </p>
                            <p className="text-xs text-[#533485]">.png (transparan)</p>
                          </>
                        )}
                      </div>
                    </div>
                  </div>
                </CardContent>
              </Card>

              {/* Target Folder */}
              <Card className="border-[#533485] bg-[#2a164a] shadow-lg">
                <CardHeader className="pb-2">
                  <CardTitle className="flex items-center gap-2 text-base text-white">
                    <FolderOpen className="h-5 w-5 text-[#d4af37]" />
                    Folder Tujuan Output
                  </CardTitle>
                </CardHeader>
                <CardContent>
                  <div className="flex gap-2">
                    <Input
                      readOnly
                      value={targetFolder}
                      className="border-[#533485] bg-[#3b2263] text-[#c4b5fd] selection:bg-[#d4af37]/30"
                    />
                    <Button
                      variant="outline"
                      className="shrink-0 border-[#533485] bg-[#3b2263] text-[#c4b5fd] hover:bg-[#533485]/30 hover:text-[#d4af37]"
                      onClick={async () => {
                        // Use Electron folder picker if available
                        const api = window.saatirilAPI
                        if (api?.selectFolder) {
                          const selected = await api.selectFolder(targetFolder)
                          if (selected) {
                            setTargetFolder(selected)
                            toast({
                              title: 'Folder Dipilih',
                              description: selected,
                            })
                          }
                        } else {
                          toast({
                            title: 'Tidak Tersedia',
                            description:
                              'Folder browsing hanya berfungsi pada versi .exe desktop.',
                          })
                        }
                      }}
                    >
                      Browse
                    </Button>
                  </div>
                  <p className="mt-2 text-xs text-[#533485]">
                    Subfolder berdasarkan nama proyek akan dibuat otomatis di dalam folder ini.
                  </p>
                  {projectName.trim() && targetFolder && (
                    <p className="mt-1 text-xs text-[#d4af37]/80">
                      → {targetFolder.replace(/[\\/]+$/, '')}\\{projectName.trim().replace(/[^a-zA-Z0-9_\-\s]/g, '').replace(/\s+/g, '_')}
                    </p>
                  )}
                </CardContent>
              </Card>
            </div>
          </div>
        </main>

        {/* ── Bottom: Start Button ─────────────────────────────────────────── */}
        <footer className="border-t border-[#533485]/50 px-5 py-3">
          <div className="mx-auto flex max-w-6xl items-center justify-between">
            <div className="text-sm text-[#c4b5fd]">
              {!isNameValid && (
                <span>Masukkan nama proyek untuk melanjutkan</span>
              )}
              {isNameValid && !isDataReady && (
                <span>Upload data peserta (Excel) untuk melanjutkan</span>
              )}
              {canStart && (
                <span className="text-[#d4af37]">
                  Siap memulai —{' '}
                  {cameraMode === 'single'
                    ? excelData[0]?.students.length
                    : (excelData[0]?.students.length ?? 0) +
                      (excelData[1]?.students.length ?? 0)}{' '}
                  peserta akan dimuat
                </span>
              )}
            </div>
            <Button
              disabled={!canStart}
              onClick={handleCreateProject}
              className={`
                px-8 py-3 text-base font-bold uppercase tracking-wider
                transition-all duration-300
                ${
                  canStart
                    ? 'bg-[#d4af37] text-[#1a0b2e] shadow-lg shadow-[#d4af37]/25 hover:bg-[#e5c44a] hover:shadow-[#d4af37]/40'
                    : 'bg-[#3b2263] text-[#533485] cursor-not-allowed'
                }
              `}
            >
              MULAI SISTEM SAATIRIL
            </Button>
          </div>
        </footer>
      </div>
    </div>
  )
}
