#!/usr/bin/env npx tsx
/**
 * SAATIRIL — License Code Generator Tool
 *
 * Generates activation codes for the SAATIRIL Electron app license system.
 * Uses the EXACT same algorithm as electron/license.ts to ensure compatibility.
 *
 * Usage:
 *   npx tsx tools/generate-license.ts <machineId> [--type <type>] [--expires <date>] [--year <year>]
 *
 * Examples:
 *   npx tsx tools/generate-license.ts abc123def456...
 *   npx tsx tools/generate-license.ts abc123def456... --type annual
 *   npx tsx tools/generate-license.ts abc123def456... --type annual --year 2027
 *   npx tsx tools/generate-license.ts abc123def456... --type event --expires 2026-03-15
 *   npx tsx tools/generate-license.ts abc123def456... --type event
 */

import * as crypto from 'crypto'

// ─── SAME SECRET as in electron/license.ts ────────────────────────────────
const LICENSE_SECRET = 'SAATIRIL-2026-HUMAS-UIN-ANTASARI-BANJARMASIN'

type LicenseType = 'trial' | 'event' | 'annual' | 'permanent'

// ─── ANSI Colors ──────────────────────────────────────────────────────────
const RESET = '\x1b[0m'
const BOLD = '\x1b[1m'
const DIM = '\x1b[2m'
const CYAN = '\x1b[36m'
const GREEN = '\x1b[32m'
const YELLOW = '\x1b[33m'
const RED = '\x1b[31m'
const WHITE = '\x1b[37m'
const BG_CYAN = '\x1b[46m'
const BG_GREEN = '\x1b[42m'
const BG_RED = '\x1b[41m'
const BRIGHT_CYAN = '\x1b[96m'
const BRIGHT_GREEN = '\x1b[92m'
const BRIGHT_YELLOW = '\x1b[93m'
const BRIGHT_RED = '\x1b[91m'
const BRIGHT_WHITE = '\x1b[97m'

// ─── Algorithm (MUST match electron/license.ts exactly) ───────────────────
function generateExpectedCode(
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

/**
 * Verify an activation code against a machine ID.
 * Mirrors the logic in electron/license.ts verifyActivationCode().
 */
function verifyActivationCode(
  machineId: string,
  activationCode: string
): { licenseType: LicenseType; expiresAt: string | null } | null {
  // Normalize the code (remove dashes, whitespace, uppercase)
  const normalized = activationCode.replace(/[-\s]/g, '').toUpperCase()
  if (normalized.length !== 16) return null

  const formatted = `${normalized.slice(0, 4)}-${normalized.slice(4, 8)}-${normalized.slice(8, 12)}-${normalized.slice(12, 16)}`

  // Try permanent first (most common for internal use)
  const permanentCode = generateExpectedCode(machineId, 'permanent', null)
  if (formatted === permanentCode) {
    return { licenseType: 'permanent', expiresAt: null }
  }

  // Try annual — check current year, next year, and year after
  const now = new Date()
  for (let yearOffset = 0; yearOffset <= 2; yearOffset++) {
    const year = now.getFullYear() + yearOffset
    const expiryDate = new Date(year, 11, 31, 23, 59, 59) // Dec 31
    const annualCode = generateExpectedCode(machineId, 'annual', expiryDate.toISOString())
    if (formatted === annualCode) {
      return { licenseType: 'annual', expiresAt: expiryDate.toISOString() }
    }
  }

  // Try event — check dates from today up to 90 days ahead
  for (let dayOffset = 0; dayOffset <= 90; dayOffset++) {
    const date = new Date(now)
    date.setDate(date.getDate() + dayOffset)
    date.setHours(23, 59, 59, 0)
    const eventCode = generateExpectedCode(machineId, 'event', date.toISOString())
    if (formatted === eventCode) {
      return { licenseType: 'event', expiresAt: date.toISOString() }
    }
  }

  return null
}

// ─── Display Helpers ──────────────────────────────────────────────────────
function getDisplayMachineId(machineId: string): string {
  const short = machineId.substring(0, 12).toUpperCase()
  return `${short.slice(0, 4)}-${short.slice(4, 8)}-${short.slice(8, 12)}`
}

function formatDate(isoDate: string): string {
  const d = new Date(isoDate)
  return d.toLocaleDateString('en-US', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
  })
}

function padCenter(text: string, width: number): string {
  const stripped = text.replace(/\x1b\[[0-9;]*m/g, '') // strip ANSI for measurement
  const visibleLen = stripped.length
  const totalPad = width - visibleLen
  const leftPad = Math.max(0, Math.floor(totalPad / 2))
  const rightPad = Math.max(0, totalPad - leftPad)
  return ' '.repeat(leftPad) + text + ' '.repeat(rightPad)
}

// ─── CLI Argument Parsing ─────────────────────────────────────────────────
interface CliArgs {
  machineId: string
  licenseType: LicenseType
  expiresAt: string | null
  error?: string
}

function parseArgs(argv: string[]): CliArgs {
  const args = argv.slice(2)

  if (args.length === 0 || args[0] === '--help' || args[0] === '-h') {
    return {
      machineId: '',
      licenseType: 'permanent',
      expiresAt: null,
      error: 'HELP',
    }
  }

  const machineId = args[0]

  // Parse flags
  let licenseType: LicenseType = 'permanent'
  let expiresDate: string | null = null
  let targetYear: number | null = null

  for (let i = 1; i < args.length; i++) {
    const arg = args[i]
    if (arg === '--type' && args[i + 1]) {
      const type = args[i + 1].toLowerCase()
      if (!['permanent', 'annual', 'event', 'trial'].includes(type)) {
        return {
          machineId,
          licenseType: 'permanent',
          expiresAt: null,
          error: `Invalid license type: "${args[i + 1]}". Use: permanent, annual, event, trial`,
        }
      }
      licenseType = type as LicenseType
      i++
    } else if (arg === '--expires' && args[i + 1]) {
      expiresDate = args[i + 1]
      i++
    } else if (arg === '--year' && args[i + 1]) {
      const year = parseInt(args[i + 1], 10)
      if (isNaN(year) || year < 2020 || year > 2100) {
        return {
          machineId,
          licenseType: 'permanent',
          expiresAt: null,
          error: `Invalid year: "${args[i + 1]}". Use a year between 2020-2100`,
        }
      }
      targetYear = year
      i++
    }
  }

  // Validate machine ID (should be 64 hex chars)
  if (!/^[a-f0-9]{64}$/i.test(machineId)) {
    return {
      machineId,
      licenseType,
      expiresAt: null,
      error: `Invalid Machine ID: must be a 64-character hex string (SHA-256), got ${machineId.length} chars`,
    }
  }

  // Compute expiry based on type
  let expiresAt: string | null = null

  switch (licenseType) {
    case 'permanent':
      expiresAt = null
      break

    case 'annual': {
      const year = targetYear ?? new Date().getFullYear()
      const expiryDate = new Date(year, 11, 31, 23, 59, 59)
      expiresAt = expiryDate.toISOString()
      break
    }

    case 'event': {
      if (expiresDate) {
        const d = new Date(expiresDate + 'T23:59:59')
        if (isNaN(d.getTime())) {
          return {
            machineId,
            licenseType,
            expiresAt: null,
            error: `Invalid date format: "${expiresDate}". Use YYYY-MM-DD`,
          }
        }
        // Use the date with time set to end of day (ms=0 for consistency with verification)
        d.setHours(23, 59, 59, 0)
        expiresAt = d.toISOString()
      } else {
        // Default: 90 days from now (ms=0 for consistency with verification)
        const d = new Date()
        d.setDate(d.getDate() + 90)
        d.setHours(23, 59, 59, 0)
        expiresAt = d.toISOString()
      }
      break
    }

    case 'trial': {
      const d = new Date()
      d.setDate(d.getDate() + 7)
      d.setHours(23, 59, 59, 0)
      expiresAt = d.toISOString()
      break
    }
  }

  return { machineId, licenseType, expiresAt }
}

// ─── Print Help ───────────────────────────────────────────────────────────
function printHelp(): void {
  const boxWidth = 56
  const innerWidth = boxWidth - 4

  console.log('')
  console.log(`${CYAN}╔${'═'.repeat(boxWidth - 2)}╗${RESET}`)
  console.log(`${CYAN}║${padCenter(`${BOLD}${BRIGHT_CYAN}SAATIRIL — License Code Generator${RESET}`, innerWidth + 20)}${CYAN}║${RESET}`)
  console.log(`${CYAN}╚${'═'.repeat(boxWidth - 2)}╝${RESET}`)
  console.log('')
  console.log(`${BOLD}USAGE${RESET}`)
  console.log(`  npx tsx tools/generate-license.ts <machineId> [options]`)
  console.log('')
  console.log(`${BOLD}ARGUMENTS${RESET}`)
  console.log(`  ${BRIGHT_CYAN}machineId${RESET}    The full 64-char hex Machine ID from the SAATIRIL app`)
  console.log('')
  console.log(`${BOLD}OPTIONS${RESET}`)
  console.log(`  ${BRIGHT_CYAN}--type${RESET} <type>      License type (default: permanent)`)
  console.log(`  ${BRIGHT_CYAN}--expires${RESET} <date>   Expiry date for event type (YYYY-MM-DD, default: 90 days)`)
  console.log(`  ${BRIGHT_CYAN}--year${RESET} <year>     Target year for annual type (default: current year)`)
  console.log(`  ${BRIGHT_CYAN}--help${RESET}            Show this help message`)
  console.log('')
  console.log(`${BOLD}LICENSE TYPES${RESET}`)
  console.log(`  ${BRIGHT_GREEN}permanent${RESET}  Never expires`)
  console.log(`  ${BRIGHT_YELLOW}annual${RESET}     Expires Dec 31 of the specified year`)
  console.log(`  ${BRIGHT_YELLOW}event${RESET}      Expires at end of the specified date`)
  console.log(`  ${DIM}trial${RESET}       Same as event, 7 days from now (legacy)`)
  console.log('')
  console.log(`${BOLD}EXAMPLES${RESET}`)
  console.log(`  ${DIM}# Generate permanent license${RESET}`)
  console.log(`  npx tsx tools/generate-license.ts abc123def456...`)
  console.log('')
  console.log(`  ${DIM}# Generate annual license (expires Dec 31 this year)${RESET}`)
  console.log(`  npx tsx tools/generate-license.ts abc123def456... --type annual`)
  console.log('')
  console.log(`  ${DIM}# Generate annual license for specific year${RESET}`)
  console.log(`  npx tsx tools/generate-license.ts abc123def456... --type annual --year 2027`)
  console.log('')
  console.log(`  ${DIM}# Generate event license (expires specific date)${RESET}`)
  console.log(`  npx tsx tools/generate-license.ts abc123def456... --type event --expires 2026-03-15`)
  console.log('')
  console.log(`  ${DIM}# Generate event license (expires 90 days from now)${RESET}`)
  console.log(`  npx tsx tools/generate-license.ts abc123def456... --type event`)
  console.log('')
}

// ─── Main ─────────────────────────────────────────────────────────────────
function main(): void {
  const parsed = parseArgs(process.argv)

  // Show help
  if (parsed.error === 'HELP') {
    printHelp()
    process.exit(0)
  }

  // Show error
  if (parsed.error) {
    console.log('')
    console.log(`${BRIGHT_RED}${BOLD}  ❌ ERROR${RESET}`)
    console.log(`  ${parsed.error}`)
    console.log('')
    console.log(`  Run with ${BRIGHT_CYAN}--help${RESET} for usage information.`)
    console.log('')
    process.exit(1)
  }

  const { machineId, licenseType, expiresAt } = parsed
  const activationCode = generateExpectedCode(machineId, licenseType, expiresAt)
  const displayId = getDisplayMachineId(machineId)

  // Verify the code using the same algorithm as the Electron app
  const verification = verifyActivationCode(machineId, activationCode)
  const isVerified = verification !== null

  // Determine status label and color
  const typeLabel = licenseType.toUpperCase()
  let typeColor = BRIGHT_GREEN
  if (licenseType === 'annual') typeColor = BRIGHT_YELLOW
  if (licenseType === 'event') typeColor = BRIGHT_YELLOW
  if (licenseType === 'trial') typeColor = DIM

  // Format expiry display
  let expiryDisplay = 'Never'
  if (expiresAt) {
    expiryDisplay = formatDate(expiresAt)
  }

  // ─── Render output ────────────────────────────────────────────────────
  console.log('')
  console.log(`${CYAN}╔══════════════════════════════════════════════════╗${RESET}`)
  console.log(`${CYAN}║${padCenter(`${BOLD}${BRIGHT_CYAN}SAATIRIL — License Code Generator${RESET}`, 56 + 20)}${CYAN}║${RESET}`)
  console.log(`${CYAN}╚══════════════════════════════════════════════════╝${RESET}`)
  console.log('')
  console.log(`  ${DIM}Machine ID:${RESET}    ${BOLD}${machineId.substring(0, 16).toUpperCase()}...${RESET} ${DIM}(${machineId.length} chars)${RESET}`)
  console.log(`  ${DIM}Display ID:${RESET}    ${BOLD}${displayId}${RESET}`)
  console.log(`  ${DIM}License Type:${RESET}  ${typeColor}${BOLD}${typeLabel}${RESET}`)
  console.log(`  ${DIM}Expires At:${RESET}    ${expiresAt ? BRIGHT_YELLOW + formatDate(expiresAt) : BRIGHT_GREEN + BOLD + 'Never'}${RESET}`)
  if (expiresAt) {
    const now = new Date()
    const expiry = new Date(expiresAt)
    const daysRemaining = Math.ceil((expiry.getTime() - now.getTime()) / (1000 * 60 * 60 * 24))
    const daysColor = daysRemaining > 30 ? BRIGHT_GREEN : daysRemaining > 7 ? BRIGHT_YELLOW : BRIGHT_RED
    console.log(`  ${DIM}Days Left:${RESET}     ${daysColor}${BOLD}${daysRemaining} days${RESET}`)
  }
  console.log('')
  console.log(`  ${BOLD}Generated Activation Code:${RESET}`)
  console.log(`${CYAN}  ████████████████████████████████████████████████${RESET}`)
  console.log(`${CYAN}  █${RESET}  ${BOLD}${BRIGHT_WHITE}${activationCode}${RESET}                          ${CYAN}█${RESET}`)
  console.log(`${CYAN}  ████████████████████████████████████████████████${RESET}`)
  console.log('')

  // Verification result
  if (isVerified) {
    console.log(`  ${BG_GREEN}${BOLD}${BRIGHT_WHITE} ✅ VERIFICATION: PASSED ${RESET}`)
    console.log(`  ${DIM}The code was verified using the same algorithm as the Electron app.${RESET}`)
    if (verification) {
      console.log(`  ${DIM}Detected as: ${verification.licenseType}${verification.expiresAt ? `, expires ${formatDate(verification.expiresAt)}` : ', permanent'}${RESET}`)
    }
  } else {
    console.log(`  ${BG_RED}${BOLD}${BRIGHT_WHITE} ❌ VERIFICATION: FAILED ${RESET}`)
    console.log(`  ${BRIGHT_RED}The generated code could not be verified! Check the algorithm.${RESET}`)
  }

  console.log('')

  // Verification command
  console.log(`  ${DIM}To verify this code later:${RESET}`)
  console.log(`  ${BRIGHT_CYAN}npx tsx tools/generate-license.ts ${machineId.substring(0, 12)}... --type ${licenseType}${expiresAt ? ` --expires ${expiresAt.split('T')[0]}` : ''}${RESET}`)
  console.log('')

  // Instructions
  console.log(`  ${DIM}Give this code to the user. They can enter it in the${RESET}`)
  console.log(`  ${DIM}SAATIRIL app activation screen to unlock the software.${RESET}`)
  console.log('')

  // Exit with error code if verification failed
  if (!isVerified) {
    process.exit(1)
  }
}

main()
