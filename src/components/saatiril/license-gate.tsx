'use client'

import { useCallback, useEffect, useState } from 'react'
import {
  Check,
  Copy,
  KeyRound,
  Loader2,
  Lock,
  ShieldCheck,
  Infinity,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'

// ─── Theme ─────────────────────────────────────────────────────────────────
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

// ─── Types ─────────────────────────────────────────────────────────────────
interface LicenseStatusResult {
  isValid: boolean
  isGracePeriod: boolean
  isExpired: boolean
  daysRemaining: number
  graceDaysRemaining: number
  licenseType: string | null
  expiresAt: string | null
  machineId: string
  displayMachineId: string
  firstRunDate: string | null
}

interface Props {
  onLicenseValid: () => void
}

// ─── Component ─────────────────────────────────────────────────────────────
export function LicenseGate({ onLicenseValid }: Props) {
  const [status, setStatus] = useState<LicenseStatusResult | null>(null)
  const [loading, setLoading] = useState(true)
  const [activating, setActivating] = useState(false)
  const [activationCode, setActivationCode] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [success, setSuccess] = useState(false)
  const [copiedId, setCopiedId] = useState(false)

  // ── Check license status on mount ──────────────────────────────────────
  useEffect(() => {
    checkStatus()
  }, [])

  const checkStatus = useCallback(async () => {
    const api = window.saatirilAPI
    if (!api?.isElectron) {
      // Not running in Electron (web/sandbox mode) — bypass license check
      onLicenseValid()
      return
    }

    try {
      const result = await api.getLicenseStatus()
      setStatus(result)

      if (result.isValid && !result.isGracePeriod) {
        // Fully licensed — proceed
        // Don't call onLicenseValid immediately; let user see the success screen briefly
      }
    } catch (err) {
      console.error('[SAATIRIL LICENSE] Failed to check status:', err)
    } finally {
      setLoading(false)
    }
  }, [onLicenseValid])

  // ── Auto-proceed when valid ────────────────────────────────────────────
  useEffect(() => {
    if (success) {
      const timer = setTimeout(() => onLicenseValid(), 1500)
      return () => clearTimeout(timer)
    }
  }, [success, onLicenseValid])

  // ── Copy Machine ID to clipboard ───────────────────────────────────────
  const handleCopyId = useCallback(async () => {
    if (!status?.machineId) return
    try {
      await navigator.clipboard.writeText(status.machineId)
      setCopiedId(true)
      setTimeout(() => setCopiedId(false), 2000)
    } catch {
      // Fallback
      const textarea = document.createElement('textarea')
      textarea.value = status.machineId
      document.body.appendChild(textarea)
      textarea.select()
      document.execCommand('copy')
      document.body.removeChild(textarea)
      setCopiedId(true)
      setTimeout(() => setCopiedId(false), 2000)
    }
  }, [status?.machineId])

  // ── Activate license ───────────────────────────────────────────────────
  const handleActivate = useCallback(async () => {
    if (!activationCode.trim()) {
      setError('Masukkan kode aktivasi terlebih dahulu.')
      return
    }

    setActivating(true)
    setError(null)

    try {
      const api = window.saatirilAPI
      const result = await api!.activateLicense(activationCode.trim())

      if (result.success) {
        setSuccess(true)
        // Refresh status
        await checkStatus()
      } else {
        setError(result.error || 'Kode aktivasi tidak valid.')
      }
    } catch (err: any) {
      setError('Terjadi kesalahan saat aktivasi. Coba lagi.')
    } finally {
      setActivating(false)
    }
  }, [activationCode, checkStatus])

  // ── Format activation code as user types (auto-dash) ───────────────────
  const handleCodeChange = useCallback((value: string) => {
    // Remove non-alphanumeric
    const clean = value.replace(/[^A-Za-z0-9]/g, '').toUpperCase()
    // Add dashes every 4 chars
    let formatted = ''
    for (let i = 0; i < clean.length && i < 16; i++) {
      if (i > 0 && i % 4 === 0) formatted += '-'
      formatted += clean[i]
    }
    setActivationCode(formatted)
    setError(null)
  }, [])

  // ── Loading screen ─────────────────────────────────────────────────────
  if (loading) {
    return (
      <div
        className="flex h-dvh flex-col items-center justify-center gap-4 px-6"
        style={{ backgroundColor: THEME.bg }}
      >
        <Loader2 className="size-12 animate-spin" style={{ color: THEME.gold }} />
        <p className="text-sm font-medium" style={{ color: THEME.muted }}>
          Memverifikasi lisensi...
        </p>
      </div>
    )
  }

  // ── Success screen ─────────────────────────────────────────────────────
  if (success) {
    return (
      <div
        className="flex h-dvh flex-col items-center justify-center gap-4 px-6"
        style={{ backgroundColor: THEME.bg }}
      >
        <div
          className="flex size-20 items-center justify-center rounded-full"
          style={{ backgroundColor: `${THEME.emerald}22` }}
        >
          <ShieldCheck className="size-10" style={{ color: THEME.emerald }} />
        </div>
        <h2 className="text-xl font-bold text-white">Lisensi Diaktifkan!</h2>
        <p className="text-sm" style={{ color: THEME.muted }}>
          Memuat aplikasi...
        </p>
      </div>
    )
  }

  // Grace period is disabled — always require activation code

  // ── Main activation UI ─────────────────────────────────────────────────
  return (
    <div
      className="flex h-dvh flex-col items-center justify-center gap-0 px-4 py-6 sm:px-6"
      style={{ backgroundColor: THEME.bg }}
    >
      <div className="w-full max-w-lg">
        {/* ── Logo / Header ──────────────────────────────────────────────── */}
        <div className="mb-8 text-center">
          <div className="mb-4 flex justify-center">
            <div
              className="flex size-16 items-center justify-center rounded-2xl"
              style={{ backgroundColor: `${THEME.gold}15`, borderWidth: 1, borderColor: `${THEME.gold}33` }}
            >
              <Lock className="size-8" style={{ color: THEME.gold }} />
            </div>
          </div>
          <h1 className="text-2xl font-bold text-white sm:text-3xl">SAATIRIL</h1>
          <p className="mt-1 text-xs tracking-widest uppercase" style={{ color: THEME.gold }}>
            Sistem Auto Track Input, Raw Into Live
          </p>
        </div>

        {/* ── No License Warning (replaces grace period) ──────────────── */}
        {status?.isExpired && !status?.licenseType && (
          <div
            className="mb-6 rounded-lg border p-3"
            style={{
              backgroundColor: `${THEME.red}11`,
              borderColor: `${THEME.red}44`,
            }}
          >
            <div className="flex items-start gap-2">
              <Lock className="mt-0.5 size-4 shrink-0" style={{ color: THEME.red }} />
              <div>
                <p className="text-sm font-semibold" style={{ color: THEME.red }}>
                  Aplikasi Terkunci
                </p>
                <p className="mt-0.5 text-xs" style={{ color: THEME.muted }}>
                  Masukkan kode aktivasi untuk membuka SAATIRIL.
                  Hubungi pengembang untuk mendapatkan kode.
                </p>
              </div>
            </div>
          </div>
        )}

        {/* ── Active License Info ────────────────────────────────────────── */}
        {status?.isValid && status?.licenseType && (
          <div
            className="mb-6 rounded-lg border p-3"
            style={{
              backgroundColor: `${THEME.emerald}11`,
              borderColor: `${THEME.emerald}44`,
            }}
          >
            <div className="flex items-start gap-2">
              <ShieldCheck className="mt-0.5 size-4 shrink-0" style={{ color: THEME.emerald }} />
              <div>
                <p className="text-sm font-semibold" style={{ color: THEME.emerald }}>
                  Lisensi Aktif — {getLicenseTypeLabel(status.licenseType)}
                </p>
                {status.expiresAt && (
                  <p className="mt-0.5 text-xs" style={{ color: THEME.muted }}>
                    Berlaku hingga: {new Date(status.expiresAt).toLocaleDateString('id-ID', { year: 'numeric', month: 'long', day: 'numeric' })}
                  </p>
                )}
                {!status.expiresAt && (
                  <p className="mt-0.5 text-xs" style={{ color: THEME.muted }}>
                    Berlaku selamanya (Permanent)
                  </p>
                )}
                <Button
                  size="sm"
                  className="mt-2 h-7 text-xs"
                  style={{ backgroundColor: THEME.emerald, color: 'white' }}
                  onClick={onLicenseValid}
                >
                  Masuk ke Aplikasi
                </Button>
              </div>
            </div>
          </div>
        )}

        {/* ── Machine ID Display ─────────────────────────────────────────── */}
        <div
          className="mb-6 rounded-lg border p-4"
          style={{
            backgroundColor: THEME.panel,
            borderColor: THEME.border,
          }}
        >
          <div className="mb-2 flex items-center justify-between">
            <label className="text-xs font-semibold uppercase tracking-wider" style={{ color: THEME.muted }}>
              ID Perangkat (Machine ID)
            </label>
            <button
              onClick={handleCopyId}
              className="flex items-center gap-1 rounded px-2 py-0.5 text-[10px] font-medium transition-colors hover:bg-white/10"
              style={{ color: THEME.gold }}
              title="Salin Machine ID lengkap"
            >
              {copiedId ? (
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
          <div
            className="rounded px-3 py-2 font-mono text-sm font-bold tracking-wider"
            style={{
              backgroundColor: `${THEME.bg}`,
              color: THEME.gold,
              borderWidth: 1,
              borderColor: `${THEME.border}66`,
            }}
          >
            {status?.displayMachineId || '-----'}
          </div>
          <p className="mt-2 text-[10px] leading-relaxed" style={{ color: `${THEME.muted}99` }}>
            Kirim ID ini ke pengembang SAATIRIL untuk mendapatkan kode aktivasi.
            Klik &ldquo;Salin&rdquo; untuk menyalin ID lengkap ke clipboard.
          </p>
        </div>

        {/* ── Activation Code Input ──────────────────────────────────────── */}
        <div
          className="mb-4 rounded-lg border p-4"
          style={{
            backgroundColor: THEME.panel,
            borderColor: THEME.border,
          }}
        >
          <label className="mb-2 block text-xs font-semibold uppercase tracking-wider" style={{ color: THEME.muted }}>
            Kode Aktivasi
          </label>
          <div className="flex gap-2">
            <div className="relative flex-1">
              <KeyRound
                className="absolute left-3 top-1/2 size-4 -translate-y-1/2"
                style={{ color: `${THEME.muted}88` }}
              />
              <Input
                value={activationCode}
                onChange={(e) => handleCodeChange(e.target.value)}
                placeholder="XXXX-XXXX-XXXX-XXXX"
                className="h-10 pl-9 font-mono text-sm font-bold tracking-widest uppercase"
                style={{
                  backgroundColor: THEME.bg,
                  borderColor: THEME.border,
                  color: THEME.gold,
                }}
                maxLength={19} // XXXX-XXXX-XXXX-XXXX
                onKeyDown={(e) => {
                  if (e.key === 'Enter') handleActivate()
                }}
              />
            </div>
            <Button
              onClick={handleActivate}
              disabled={activating || activationCode.replace(/[-\s]/g, '').length < 16}
              className="h-10 shrink-0 px-4 font-semibold"
              style={{
                backgroundColor: THEME.gold,
                color: THEME.bg,
              }}
            >
              {activating ? (
                <Loader2 className="size-4 animate-spin" />
              ) : (
                'Aktivasi'
              )}
            </Button>
          </div>

          {/* Error message */}
          {error && (
            <p className="mt-2 text-xs font-medium" style={{ color: THEME.red }}>
              {error}
            </p>
          )}
        </div>

        {/* ── Help text ──────────────────────────────────────────────────── */}
        <div className="text-center">
          <p className="text-[10px] leading-relaxed" style={{ color: `${THEME.muted}88` }}>
            Hubungi pengembang SAATIRIL untuk mendapatkan kode aktivasi.
            <br />
            Setiap kode hanya berlaku untuk satu perangkat.
          </p>
        </div>
      </div>
    </div>
  )
}

// ─── Helpers ───────────────────────────────────────────────────────────────
function getLicenseTypeLabel(type: string): string {
  switch (type) {
    case 'permanent': return 'Permanent'
    case 'annual': return 'Tahunan'
    case 'event': return 'Acara'
    case 'trial': return 'Percobaan'
    default: return type
  }
}

export default LicenseGate
