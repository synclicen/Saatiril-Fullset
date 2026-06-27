#!/usr/bin/env bun
/**
 * SAATIRIL — License Code Generator Tool
 *
 * Usage:
 *   bun run tools/generate-license.ts <machineId> <licenseType> [expiryDate]
 *
 * Examples:
 *   bun run tools/generate-license.ts A1B2C3D4E5F6... permanent
 *   bun run tools/generate-license.ts A1B2C3D4E5F6... annual
 *   bun run tools/generate-license.ts A1B2C3D4E5F6... event 2026-06-30
 *   bun run tools/generate-license.ts A1B2C3D4E5F6... event 2026-12-31
 *
 * The machineId is the FULL 64-char hex Machine ID displayed by the app.
 * The displayMachineId (XXXX-XXXX-XXXX) is for human readability —
 * you need the FULL machineId to generate a code.
 *
 * License Types:
 *   permanent  — Never expires
 *   annual     — Expires at end of the specified year (default: current year)
 *   event      — Expires at end of the specified date
 *   trial      — Same as event, 7 days from now
 */

import * as crypto from 'crypto'

// ─── SAME SECRET as in electron/license.ts ────────────────────────────────
const LICENSE_SECRET = 'SAATIRIL-2026-HUMAS-UIN-ANTASARI-BANJARMASIN'

type LicenseType = 'trial' | 'event' | 'annual' | 'permanent'

function generateActivationCode(
  machineId: string,
  licenseType: LicenseType,
  expiresAt: string | null
): string {
  const expiryStr = expiresAt ? new Date(expiresAt).getTime().toString(16) : '0'
  const input = `${machineId}:${licenseType}:${expiryStr}:${LICENSE_SECRET}`
  const hash = crypto.createHash('sha256').update(input).digest('hex')
  const code = hash.substring(0, 16).toUpperCase()
  return `${code.slice(0, 4)}-${code.slice(4, 8)}-${code.slice(8, 12)}-${code.slice(12, 16)}`
}

function getExpiryForType(licenseType: LicenseType, dateArg?: string): string | null {
  switch (licenseType) {
    case 'permanent':
      return null
    case 'annual': {
      const year = dateArg ? parseInt(dateArg, 10) : new Date().getFullYear()
      return new Date(year, 11, 31, 23, 59, 59).toISOString()
    }
    case 'event': {
      if (!dateArg) {
        // Default: end of today
        const today = new Date()
        today.setHours(23, 59, 59)
        return today.toISOString()
      }
      const d = new Date(dateArg)
      if (isNaN(d.getTime())) {
        console.error('Invalid date format. Use YYYY-MM-DD')
        process.exit(1)
      }
      d.setHours(23, 59, 59)
      return d.toISOString()
    }
    case 'trial': {
      const now = new Date()
      now.setDate(now.getDate() + 7)
      now.setHours(23, 59, 59)
      return now.toISOString()
    }
  }
}

// ─── Main ──────────────────────────────────────────────────────────────────
const args = process.argv.slice(2)

if (args.length < 2) {
  console.log(`
╔══════════════════════════════════════════════════════════════╗
║           SAATIRIL — License Code Generator                 ║
╠══════════════════════════════════════════════════════════════╣
║                                                              ║
║  Usage:                                                      ║
║    bun run tools/generate-license.ts <machineId> <type> [date]║
║                                                              ║
║  Types:                                                      ║
║    permanent  — Never expires                                ║
║    annual     — Expires Dec 31 of given year                 ║
║    event      — Expires end of given date                    ║
║    trial      — Expires 7 days from now                      ║
║                                                              ║
║  Examples:                                                   ║
║    bun run tools/generate-license.ts abc123... permanent     ║
║    bun run tools/generate-license.ts abc123... annual 2026   ║
║    bun run tools/generate-license.ts abc123... event 2026-06-30║
║                                                              ║
╚══════════════════════════════════════════════════════════════╝
`)
  process.exit(0)
}

const machineId = args[0]
const licenseType = args[1] as LicenseType
const dateArg = args[2]

// Validate machine ID (should be 64 hex chars)
if (!/^[a-f0-9]{64}$/i.test(machineId)) {
  console.error('❌ Machine ID harus berupa 64 karakter hex (SHA-256)')
  process.exit(1)
}

// Validate license type
if (!['permanent', 'annual', 'event', 'trial'].includes(licenseType)) {
  console.error('❌ Tipe lisensi tidak valid. Gunakan: permanent, annual, event, trial')
  process.exit(1)
}

const expiresAt = getExpiryForType(licenseType, dateArg)
const activationCode = generateActivationCode(machineId, licenseType, expiresAt)

// Display Machine ID (shortened)
const displayId = machineId.substring(0, 12).toUpperCase()
const formattedDisplayId = `${displayId.slice(0, 4)}-${displayId.slice(4, 8)}-${displayId.slice(8, 12)}`

console.log('')
console.log('╔══════════════════════════════════════════════════════════════╗')
console.log('║           SAATIRIL — License Code Generated                ║')
console.log('╠══════════════════════════════════════════════════════════════╣')
console.log(`║  Machine ID (short):  ${formattedDisplayId}                          ║`)
console.log(`║  License Type:        ${licenseType.padEnd(40)}║`)
console.log(`║  Expires At:          ${(expiresAt ? new Date(expiresAt).toLocaleDateString('id-ID') : 'Tidak pernah (Permanent)').padEnd(40)}║`)
console.log('║                                                              ║')
console.log(`║  Activation Code:                                             ║`)
console.log(`║    ${activationCode}                                        ║`)
console.log('║                                                              ║')
console.log('║  Berikan kode ini kepada pengguna untuk dimasukkan           ║')
console.log('║  ke aplikasi SAATIRIL.                                       ║')
console.log('╚══════════════════════════════════════════════════════════════╝')
console.log('')
