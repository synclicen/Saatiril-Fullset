'use client'

import { useEffect, useState } from 'react'
import { Badge } from '@/components/ui/badge'
import { Wifi, WifiOff, Signal, SignalHigh, SignalLow, SignalZero } from 'lucide-react'
import { getConnectionHealth, onLatencyUpdate, type ConnectionHealth } from '@/lib/socket'

// ─── Theme ────────────────────────────────────────────────────────────────
const THEME = {
  gold: '#d4af37',
  muted: '#c4b5fd',
  border: '#533485',
  bg: '#1a0b2e',
}

/**
 * Network quality indicator badge — shows latency and connection quality.
 * Used in Operator and MC panels to help users identify network issues.
 *
 * Quality thresholds (LAN-optimized):
 * - Excellent: <5ms
 * - Good: <15ms
 * - Fair: <30ms
 * - Poor: >=30ms (network issues)
 */
export function NetworkQualityBadge() {
  const [health, setHealth] = useState<ConnectionHealth>(getConnectionHealth())

  useEffect(() => {
    // Subscribe to latency updates (every 5s)
    const unsub = onLatencyUpdate((h) => setHealth({ ...h }))
    return unsub
  }, [])

  const { connected, latencyMs, avgLatencyMs, networkQuality } = health

  if (!connected) {
    return (
      <Badge
        className="text-[9px] px-1.5 py-0 border-0 gap-1"
        style={{ backgroundColor: 'rgba(248,113,113,0.2)', color: '#f87171' }}
      >
        <WifiOff className="size-3" />
        Offline
      </Badge>
    )
  }

  const config: Record<string, { color: string; bg: string; icon: React.ReactNode }> = {
    excellent: {
      color: '#4ade80',
      bg: 'rgba(74,222,128,0.2)',
      icon: <SignalHigh className="size-3" />,
    },
    good: {
      color: '#a3e635',
      bg: 'rgba(163,230,53,0.2)',
      icon: <Signal className="size-3" />,
    },
    fair: {
      color: '#fbbf24',
      bg: 'rgba(251,191,36,0.2)',
      icon: <SignalLow className="size-3" />,
    },
    poor: {
      color: '#f87171',
      bg: 'rgba(248,113,113,0.2)',
      icon: <SignalZero className="size-3" />,
    },
    unknown: {
      color: THEME.muted,
      bg: 'rgba(196,181,253,0.15)',
      icon: <Wifi className="size-3" />,
    },
  }

  const c = config[networkQuality] ?? config.unknown
  const latencyText = latencyMs >= 0 ? `${Math.round(latencyMs)}ms` : '...'

  return (
    <Badge
      className="text-[9px] px-1.5 py-0 border-0 gap-1"
      style={{ backgroundColor: c.bg, color: c.color }}
    >
      {c.icon}
      {latencyText}
    </Badge>
  )
}
