#!/usr/bin/env npx tsx
/**
 * SAATIRIL — License Code Generator Tool
 *
 * Generates MONTHLY activation codes for the SAATIRIL Electron app license system.
 * Every code is valid for 30 days. After expiry, user must request a new code.
 * Uses the EXACT same algorithm as electron/license.ts to ensure compatibility.
 *
 * Usage:
 *   npx tsx tools/generate-license.ts <machineId>
 *
 * Examples:
 *   npx tsx tools/generate-license.ts abc123def456...
 */

import * as crypto from 'crypto'

// ─── SAME SECRET as in electron/license.ts ────────────────────────────────
const LICENSE_SECRET = 'SAATIRIL-2026-HUMAS-UIN-ANTASARI-BANJARMASIN'

type LicenseType = 'monthly'

// ─── ANSI Colors ──────────────────────────────────────────────────────────
const RESET = '\x1b[0m'
const BOLD = '\x1b[1m'
const DIM = '\x1b[2m'
const CYAN = '\x1b[36m'
const YELLOW = '\x1b[33m'
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
  expiresAt: string
  error?: string
}

function parseArgs(argv: string[]): CliArgs {
  const args = argv.slice(2)

  if (args.length === 0 || args[0] === '--help' || args[0] === '-h') {
    return {
      machineId: '',
      expiresAt: '',
      error: 'HELP',
    }
  }

  const machineId = args[0]

  // Validate machine ID (should be 64 hex chars)
  if (!/^[a-f0-9]{64}$/i.test(machineId)) {
    return {
      machineId,
      expiresAt: '',
      error: `Invalid Machine ID: must be a 64-character hex string (SHA-256), got ${machineId.length} chars`,
    }
  }

  // Monthly license: 30 days from now
  const d = new Date()
  d.setDate(d.getDate() + 30)
  d.setHours(23, 59, 59, 0) // ms=0 for consistent hashing with verification
  const expiresAt = d.toISOString()

  return { machineId, expiresAt }
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
  console.log(`  npx tsx tools/generate-license.ts <machineId>`)
  console.log('')
  console.log(`${BOLD}ARGUMENTS${RESET}`)
  console.log(`  ${BRIGHT_CYAN}machineId${RESET}    The full 64-char hex Machine ID from the SAATIRIL app`)
  console.log('')
  console.log(`${BOLD}LICENSE POLICY${RESET}`)
  console.log(`  ${BRIGHT_GREEN}monthly${RESET}     Valid for 30 days from generation date`)
  console.log(`  ${DIM}After expiry, user must request a new code from developer${RESET}`)
  console.log('')
  console.log(`${BOLD}EXAMPLES${RESET}`)
  console.log(`  ${DIM}# Generate monthly license (30 days from now)${RESET}`)
  console.log(`  npx tsx tools/generate-license.ts abc123def456...`)
  console.log('')
  console.log(`${BOLD}FLOW${RESET}`)
  console.log(`  1. User opens SAATIRIL → sees Machine ID → sends it to you`)
  console.log(`  2. You run this tool with their Machine ID`)
  console.log(`  3. You send the generated code to user`)
  console.log(`  4. User enters code → app unlocked for 30 days`)
  console.log(`  5. After 30 days → app locked again → repeat from step 1`)
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

  const { machineId, expiresAt } = parsed
  const activationCode = generateExpectedCode(machineId, 'monthly', expiresAt)
  const displayId = getDisplayMachineId(machineId)

  // Verify the code using the same algorithm as the Electron app
  const verification = verifyActivationCode(machineId, activationCode)
  const isVerified = verification !== null

  // Calculate days remaining
  const now = new Date()
  const expiry = new Date(expiresAt)
  const daysRemaining = Math.ceil((expiry.getTime() - now.getTime()) / (1000 * 60 * 60 * 24))
  const daysColor = daysRemaining > 14 ? BRIGHT_GREEN : daysRemaining > 7 ? BRIGHT_YELLOW : BRIGHT_RED

  // ─── Render output ────────────────────────────────────────────────────
  console.log('')
  console.log(`${CYAN}╔══════════════════════════════════════════════════╗${RESET}`)
  console.log(`${CYAN}║${padCenter(`${BOLD}${BRIGHT_CYAN}SAATIRIL — License Code Generator${RESET}`, 56 + 20)}${CYAN}║${RESET}`)
  console.log(`${CYAN}╚══════════════════════════════════════════════════╝${RESET}`)
  console.log('')
  console.log(`  ${DIM}Machine ID:${RESET}    ${BOLD}${machineId.substring(0, 16).toUpperCase()}...${RESET} ${DIM}(${machineId.length} chars)${RESET}`)
  console.log(`  ${DIM}Display ID:${RESET}    ${BOLD}${displayId}${RESET}`)
  console.log(`  ${DIM}License Type:${RESET}  ${BRIGHT_GREEN}${BOLD}MONTHLY${RESET}`)
  console.log(`  ${DIM}Expires At:${RESET}    ${BRIGHT_YELLOW}${formatDate(expiresAt)}${RESET}`)
  console.log(`  ${DIM}Days Left:${RESET}     ${daysColor}${BOLD}${daysRemaining} days${RESET}`)
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
      console.log(`  ${DIM}Detected as: ${verification.licenseType}, expires ${formatDate(verification.expiresAt)}${RESET}`)
    }
  } else {
    console.log(`  ${BG_RED}${BOLD}${BRIGHT_WHITE} ❌ VERIFICATION: FAILED ${RESET}`)
    console.log(`  ${BRIGHT_RED}The generated code could not be verified! Check the algorithm.${RESET}`)
  }

  console.log('')

  // Instructions
  console.log(`  ${DIM}Give this code to the user. They enter it in the SAATIRIL${RESET}`)
  console.log(`  ${DIM}activation screen to unlock the app for 30 days.${RESET}`)
  console.log(`  ${DIM}After 30 days, the app will lock and they must request${RESET}`)
  console.log(`  ${DIM}a new code from you.${RESET}`)
  console.log('')

  // Exit with error code if verification failed
  if (!isVerified) {
    process.exit(1)
  }
}

main()
