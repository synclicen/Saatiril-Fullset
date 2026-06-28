'use client'

import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  Copy,
  Check,
  KeyRound,
  Loader2,
  ShieldCheck,
  AlertTriangle,
  ArrowLeft,
} from 'lucide-react'

const THEME = {
  bg: '#1a0b2e',
  panel: '#2a164a',
  card: '#3b2263',
  border: '#533485',
  gold: '#d4af37',
  muted: '#c4b5fd',
  cyan: '#06b6d4',
  emerald: '#22c55e',
  red: '#ef4444',
} as const

interface GenerateResult {
  machineId: string
  displayMachineId: string
  licenseType: string
  activationCode: string
  expiresAt: string
  expiresAtFormatted: string
  daysRemaining: number
  verified: boolean
}

export default function AdminLicensePage() {
  const [machineId, setMachineId] = useState('')
  const [adminKey, setAdminKey] = useState('')
  const [loading, setLoading] = useState(false)
  const [result, setResult] = useState<GenerateResult | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [copiedCode, setCopiedCode] = useState(false)

  const handleGenerate = async () => {
    if (!machineId.trim()) {
      setError('Masukkan Machine ID terlebih dahulu.')
      return
    }

    if (!adminKey.trim()) {
      setError('Masukkan Admin Key terlebih dahulu.')
      return
    }

    setLoading(true)
    setError(null)
    setResult(null)

    try {
      const response = await fetch('/api/generate-license/', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          machineId: machineId.trim(),
          adminKey: adminKey.trim(),
        }),
      })

      const data = await response.json()

      if (!data.success) {
        setError(data.error || 'Gagal membuat kode aktivasi.')
        return
      }

      setResult(data.data)
    } catch (err: any) {
      setError('Terjadi kesalahan jaringan. Coba lagi.')
    } finally {
      setLoading(false)
    }
  }

  const handleCopyCode = async () => {
    if (!result?.activationCode) return
    try {
      await navigator.clipboard.writeText(result.activationCode)
      setCopiedCode(true)
      setTimeout(() => setCopiedCode(false), 2000)
    } catch {
      const textarea = document.createElement('textarea')
      textarea.value = result.activationCode
      document.body.appendChild(textarea)
      textarea.select()
      document.execCommand('copy')
      document.body.removeChild(textarea)
      setCopiedCode(true)
      setTimeout(() => setCopiedCode(false), 2000)
    }
  }

  return (
    <div
      className="min-h-dvh flex flex-col items-center justify-start px-4 py-8"
      style={{ backgroundColor: THEME.bg }}
    >
      <div className="w-full max-w-lg">
        {/* Header */}
        <div className="mb-8 text-center">
          <div className="mb-4 flex justify-center">
            <div
              className="flex size-16 items-center justify-center rounded-2xl"
              style={{
                backgroundColor: `${THEME.gold}15`,
                borderWidth: 1,
                borderColor: `${THEME.gold}33`,
              }}
            >
              <KeyRound className="size-8" style={{ color: THEME.gold }} />
            </div>
          </div>
          <h1 className="text-2xl font-bold text-white sm:text-3xl">
            Generator Kode Aktivasi
          </h1>
          <p className="mt-1 text-xs tracking-widest uppercase" style={{ color: THEME.gold }}>
            Panel Pengembang SAATIRIL
          </p>
        </div>

        {/* Back to app link */}
        <div className="mb-6">
          <a
            href="/"
            className="inline-flex items-center gap-1.5 text-xs font-medium transition-colors hover:opacity-80"
            style={{ color: THEME.muted }}
          >
            <ArrowLeft className="size-3" />
            Kembali ke Aplikasi
          </a>
        </div>

        {/* Admin Key Input */}
        <div
          className="mb-4 rounded-lg border p-4"
          style={{
            backgroundColor: THEME.panel,
            borderColor: THEME.border,
          }}
        >
          <label
            className="mb-2 block text-xs font-semibold uppercase tracking-wider"
            style={{ color: THEME.muted }}
          >
            Admin Key
          </label>
          <Input
            type="password"
            value={adminKey}
            onChange={(e) => {
              setAdminKey(e.target.value)
              setError(null)
            }}
            placeholder="Masukkan admin key..."
            className="h-10 font-mono text-sm"
            style={{
              backgroundColor: THEME.bg,
              borderColor: THEME.border,
              color: THEME.gold,
            }}
            onKeyDown={(e) => {
              if (e.key === 'Enter') handleGenerate()
            }}
          />
          <p className="mt-2 text-[10px] leading-relaxed" style={{ color: `${THEME.muted}88` }}>
            Admin key diperoleh dari file konfigurasi pengembang. Jika Anda tidak memiliki key ini,
            Anda tidak dapat membuat kode aktivasi.
          </p>
        </div>

        {/* Machine ID Input */}
        <div
          className="mb-4 rounded-lg border p-4"
          style={{
            backgroundColor: THEME.panel,
            borderColor: THEME.border,
          }}
        >
          <label
            className="mb-2 block text-xs font-semibold uppercase tracking-wider"
            style={{ color: THEME.muted }}
          >
            Machine ID User
          </label>
          <Input
            value={machineId}
            onChange={(e) => {
              setMachineId(e.target.value)
              setError(null)
            }}
            placeholder="Paste 64 karakter Machine ID dari user..."
            className="h-10 font-mono text-xs"
            style={{
              backgroundColor: THEME.bg,
              borderColor: THEME.border,
              color: THEME.gold,
            }}
            onKeyDown={(e) => {
              if (e.key === 'Enter') handleGenerate()
            }}
          />
          <p className="mt-2 text-[10px] leading-relaxed" style={{ color: `${THEME.muted}88` }}>
            Minta user untuk klik &ldquo;Salin ID Lengkap&rdquo; di layar aktivasi SAATIRIL,
            lalu kirimkan ID tersebut kepada Anda. Paste ID tersebut di sini.
          </p>
        </div>

        {/* Generate Button */}
        <Button
          onClick={handleGenerate}
          disabled={loading || !machineId.trim() || !adminKey.trim()}
          className="mb-4 h-11 w-full px-4 text-sm font-semibold"
          style={{
            backgroundColor: THEME.gold,
            color: THEME.bg,
          }}
        >
          {loading ? (
            <Loader2 className="mr-2 size-4 animate-spin" />
          ) : (
            <KeyRound className="mr-2 size-4" />
          )}
          Buat Kode Aktivasi
        </Button>

        {/* Error */}
        {error && (
          <div
            className="mb-4 rounded-lg border p-3"
            style={{
              backgroundColor: `${THEME.red}11`,
              borderColor: `${THEME.red}44`,
            }}
          >
            <div className="flex items-start gap-2">
              <AlertTriangle
                className="mt-0.5 size-4 shrink-0"
                style={{ color: THEME.red }}
              />
              <p className="text-sm font-medium" style={{ color: THEME.red }}>
                {error}
              </p>
            </div>
          </div>
        )}

        {/* Result */}
        {result && (
          <div
            className="rounded-lg border p-4"
            style={{
              backgroundColor: THEME.panel,
              borderColor: result.verified ? `${THEME.emerald}66` : `${THEME.red}66`,
            }}
          >
            {/* Verification Badge */}
            <div className="mb-4 flex items-center gap-2">
              {result.verified ? (
                <div
                  className="flex items-center gap-1.5 rounded-full px-3 py-1"
                  style={{ backgroundColor: `${THEME.emerald}22` }}
                >
                  <ShieldCheck className="size-4" style={{ color: THEME.emerald }} />
                  <span
                    className="text-xs font-semibold"
                    style={{ color: THEME.emerald }}
                  >
                    Kode Terverifikasi
                  </span>
                </div>
              ) : (
                <div
                  className="flex items-center gap-1.5 rounded-full px-3 py-1"
                  style={{ backgroundColor: `${THEME.red}22` }}
                >
                  <AlertTriangle className="size-4" style={{ color: THEME.red }} />
                  <span className="text-xs font-semibold" style={{ color: THEME.red }}>
                    Verifikasi Gagal
                  </span>
                </div>
              )}
            </div>

            {/* Details */}
            <div className="mb-4 space-y-2">
              <div className="flex justify-between text-xs">
                <span style={{ color: THEME.muted }}>Display ID:</span>
                <span className="font-mono font-bold" style={{ color: THEME.gold }}>
                  {result.displayMachineId}
                </span>
              </div>
              <div className="flex justify-between text-xs">
                <span style={{ color: THEME.muted }}>Tipe Lisensi:</span>
                <span className="font-semibold" style={{ color: THEME.emerald }}>
                  MONTHLY (1 Bulan)
                </span>
              </div>
              <div className="flex justify-between text-xs">
                <span style={{ color: THEME.muted }}>Berlaku Hingga:</span>
                <span className="font-semibold" style={{ color: THEME.cyan }}>
                  {result.expiresAtFormatted}
                </span>
              </div>
              <div className="flex justify-between text-xs">
                <span style={{ color: THEME.muted }}>Sisa Waktu:</span>
                <span className="font-semibold" style={{ color: THEME.emerald }}>
                  {result.daysRemaining} hari
                </span>
              </div>
            </div>

            {/* Activation Code */}
            <div className="mb-3">
              <label
                className="mb-2 block text-xs font-semibold uppercase tracking-wider"
                style={{ color: THEME.muted }}
              >
                Kode Aktivasi
              </label>
              <div
                className="flex items-center gap-2 rounded px-4 py-3"
                style={{
                  backgroundColor: THEME.bg,
                  borderWidth: 2,
                  borderColor: THEME.gold,
                }}
              >
                <span
                  className="flex-1 text-center font-mono text-xl font-black tracking-[0.3em]"
                  style={{ color: THEME.gold }}
                >
                  {result.activationCode}
                </span>
                <button
                  onClick={handleCopyCode}
                  className="flex items-center gap-1 rounded px-2 py-1 text-[10px] font-medium transition-colors hover:bg-white/10"
                  style={{ color: THEME.gold }}
                  title="Salin kode aktivasi"
                >
                  {copiedCode ? (
                    <>
                      <Check className="size-3" style={{ color: THEME.emerald }} />
                      <span style={{ color: THEME.emerald }}>Tersalin!</span>
                    </>
                  ) : (
                    <>
                      <Copy className="size-3" />
                      Salin
                    </>
                  )}
                </button>
              </div>
            </div>

            {/* Instructions */}
            <div
              className="rounded p-3"
              style={{ backgroundColor: `${THEME.cyan}11` }}
            >
              <p className="text-[10px] leading-relaxed" style={{ color: THEME.muted }}>
                <strong style={{ color: THEME.cyan }}>Cara Menggunakan:</strong> Salin kode di atas,
                kirimkan ke user (via WhatsApp/email/dll). User memasukkan kode tersebut di layar
                aktivasi SAATIRIL. Kode berlaku 30 hari sejak dibuat.
              </p>
            </div>
          </div>
        )}

        {/* Footer help */}
        <div className="mt-6 text-center">
          <p className="text-[10px] leading-relaxed" style={{ color: `${THEME.muted}66` }}>
            Halaman ini hanya untuk pengembang SAATIRIL.
            <br />
            Jika Anda adalah user, hubungi pengembang untuk mendapatkan kode aktivasi.
          </p>
        </div>
      </div>
    </div>
  )
}
