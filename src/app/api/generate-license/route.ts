import { NextRequest, NextResponse } from 'next/server'
import * as crypto from 'crypto'

// Mark as force-dynamic for static export compatibility
export const dynamic = 'force-dynamic'

// ─── SAME SECRET as in electron/license.ts ────────────────────────────────
const LICENSE_SECRET = 'SAATIRIL-2026-HUMAS-UIN-ANTASARI-BANJARMASIN'

type LicenseType = 'monthly'

// ─── Algorithm (MUST match electron/license.ts exactly) ───────────────────
function generateExpectedCode(
  machineId: string,
  licenseType: LicenseType,
  expiresAt: string
): string {
  const expiryStr = new Date(expiresAt).getTime().toString(16)
  const input = `${machineId}:${licenseType}:${expiryStr}:${LICENSE_SECRET}`
  const hash = crypto.createHash('sha256').update(input).digest('hex')
  const code = hash.substring(0, 16).toUpperCase()
  return `${code.slice(0, 4)}-${code.slice(4, 8)}-${code.slice(8, 12)}-${code.slice(12, 16)}`
}

/**
 * Verify an activation code against a machine ID.
 * Mirrors the logic in electron/license.ts verifyActivationCode().
 */
function verifyActivationCode(
  machineId: string,
  activationCode: string
): { licenseType: LicenseType; expiresAt: string } | null {
  const normalized = activationCode.replace(/[-\s]/g, '').toUpperCase()
  if (normalized.length !== 16) return null

  const formatted = `${normalized.slice(0, 4)}-${normalized.slice(4, 8)}-${normalized.slice(8, 12)}-${normalized.slice(12, 16)}`

  // Only monthly license type — check dates from today up to 45 days ahead
  const now = new Date()
  for (let dayOffset = 0; dayOffset <= 45; dayOffset++) {
    const date = new Date(now)
    date.setDate(date.getDate() + dayOffset)
    date.setHours(23, 59, 59, 0) // ms=0 for consistent hashing
    const monthlyCode = generateExpectedCode(machineId, 'monthly', date.toISOString())
    if (formatted === monthlyCode) {
      return { licenseType: 'monthly', expiresAt: date.toISOString() }
    }
  }

  return null
}

function getDisplayMachineId(machineId: string): string {
  const short = machineId.substring(0, 12).toUpperCase()
  return `${short.slice(0, 4)}-${short.slice(4, 8)}-${short.slice(8, 12)}`
}

// ─── API Handler ───────────────────────────────────────────────────────────
export async function POST(request: NextRequest) {
  try {
    const body = await request.json()
    const { machineId, adminKey } = body

    // ── Admin key check (simple protection for the API) ───────────────────
    // The admin key is derived from the LICENSE_SECRET itself
    // so only someone with access to the source code can use this API
    const expectedAdminKey = crypto
      .createHash('sha256')
      .update(`${LICENSE_SECRET}:admin-api-key`)
      .digest('hex')
      .substring(0, 16)
      .toUpperCase()

    if (adminKey !== expectedAdminKey) {
      return NextResponse.json(
        { success: false, error: 'Admin key tidak valid. Akses ditolak.' },
        { status: 403 }
      )
    }

    // ── Validate Machine ID ───────────────────────────────────────────────
    if (!machineId || !/^[a-f0-9]{64}$/i.test(machineId)) {
      return NextResponse.json(
        {
          success: false,
          error: `Machine ID tidak valid. Harus 64 karakter hex (SHA-256). Diterima: ${machineId?.length || 0} karakter.`,
        },
        { status: 400 }
      )
    }

    // ── Generate monthly license (30 days from now) ───────────────────────
    const d = new Date()
    d.setDate(d.getDate() + 30)
    d.setHours(23, 59, 59, 0) // ms=0 for consistent hashing
    const expiresAt = d.toISOString()

    const activationCode = generateExpectedCode(machineId, 'monthly', expiresAt)
    const displayId = getDisplayMachineId(machineId)

    // ── Verify the code using the same algorithm as the Electron app ──────
    const verification = verifyActivationCode(machineId, activationCode)
    const isVerified = verification !== null

    // ── Calculate days remaining ──────────────────────────────────────────
    const now = new Date()
    const expiry = new Date(expiresAt)
    const daysRemaining = Math.ceil(
      (expiry.getTime() - now.getTime()) / (1000 * 60 * 60 * 24)
    )

    return NextResponse.json({
      success: true,
      data: {
        machineId,
        displayMachineId: displayId,
        licenseType: 'monthly',
        activationCode,
        expiresAt,
        expiresAtFormatted: expiry.toLocaleDateString('id-ID', {
          year: 'numeric',
          month: 'long',
          day: 'numeric',
        }),
        daysRemaining,
        verified: isVerified,
      },
    })
  } catch (error: any) {
    console.error('[SAATIRIL API] Generate license error:', error)
    return NextResponse.json(
      { success: false, error: 'Terjadi kesalahan server.' },
      { status: 500 }
    )
  }
}
