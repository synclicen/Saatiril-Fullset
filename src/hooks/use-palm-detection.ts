'use client'

import { useCallback, useEffect, useRef, useState } from 'react'

/**
 * HAND PRESENCE TRIGGER (photobooth mode)
 *
 * PHOTOBOOTH TRIGGER: Hand appears → confirmed → hand leaves frame → timer starts.
 *
 * Flow:
 *   1. Person shows hand to camera
 *   2. Hand must be visible for 500ms to be "confirmed" (debounce)
 *   3. Indicator turns green: "Tangan terdeteksi ✓"
 *   4. Person removes hand from frame → TIMER STARTS immediately
 *   5. Person poses during countdown → photo taken at 0
 *
 * This is the most intuitive photobooth trigger because:
 * - Showing hand = "I'm ready"
 * - Removing hand = "Start the timer!"
 * - Person already has hand away = ready to pose immediately
 * - No complex gesture needed, just show then remove hand
 *
 * Timer ALWAYS completes once started (photobooth behavior).
 * 5-second cooldown after trigger prevents re-triggering.
 */

export type PalmDetectionStatus =
  | 'unloaded'
  | 'loading_scripts'
  | 'loading_model'
  | 'model_ready'
  | 'detecting'
  | 'stopped'
  | 'error'

/** Current hand detection state */
export type PalmState = 'none' | 'searching' | 'hand_detected' | 'confirmed' | 'triggered'

export interface PalmDetectionCallbacks {
  /** Fires when hand has been sustained long enough to be "confirmed" */
  onPalmConfirmed: () => void
  /** Fires when confirmed hand leaves frame → START TIMER (photobooth trigger) */
  onPalmLeft: () => void
}

interface UsePalmDetectionReturn {
  status: PalmDetectionStatus
  palmState: PalmState
  isRunning: boolean
  error: string | null
  initialize: () => Promise<boolean>
  startDetection: (
    videoElement: HTMLVideoElement,
    callbacks: PalmDetectionCallbacks,
  ) => Promise<void>
  stopDetection: () => void
  dispose: () => void
}

// ─── Singleton script loader ──────────────────────────────────────────────
let scriptsLoadPromise: Promise<boolean> | null = null

function loadScript(src: string): Promise<void> {
  return new Promise((resolve, reject) => {
    if (document.querySelector(`script[src="${src}"]`)) {
      resolve()
      return
    }
    const s = document.createElement('script')
    s.src = src
    s.async = true
    s.onload = () => resolve()
    s.onerror = () => reject(new Error(`Failed to load: ${src}`))
    document.head.appendChild(s)
  })
}

async function loadPalmScripts(): Promise<boolean> {
  if (scriptsLoadPromise) return scriptsLoadPromise
  scriptsLoadPromise = (async () => {
    try {
      await loadScript('https://cdn.jsdelivr.net/npm/@mediapipe/camera_utils@0.3/camera_utils.js')
      await loadScript('https://cdn.jsdelivr.net/npm/@mediapipe/drawing_utils@0.3/drawing_utils.js')
      await loadScript('https://cdn.jsdelivr.net/npm/@mediapipe/hands@0.4/hands.js')
      await new Promise((r) => setTimeout(r, 100))

      if (typeof (window as any).Hands === 'undefined') {
        throw new Error('MediaPipe Hands global missing')
      }
      return true
    } catch (e: any) {
      console.error('[SAATIRIL Hand] Script load failed:', e.message)
      scriptsLoadPromise = null
      return false
    }
  })()
  return scriptsLoadPromise
}

// ─── Tuning Constants ─────────────────────────────────────────────────────
// Hand must be held this long before confirmed (ms) — debounce against flicker
const HAND_CONFIRM_SUSTAIN_MS = 500
// Cooldown after trigger fires (ms) — prevents re-triggering
const TRIGGER_COOLDOWN_MS = 5000

export function usePalmDetection(): UsePalmDetectionReturn {
  const [status, setStatus] = useState<PalmDetectionStatus>('unloaded')
  const [palmState, setPalmState] = useState<PalmState>('none')
  const [isRunning, setIsRunning] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const handsRef = useRef<any>(null)
  const animFrameRef = useRef<number | null>(null)
  const videoRef = useRef<HTMLVideoElement | null>(null)
  const isDetectingRef = useRef(false)

  const callbacksRef = useRef<PalmDetectionCallbacks | null>(null)

  // ── Hand tracking refs ──
  const handVisibleSinceRef = useRef<number>(0)
  const isConfirmedRef = useRef<boolean>(false)
  const triggerFiredRef = useRef<boolean>(false)
  const lastTriggerTimeRef = useRef<number>(0)

  const resetState = useCallback(() => {
    handVisibleSinceRef.current = 0
    isConfirmedRef.current = false
    triggerFiredRef.current = false
    lastTriggerTimeRef.current = 0
  }, [])

  const processResults = useCallback((results: any) => {
    if (!isDetectingRef.current) return

    const multiHandLandmarks = results.multiHandLandmarks || []
    const now = Date.now()
    const handDetected = multiHandLandmarks.length > 0

    // ── Cooldown check ──
    if (lastTriggerTimeRef.current > 0 && now - lastTriggerTimeRef.current < TRIGGER_COOLDOWN_MS) {
      setPalmState('triggered')
      return
    }

    if (handDetected) {
      if (handVisibleSinceRef.current === 0) {
        // Hand just appeared
        handVisibleSinceRef.current = now
        isConfirmedRef.current = false
        triggerFiredRef.current = false
        setPalmState('hand_detected')
        console.log('[SAATIRIL Hand] Hand appeared — waiting for sustain')
      } else if (!isConfirmedRef.current && now - handVisibleSinceRef.current >= HAND_CONFIRM_SUSTAIN_MS) {
        // Hand sustained long enough → confirmed
        isConfirmedRef.current = true
        setPalmState('confirmed')
        console.log('[SAATIRIL Hand] Hand confirmed ✓ — remove hand to trigger timer')
        callbacksRef.current?.onPalmConfirmed?.()
      }
      // else: hand still visible, waiting for sustain or waiting to leave
    } else {
      // No hand in frame
      if (isConfirmedRef.current && !triggerFiredRef.current) {
        // Hand was confirmed and now left → TRIGGER!
        triggerFiredRef.current = true
        lastTriggerTimeRef.current = now
        setPalmState('triggered')
        console.log('[SAATIRIL Hand] Hand left frame → TIMER STARTED! (photobooth trigger)')
        callbacksRef.current?.onPalmLeft?.()
      } else {
        // Hand was not confirmed or already triggered — just reset
        setPalmState('none')
      }
      handVisibleSinceRef.current = 0
      isConfirmedRef.current = false
    }
  }, [])

  const detectFrameRef = useRef<() => void>(() => {})

  const detectFrame = useCallback(async () => {
    if (!isDetectingRef.current || !handsRef.current || !videoRef.current) return

    try {
      await handsRef.current.send({ image: videoRef.current })
    } catch {
      // Frame send failed, skip
    }

    if (isDetectingRef.current) {
      animFrameRef.current = requestAnimationFrame(detectFrameRef.current)
    }
  }, [])

  useEffect(() => { detectFrameRef.current = detectFrame }, [detectFrame])

  const initialize = useCallback(async (): Promise<boolean> => {
    setStatus('loading_scripts')
    setError(null)

    const ok = await loadPalmScripts()
    if (!ok) {
      setStatus('error')
      setError('Failed to load MediaPipe Hands scripts')
      return false
    }

    setStatus('loading_model')

    try {
      const hands = new (window as any).Hands({
        locateFile: (file: string) => {
          return `https://cdn.jsdelivr.net/npm/@mediapipe/hands@0.4/${file}`
        },
      })

      hands.setOptions({
        maxNumHands: 1,
        modelComplexity: 1,
        minDetectionConfidence: 0.3, // Very responsive
        minTrackingConfidence: 0.3,
      })

      hands.onResults(processResults)

      // Initialize with dummy frame
      const tempCanvas = document.createElement('canvas')
      tempCanvas.width = 1
      tempCanvas.height = 1
      await hands.send({ image: tempCanvas })

      handsRef.current = hands
      setStatus('model_ready')
      return true
    } catch (e: any) {
      console.error('[SAATIRIL Hand] Model initialization failed:', e)
      setStatus('error')
      setError(e.message || 'Model initialization failed')
      return false
    }
  }, [processResults])

  const startDetection = useCallback(
    async (videoElement: HTMLVideoElement, callbacks: PalmDetectionCallbacks) => {
      if (!handsRef.current) {
        const ok = await initialize()
        if (!ok) return
      }

      videoRef.current = videoElement
      callbacksRef.current = callbacks
      isDetectingRef.current = true
      resetState()

      setIsRunning(true)
      setPalmState('searching')
      setStatus('detecting')

      detectFrameRef.current()
    },
    [initialize, detectFrame, resetState],
  )

  const stopDetection = useCallback(() => {
    isDetectingRef.current = false
    if (animFrameRef.current) {
      cancelAnimationFrame(animFrameRef.current)
      animFrameRef.current = null
    }
    setIsRunning(false)
    setPalmState('none')
    setStatus('model_ready')
    resetState()
  }, [resetState])

  const dispose = useCallback(() => {
    stopDetection()
    if (handsRef.current) {
      handsRef.current.close()
      handsRef.current = null
    }
    setStatus('unloaded')
  }, [stopDetection])

  // Cleanup on unmount
  useEffect(() => {
    return () => {
      isDetectingRef.current = false
      if (animFrameRef.current) {
        cancelAnimationFrame(animFrameRef.current)
      }
    }
  }, [])

  return {
    status,
    palmState,
    isRunning,
    error,
    initialize,
    startDetection,
    stopDetection,
    dispose,
  }
}
