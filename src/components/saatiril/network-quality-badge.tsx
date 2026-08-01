'use client'

import { useEffect, useState } from 'react'
import { Badge } from '@/components/ui/badge'
import { Wifi, WifiOff, Signal, SignalHigh, SignalLow, SignalZero, AlertTriangle, Clock } from 'lucide-react'
import { getConnectionHealth, onLatencyUpdate, type ConnectionHealth } from '@/lib/socket'

// ─── Theme ────────────────────────────────────────────────────────────────
const THEME = {
  gold: '#d4af37',
  muted: '#c4b5fd',
  border: '#533485',
  bg: '#1a0b2e',
}

/**
 * Network quality indicator badge — shows latency, connection quality, and queue status.
 * Used in Operator and MC panels to help users identify network issues.
 *
 * Quality thresholds (LAN-optimized):
 * - Excellent: <5ms
 * - Good: <15ms
 * - Fair: <30ms
 * - Poor: >=30ms (network issues)
 *
 * Also shows:
 * - Offline state with reconnect indicator
 * - Queued events count (messages waiting to be sent)
 * - Reconnect attempt count
 */

interface NetworkQualityBadgeProps {
  /** Show detailed info (queue, reconnect count) — default true for operator, false for MC */
  detailed?: boolean
}

export function NetworkQualityBadge({ detailed = true }: NetworkQualityBadgeProps) {
  const [health, setHealth] = useState<ConnectionHealth>(getConnectionHealth())

  useEffect(() => {
    // Subscribe to latency updates (every 5s)
    const unsub = onLatencyUpdate((h) => setHealth({ ...h }))
    return unsub
  }, [])

  const { connected, latencyMs, avgLatencyMs, networkQuality, queuedEvents, reconnectCount } = health

  if (!connected) {
    return (
      <div className="flex items-center gap-1">
        <Badge
          className="text-[9px] px-1.5 py-0 border-0 gap-1"
          style={{ backgroundColor: 'rgba(248,113,113,0.2)', color: '#f87171' }}
        >
          <WifiOff className="size-3" />
          Offline
        </Badge>
        {reconnectCount > 0 && (
          <Badge
            className="text-[9px] px-1.5 py-0 border-0 gap-1"
            style={{ backgroundColor: 'rgba(251,191,36,0.15)', color: '#fbbf24' }}
          >
            <Clock className="size-3" />
            Reconnect #{reconnectCount}
          </Badge>
        )}
        {queuedEvents > 0 && (
          <Badge
            className="text-[9px] px-1.5 py-0 border-0 gap-1"
            style={{ backgroundColor: 'rgba(251,191,36,0.15)', color: '#fbbf24' }}
          >
            <AlertTriangle className="size-3" />
            {queuedEvents} queued
          </Badge>
        )}
      </div>
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
    <div className="flex items-center gap-1">
      <Badge
        className="text-[9px] px-1.5 py-0 border-0 gap-1"
        style={{ backgroundColor: c.bg, color: c.color }}
      >
        {c.icon}
        {latencyText}
      </Badge>
      {detailed && queuedEvents > 0 && (
        <Badge
          className="text-[9px] px-1.5 py-0 border-0 gap-1"
          style={{ backgroundColor: 'rgba(251,191,36,0.15)', color: '#fbbf24' }}
        >
          <AlertTriangle className="size-3" />
          {queuedEvents} queued
        </Badge>
      )}
    </div>
  )
}
