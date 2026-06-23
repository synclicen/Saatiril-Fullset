'use client'

import type { CSSProperties } from 'react'

interface SaatirilFooterLinesProps {
  /** Wrapper className applied to the lines container */
  className?: string
  /** Wrapper inline style (e.g. color) — text inherits via currentColor */
  style?: CSSProperties
}

/**
 * Branding footer for the Saatiril system.
 *
 * Renders the official 4-line credit block, each line using a distinctly
 * different font style so the visual hierarchy reads clearly:
 *
 *   1. © 2026-Made by Fajrianor                → mono (technical / copyright)
 *   2. SAATIRIL: Sistem Auto Track Input, ...  → serif italic (tagline)
 *   3. Pusat Humas dan Keterbukaan Informasi   → sans bold uppercase (institution)
 *   4. UIN Antasari Banjarmasin                → mono wide-tracked (university)
 *
 * The component only renders the inner lines; the parent <footer> supplies
 * the border / background / padding so it can match the surrounding theme.
 * Text colour is inherited from the parent via `currentColor` plus opacity.
 */
export function SaatirilFooterLines({ className, style }: SaatirilFooterLinesProps) {
  return (
    <div className={className} style={style}>
      {/* 1 — Copyright (monospace) */}
      <p className="text-center font-mono text-[10px] leading-relaxed tracking-wider opacity-70 sm:text-xs">
        © 2026-Made by Fajrianor
      </p>

      {/* 2 — Tagline (serif italic) */}
      <p className="text-center font-serif text-xs italic leading-relaxed opacity-90 sm:text-sm">
        SAATIRIL: Sistem Auto Track Input, Raw Into Live
      </p>

      {/* 3 — Institution (sans, bold, uppercase) */}
      <p className="text-center text-[10px] font-semibold uppercase leading-relaxed tracking-wide opacity-80 sm:text-xs">
        Pusat Humas dan Keterbukaan Informasi
      </p>

      {/* 4 — University (monospace, wide tracking) */}
      <p className="text-center font-mono text-[9px] leading-relaxed tracking-[0.25em] opacity-60 sm:text-[11px]">
        UIN Antasari Banjarmasin
      </p>
    </div>
  )
}
