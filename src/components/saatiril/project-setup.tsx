'use client'

import React, { useCallback, useRef, useState } from 'react'
import * as XLSX from 'xlsx'
import {
  ArrowLeft,
  Camera,
  Check,
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

import { useSaatirilStore, type CameraMode, type Student, stripFrameForSync, isPhotoshootMode, isDualMode } from '@/store/use-saatiril-store'
import { emitLocal } from '@/lib/socket'
import { useToast } from '@/hooks/use-toast'

// ─── Parsed data from Excel ────────────────────────────────────────────────────
interface ParsedExcelData {
  fileName: string
  students: Student[]
}

// ─── Filter Preset options ─────────────────────────────────────────────────────
// CSS filter strings MUST stay in sync with PRESET_FILTERS in operator-panel.tsx
// so the preview thumbnails exactly match the live camera output.
export const PRESET_OPTIONS: Array<{
  value: string
  label: string
  desc: string
  filter: string
}> = [
  { value: 'original',    label: 'Original Sensor',  desc: 'Tanpa Filter',                 filter: 'none' },
  { value: 'studio',      label: 'Studio Bright',    desc: 'Cahaya Studio Hangat',         filter: 'brightness(1.1) contrast(1.05) saturate(1.1)' },
  { value: 'cinematic',   label: 'Cinematic Gold',   desc: 'Tone Sinematik Emas',          filter: 'sepia(0.15) contrast(1.1) brightness(0.95) saturate(1.3)' },
  { value: 'pro',         label: 'Preset Pro',       desc: 'High Contrast + Sharpening',   filter: 'contrast(1.25) brightness(1.05) saturate(1.15)' },
  { value: 'vivid',       label: 'Vivid',            desc: 'Warna Cerah & Kontras Tinggi', filter: 'brightness(1.08) contrast(1.12) saturate(1.45) hue-rotate(5deg)' },
  { value: 'softPortrait',label: 'Soft Portrait',    desc: 'Kulit Lembut & Hangat',        filter: 'brightness(1.12) contrast(0.92) saturate(1.08) sepia(0.08)' },
  { value: 'classicFilm', label: 'Classic Film',     desc: 'Nuansa Film Vintage',          filter: 'brightness(1.02) contrast(1.15) saturate(0.85) sepia(0.2)' },
  { value: 'dramaticBW',  label: 'Dramatic B&W',     desc: 'Hitam Putih Dramatis',         filter: 'brightness(1.05) contrast(1.35) saturate(0) grayscale(1)' },
  { value: 'warmSunset',  label: 'Warm Sunset',      desc: 'Tone Emas Sore Hari',          filter: 'brightness(1.06) contrast(1.08) saturate(1.3) sepia(0.18) hue-rotate(-10deg)' },
]

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
  const [cameraMode, setCameraMode] = useState<CameraMode>('single')
  const [ratio, setRatio] = useState('4:3')
  const [preset, setPreset] = useState('original')
  const [targetFolder, setTargetFolder] = useState('C:\\SAATIRIL_System_Out')
  const [frameData, setFrameData] = useState<string | null>(null)
  const [frameFileName, setFrameFileName] = useState<string>('')
  const [sessionPassword, setSessionPassword] = useState('')

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
  const isSingleDataReady = (cameraMode === 'single' || cameraMode === 'single-photoshoot' || cameraMode === 'dual-photoshoot') && excelData[0] !== null && excelData[0]!.students.length > 0
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

    if (cameraMode === 'dual') {
      allStudents.push(...(excelData[0]?.students ?? []))
      allStudents.push(...(excelData[1]?.students ?? []))
    } else {
      // single, single-photoshoot, dual-photoshoot: only 1 data upload
      allStudents.push(...(excelData[0]?.students ?? []))
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
        sessionPassword: sessionPassword.trim() || undefined,
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
    sessionPassword,
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

              {/* Session Password (Keamanan LAN) */}
              <Card className="border-[#533485] bg-[#2a164a] shadow-lg">
                <CardHeader className="pb-2">
                  <CardTitle className="flex items-center gap-2 text-base text-white">
                    <svg className="h-5 w-5 text-[#d4af37]" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect width="18" height="11" x="3" y="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>
                    Password Sesi LAN
                    <span className="ml-auto text-[10px] font-normal text-[#533485]">Opsional</span>
                  </CardTitle>
                </CardHeader>
                <CardContent>
                  <Input
                    type="text"
                    placeholder="Kosongkan = tanpa password"
                    value={sessionPassword}
                    onChange={(e) => setSessionPassword(e.target.value)}
                    className="border-[#533485] bg-[#3b2263] text-white placeholder:text-[#533485] focus-visible:border-[#d4af37] focus-visible:ring-[#d4af37]/30"
                  />
                  <p className="mt-2 text-xs text-[#533485]">
                    Jika diisi, Operator dan MC harus memasukkan password ini untuk bergabung ke sesi.
                    Mencegah perangkat tidak dikenal mengakses sesi foto Anda di jaringan LAN.
                  </p>
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
                      setCameraMode(val as CameraMode)
                      // Reset kanan data when switching away from dual
                      if (val !== 'dual') {
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
                      <SelectItem value="single-photoshoot" className="text-white focus:bg-[#3b2263] focus:text-[#d4af37]">
                        Single Photoshoot — 1 MC & 1 Kamera (Urutan Bebas)
                      </SelectItem>
                      <SelectItem value="dual-photoshoot" className="text-white focus:bg-[#3b2263] focus:text-[#d4af37]">
                        Dual Photoshoot — 1 MC & 2 Kamera Bersamaan (Urutan Bebas)
                      </SelectItem>
                    </SelectContent>
                  </Select>

                  {cameraMode === 'single' ? (
                    <Badge
                      variant="outline"
                      className="border-[#d4af37]/40 text-[#d4af37]"
                    >
                      Single Channel — Wisuda
                    </Badge>
                  ) : cameraMode === 'dual' ? (
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
                  ) : cameraMode === 'single-photoshoot' ? (
                    <Badge
                      variant="outline"
                      className="border-emerald-400/40 text-emerald-400"
                    >
                      Photoshoot — 1 Foto/Peserta
                    </Badge>
                  ) : (
                    <div className="flex gap-2">
                      <Badge
                        variant="outline"
                        className="border-emerald-400/40 text-emerald-400"
                      >
                        Photoshoot — 1 Foto/Peserta
                      </Badge>
                      <Badge
                        variant="outline"
                        className="border-cyan-400/40 text-cyan-400"
                      >
                        2 Kamera
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
                  {cameraMode === 'dual' ? (
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
                  ) : (
                    renderUploadZone(
                      0,
                      isPhotoshootMode(cameraMode) ? 'Data Peserta Photoshoot' : 'Data Peserta',
                      isPhotoshootMode(cameraMode) ? '#4ade80' : '#d4af37',
                      isPhotoshootMode(cameraMode) ? 'border-emerald-400/50' : 'border-[#d4af37]/50',
                    )
                  )}

                  <p className="text-xs text-[#533485]">
                    Kolom harus mengandung kata "nim"/"nis"/"id" untuk NIM
                    dan "nama"/"name" untuk Nama.
                    {isPhotoshootMode(cameraMode) && (
                      <span className="block mt-1 text-emerald-400/70">
                        Mode Photoshoot: hanya 1 foto per peserta, urutan bebas.
                      </span>
                    )}
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
                        <SelectItem value="9:16" className="text-white focus:bg-[#3b2263] focus:text-[#d4af37]">
                          9:16 — 16:9 Portrait
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

                  {/* Filter Preset — visual grid with live preview thumbnails */}
                  <div className="space-y-2">
                    <Label className="text-sm font-medium text-[#c4b5fd]">
                      Filter Preset
                    </Label>
                    <p className="text-xs text-white/50">
                      Klik preset untuk melihat contoh hasil foto. Preview menggunakan foto contoh yang sama agar perbedaan terlihat jelas.
                    </p>
                    <div className="grid grid-cols-3 gap-1.5 sm:grid-cols-5">
                      {PRESET_OPTIONS.map((opt) => {
                        const selected = preset === opt.value
                        return (
                          <button
                            key={opt.value}
                            type="button"
                            onClick={() => setPreset(opt.value)}
                            aria-pressed={selected}
                            className={`
                              group relative flex flex-col overflow-hidden rounded-md border text-left
                              transition-all duration-150 focus:outline-none focus:ring-2 focus:ring-[#d4af37]/40
                              ${selected
                                ? 'border-[#d4af37] bg-[#d4af37]/10 ring-1 ring-[#d4af37]/50'
                                : 'border-[#533485] bg-[#3b2263]/60 hover:border-[#d4af37]/50 hover:bg-[#3b2263]'}
                            `}
                          >
                            {/* Selected check badge */}
                            {selected && (
                              <span className="absolute right-1 top-1 z-10 flex h-4 w-4 items-center justify-center rounded-full bg-[#d4af37] text-[#2a164a] shadow">
                                <Check className="h-2.5 w-2.5" strokeWidth={3} />
                              </span>
                            )}

                            {/* Preview thumbnail — CSS filter matches operator live output */}
                            <div className="relative aspect-[3/4] w-full overflow-hidden bg-black/30">
                              <img
                                src="/samples/preset-base.jpg"
                                alt={`Contoh foto preset ${opt.label}`}
                                className="h-full w-full object-cover transition-transform duration-200 group-hover:scale-[1.03]"
                                style={{ filter: opt.filter }}
                                draggable={false}
                              />
                            </div>

                            {/* Label + description */}
                            <div className="flex flex-col gap-0 px-1.5 py-1">
                              <span
                                className={`truncate text-[10px] font-semibold leading-tight ${
                                  selected ? 'text-[#d4af37]' : 'text-white'
                                }`}
                              >
                                {opt.label}
                              </span>
                              <span className="truncate text-[8px] leading-tight text-white/50">
                                {opt.desc}
                              </span>
                            </div>
                          </button>
                        )
                      })}
                    </div>
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
                <span className={isPhotoshootMode(cameraMode) ? 'text-emerald-400' : 'text-[#d4af37]'}>
                  Siap memulai —{' '}
                  {cameraMode === 'dual'
                    ? (excelData[0]?.students.length ?? 0) +
                      (excelData[1]?.students.length ?? 0)
                    : excelData[0]?.students.length}{' '}
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
