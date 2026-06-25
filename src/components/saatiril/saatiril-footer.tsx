'use client'

import type { CSSProperties } from 'react'

interface SaatirilFooterLinesProps {
  /** Wrapper className applied to the lines container */
  className?: string
  /** Wrapper inline style — kept for API compat; per-line colors are set explicitly below */
  style?: CSSProperties
}

// ── Theme palette (matches app THEME) ──────────────────────────────────────
const COLORS = {
  gold: '#d4af37',      // brand accent — copyright
  muted: '#c4b5fd',     // primary text — tagline
  cyan: '#06b6d4',      // secondary accent — institution
  goldSoft: '#a88b2d',  // dimmer gold — university
} as const

/**
 * Branding footer for the Saatiril system.
 *
 * Renders the official 4-line credit block. Each line uses a distinctly
 * different color from the app theme so the visual hierarchy reads clearly:
 *
 *   1. © 2026-Made by Fajrianor                → gold (brand / copyright)
 *   2. SAATIRIL: Sistem Auto Track Input, ...  → muted lavender (tagline)
 *   3. Pusat Humas dan Keterbukaan Informasi   → cyan (institution accent)
 *   4. UIN Antasari Banjarmasin                → soft gold (university)
 *
 * The component only renders the inner lines; the parent <footer> supplies
 * the border / background / padding so it can match the surrounding theme.
 */
export function SaatirilFooterLines({ className, style }: SaatirilFooterLinesProps) {
  return (
    <div className={className} style={style}>
      {/* 1 — Copyright (monospace, gold) */}
      <p
        className="text-center font-mono text-[9px] leading-tight tracking-wider sm:text-[11px]"
        style={{ color: COLORS.gold }}
      >
        © 2026-Made by Fajrianor
      </p>

      {/* 2 — Tagline (serif italic, muted lavender) */}
      <p
        className="text-center font-serif text-[11px] italic leading-tight sm:text-xs"
        style={{ color: COLORS.muted }}
      >
        SAATIRIL: Sistem Auto Track Input, Raw Into Live
      </p>

      {/* 3 — Institution (sans, bold, uppercase, cyan) */}
      <p
        className="text-center text-[9px] font-semibold uppercase leading-tight tracking-wide sm:text-[11px]"
        style={{ color: COLORS.cyan }}
      >
        Pusat Humas dan Keterbukaan Informasi
      </p>

      {/* 4 — University (monospace, wide tracking, soft gold) */}
      <p
        className="text-center font-mono text-[8px] leading-tight tracking-[0.25em] sm:text-[10px]"
        style={{ color: COLORS.goldSoft }}
      >
        UIN Antasari Banjarmasin
      </p>
    </div>
  )
}
