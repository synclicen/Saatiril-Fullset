/**
 * SAATIRIL — License System (Level 1: Offline Activation)
 *
 * How it works:
 * 1. On first launch, generate a Machine ID from hardware fingerprint
 * 2. Display Machine ID to user → they send it to us
 * 3. We run the generator tool → produce an Activation Code
 * 4. User enters the code → app verifies it matches the Machine ID
 * 5. If valid, store signed license data locally
 * 6. On subsequent launches, verify the stored license
 *
 * Security:
 * - Machine ID = SHA256(cpuInfo + macAddress + hostname + platform + arch)
 * - Activation Code = SHA256(machineId + ":" + licenseType + ":" + expiry + ":" + SECRET)
 * - License data is HMAC-signed to prevent tampering
 * - No grace period — activation required immediately
 */

import * as crypto from 'crypto'
import * as fs from 'fs'
import * as os from 'os'
import * as path from 'path'
import { app } from 'electron'

// ─── Configuration ─────────────────────────────────────────────────────────
const GRACE_PERIOD_DAYS = 0 // No grace period — activation required immediately

// IMPORTANT: This secret is used for code generation AND verification.
// The same secret must be used in tools/generate-license.ts
// In production, this should be obfuscated or derived at runtime.
const LICENSE_SECRET = 'SAATIRIL-2026-HUMAS-UIN-ANTASARI-BANJARMASIN'

// ─── Types ─────────────────────────────────────────────────────────────────
export type LicenseType = 'trial' | 'event' | 'annual' | 'permanent'

export interface LicenseData {
  machineId: string
  activationCode: string
  licenseType: LicenseType
  activatedAt: string    // ISO date
  expiresAt: string | null // ISO date or null for permanent
  signature: string
}

export interface LicenseStatus {
  isValid: boolean
  isGracePeriod: boolean
  isExpired: boolean
  daysRemaining: number
  graceDaysRemaining: number
  licenseType: LicenseType | null
  expiresAt: string | null
  machineId: string
  displayMachineId: string // Shortened for display
  firstRunDate: string | null
}

// ─── Machine ID Generation ─────────────────────────────────────────────────
function getHardwareFingerprint(): string {
  const cpus = os.cpus()
  const cpuInfo = cpus.length > 0 ? cpus[0].model : 'unknown-cpu'
  const cpuCores = String(cpus.length)

  // Get first non-internal MAC address
  const nets = os.networkInterfaces()
  let macAddress = 'no-mac'
  for (const [, addrs] of Object.entries(nets)) {
    if (!addrs) continue
    for (const addr of addrs) {
      if (!addr.internal && addr.family === 'IPv4' && addr.mac && addr.mac !== '00:00:00:00:00:00') {
        macAddress = addr.mac
        break
      }
    }
    if (macAddress !== 'no-mac') break
  }

  const hostname = os.hostname()
  const platform = os.platform()
  const arch = os.arch()

  const raw = `${cpuInfo}|${cpuCores}|${macAddress}|${hostname}|${platform}|${arch}`
  return crypto.createHash('sha256').update(raw).digest('hex')
}

/**
 * Get the Machine ID for this computer.
 * This is a SHA-256 hash of hardware-specific information.
 */
export function getMachineId(): string {
  return getHardwareFingerprint()
}

/**
 * Get a short, display-friendly version of the Machine ID.
 * First 12 characters, formatted as XXXX-XXXX-XXXX
 */
export function getDisplayMachineId(machineId: string): string {
  const short = machineId.substring(0, 12).toUpperCase()
  return `${short.slice(0, 4)}-${short.slice(4, 8)}-${short.slice(8, 12)}`
}

// ─── Activation Code Verification ──────────────────────────────────────────
/**
 * Generate the expected activation code for a given machine ID and license params.
 * This is the SAME algorithm used in tools/generate-license.ts
 */
export function generateExpectedCode(
  machineId: string,
  licenseType: LicenseType,
  expiresAt: string | null
): string {
  const expiryStr = expiresAt ? new Date(expiresAt).getTime().toString(16) : '0'
  const input = `${machineId}:${licenseType}:${expiryStr}:${LICENSE_SECRET}`
  const hash = crypto.createHash('sha256').update(input).digest('hex')
  // Take first 16 chars, format as XXXX-XXXX-XXXX-XXXX
  const code = hash.substring(0, 16).toUpperCase()
  return `${code.slice(0, 4)}-${code.slice(4, 8)}-${code.slice(8, 12)}-${code.slice(12, 16)}`
}

/**
 * Verify an activation code against a machine ID.
 * Tries all license types and common expiry patterns.
 * Returns the license type and expiry if valid, null if invalid.
 */
export function verifyActivationCode(
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

  // Try annual — check current year and next year
  const now = new Date()
  for (let yearOffset = 0; yearOffset <= 2; yearOffset++) {
    const year = now.getFullYear() + yearOffset
    const expiryDate = new Date(year, 11, 31, 23, 59, 59, 0) // Dec 31 — ms=0 for consistent hashing
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

// ─── License Data Signing & Verification ───────────────────────────────────
function signLicenseData(data: Omit<LicenseData, 'signature'>): string {
  const payload = JSON.stringify({
    machineId: data.machineId,
    activationCode: data.activationCode,
    licenseType: data.licenseType,
    activatedAt: data.activatedAt,
    expiresAt: data.expiresAt,
  })
  return crypto.createHmac('sha256', LICENSE_SECRET).update(payload).digest('hex')
}

function verifyLicenseSignature(data: LicenseData): boolean {
  const expectedSig = signLicenseData({
    machineId: data.machineId,
    activationCode: data.activationCode,
    licenseType: data.licenseType,
    activatedAt: data.activatedAt,
    expiresAt: data.expiresAt,
  })
  return crypto.timingSafeEqual(
    Buffer.from(data.signature, 'hex'),
    Buffer.from(expectedSig, 'hex')
  )
}

// ─── License File Storage ──────────────────────────────────────────────────
function getLicenseFilePath(): string {
  return path.join(app.getPath('userData'), 'license.dat')
}

function getFirstRunFilePath(): string {
  return path.join(app.getPath('userData'), 'first-run.dat')
}

export function readLicenseFile(): LicenseData | null {
  try {
    const filePath = getLicenseFilePath()
    if (!fs.existsSync(filePath)) return null

    const encrypted = fs.readFileSync(filePath, 'utf-8')
    const data = JSON.parse(decryptData(encrypted)) as LicenseData

    // Verify signature
    if (!verifyLicenseSignature(data)) {
      console.warn('[SAATIRIL LICENSE] License file signature invalid — tampering detected')
      return null
    }

    // Verify machine ID matches current machine
    const currentMachineId = getMachineId()
    if (data.machineId !== currentMachineId) {
      console.warn('[SAATIRIL LICENSE] Machine ID mismatch — license tied to different machine')
      return null
    }

    return data
  } catch (err: any) {
    console.warn('[SAATIRIL LICENSE] Failed to read license file:', err.message)
    return null
  }
}

export function writeLicenseFile(data: LicenseData): boolean {
  try {
    const filePath = getLicenseFilePath()
    const dir = path.dirname(filePath)
    if (!fs.existsSync(dir)) {
      fs.mkdirSync(dir, { recursive: true })
    }

    const encrypted = encryptData(JSON.stringify(data))
    fs.writeFileSync(filePath, encrypted, 'utf-8')
    return true
  } catch (err: any) {
    console.error('[SAATIRIL LICENSE] Failed to write license file:', err.message)
    return false
  }
}

// ─── First Run Tracking (for grace period) ────────────────────────────────
export function getFirstRunDate(): string | null {
  try {
    const filePath = getFirstRunFilePath()
    if (!fs.existsSync(filePath)) return null
    const data = fs.readFileSync(filePath, 'utf-8')
    const parsed = JSON.parse(decryptData(data))
    return parsed.firstRunDate || null
  } catch {
    return null
  }
}

export function recordFirstRun(): string {
  const now = new Date().toISOString()
  const filePath = getFirstRunFilePath()
  const dir = path.dirname(filePath)
  if (!fs.existsSync(dir)) {
    fs.mkdirSync(dir, { recursive: true })
  }
  fs.writeFileSync(filePath, encryptData(JSON.stringify({ firstRunDate: now })), 'utf-8')
  return now
}

// ─── Simple Encryption/Decryption for local files ─────────────────────────
// Uses AES-256-CBC with a key derived from the machine ID
function getEncryptionKey(): { key: Buffer; iv: Buffer } {
  const machineId = getMachineId()
  const key = crypto.createHash('sha256').update(machineId + ':SAATIRIL-ENCRYPT').digest()
  const iv = crypto.createHash('md5').update(machineId + ':SAATIRIL-IV').digest()
  return { key, iv }
}

function encryptData(plaintext: string): string {
  const { key, iv } = getEncryptionKey()
  const cipher = crypto.createCipheriv('aes-256-cbc', key, iv)
  let encrypted = cipher.update(plaintext, 'utf-8', 'base64')
  encrypted += cipher.final('base64')
  return encrypted
}

function decryptData(ciphertext: string): string {
  const { key, iv } = getEncryptionKey()
  const decipher = crypto.createDecipheriv('aes-256-cbc', key, iv)
  let decrypted = decipher.update(ciphertext, 'base64', 'utf-8')
  decrypted += decipher.final('utf-8')
  return decrypted
}

// ─── License Status Check ──────────────────────────────────────────────────
export function checkLicenseStatus(): LicenseStatus {
  const machineId = getMachineId()
  const displayMachineId = getDisplayMachineId(machineId)

  // 1. Check if we have a valid license file
  const licenseData = readLicenseFile()

  if (licenseData) {
    // Re-verify the activation code
    const verification = verifyActivationCode(machineId, licenseData.activationCode)
    if (!verification) {
      // License file exists but code is invalid
      return {
        isValid: false,
        isGracePeriod: false,
        isExpired: true,
        daysRemaining: 0,
        graceDaysRemaining: 0,
        licenseType: null,
        expiresAt: null,
        machineId,
        displayMachineId,
        firstRunDate: getFirstRunDate(),
      }
    }

    // Check expiry
    const now = new Date()
    const isExpired = licenseData.expiresAt
      ? new Date(licenseData.expiresAt) < now
      : false

    const daysRemaining = licenseData.expiresAt
      ? Math.max(0, Math.ceil((new Date(licenseData.expiresAt).getTime() - now.getTime()) / (1000 * 60 * 60 * 24)))
      : Infinity

    return {
      isValid: !isExpired,
      isGracePeriod: false,
      isExpired,
      daysRemaining,
      graceDaysRemaining: 0,
      licenseType: licenseData.licenseType,
      expiresAt: licenseData.expiresAt,
      machineId,
      displayMachineId,
      firstRunDate: getFirstRunDate(),
    }
  }

  // 2. No license file — NO grace period, app is locked
  const firstRunDate = getFirstRunDate() || recordFirstRun()

  return {
    isValid: false, // Always locked without activation code
    isGracePeriod: false,
    isExpired: true, // Treated as expired = locked
    daysRemaining: 0,
    graceDaysRemaining: 0,
    licenseType: null,
    expiresAt: null,
    machineId,
    displayMachineId,
    firstRunDate,
  }
}

// ─── Activate License ──────────────────────────────────────────────────────
export function activateLicense(activationCode: string): { success: boolean; error?: string; licenseType?: LicenseType } {
  const machineId = getMachineId()

  // Verify the code
  const verification = verifyActivationCode(machineId, activationCode)
  if (!verification) {
    return { success: false, error: 'Kode aktivasi tidak valid untuk perangkat ini.' }
  }

  // Check if event license is already expired
  if (verification.expiresAt && new Date(verification.expiresAt) < new Date()) {
    return { success: false, error: 'Kode aktivasi sudah kadaluarsa.' }
  }

  // Create license data
  const licenseData: LicenseData = {
    machineId,
    activationCode: activationCode.replace(/[-\s]/g, '').toUpperCase().replace(/(.{4})(?=.)/g, '$1-'),
    licenseType: verification.licenseType,
    activatedAt: new Date().toISOString(),
    expiresAt: verification.expiresAt,
    signature: '', // Will be set below
  }

  // Sign the license data
  licenseData.signature = signLicenseData(licenseData)

  // Write to disk
  const written = writeLicenseFile(licenseData)
  if (!written) {
    return { success: false, error: 'Gagal menyimpan data lisensi ke disk.' }
  }

  console.log(`[SAATIRIL LICENSE] Activated: ${verification.licenseType}${verification.expiresAt ? ` (expires: ${verification.expiresAt})` : ' (permanent)'}`)
  return { success: true, licenseType: verification.licenseType }
}
