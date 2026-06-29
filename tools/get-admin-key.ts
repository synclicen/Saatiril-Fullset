#!/usr/bin/env npx tsx
/**
 * SAATIRIL — Get Admin Key for Web License Generator
 *
 * Prints the admin key needed to access /admin license generator page.
 * Run this tool to get the key, then use it in the web interface.
 *
 * Usage:
 *   npx tsx tools/get-admin-key.ts
 */

import * as crypto from 'crypto'

const LICENSE_SECRET = 'SAATIRIL-2026-HUMAS-UIN-ANTASARI-BANJARMASIN'

const adminKey = crypto
  .createHash('sha256')
  .update(`${LICENSE_SECRET}:admin-api-key`)
  .digest('hex')
  .substring(0, 16)
  .toUpperCase()

console.log('')
console.log('╔══════════════════════════════════════════════════╗')
console.log('║    SAATIRIL — Admin Key for Web Generator       ║')
console.log('╚══════════════════════════════════════════════════╝')
console.log('')
console.log(`  Admin Key: ${adminKey}`)
console.log('')
console.log('  Gunakan key ini di halaman /admin untuk generate kode aktivasi.')
console.log('')
