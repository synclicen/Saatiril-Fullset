'use client'

import { useCallback, useEffect, useRef, useState } from 'react'

/**
 * WAVING HAND DETECTION (shutter trigger)
 *
 * Detects a WAVING hand gesture (hand moving back and forth horizontally)
 * to trigger the camera shutter. Much more responsive and intentional
 * than static hand presence detection.
 *
 * PHOTOBOOTH BEHAVIOR:
 *   1. Person waves their hand in front of the camera.
 *   2. System detects horizontal oscillation (≥2 direction changes).
 *   3. onWaveConfirmed fires ONCE → starts timer (or direct capture).
 *   4. Person can STOP waving and pose — timer ALWAYS completes.
 *   5. When countdown reaches 0 → photo is taken automatically.
 *
 * WAVING ALGORITHM:
 *   - Track wrist (landmark 0) X position across frames
 *   - Detect direction changes (left→right or right→left)
 *   - Min amplitude per direction: 6% of frame width
 *   - 2+ direction changes within 2 seconds = wave confirmed
 *   - 5-second cooldown after confirmation prevents re-triggering
 */

export type PalmDetectionStatus =
  | 'unloaded'
  | 'loading_scripts'
  | 'loading_model'
  | 'model_ready'
  | 'detecting'
  | 'stopped'
  | 'error'

/** Current wave detection state, for UI feedback */
export type PalmState = 'none' | 'searching' | 'hand_visible' | 'waving' | 'confirmed'

export interface PalmDetectionCallbacks {
  /** Fires once when a wave gesture has been confirmed */
  onPalmConfirmed: () => void
  /** Fires when the confirmed hand leaves the frame (NO-OP: timer continues — photobooth behavior) */
  onPalmReleased: () => void
}

interface UsePalmDetectionReturn {
  status: PalmDetectionStatus
  palmState: PalmState
  /** 0–5, how many fingers currently extended (for the small indicator) */
  fingersExtended: number
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
      console.error('[SAATIRIL Wave] Script load failed:', e.message)
      scriptsLoadPromise = null
      return false
    }
  })()
  return scriptsLoadPromise
}

// ─── Count extended fingers from hand landmarks ───────────────────────────
function countExtendedFingers(landmarks: any[]): number {
  const tipIds = [4, 8, 12, 16, 20]
  const pipIds = [3, 6, 10, 14, 18]

  let fingersUp = 0

  const thumbTip = landmarks[tipIds[0]]
  const thumbIp = landmarks[pipIds[0]]
  const wrist = landmarks[0]
  const middleMcp = landmarks[9]
  const isRightHand = wrist.x < middleMcp.x

  if (isRightHand) {
    if (thumbTip.x < thumbIp.x) fingersUp++
  } else {
    if (thumbTip.x > thumbIp.x) fingersUp++
  }

  for (let i = 1; i < 5; i++) {
    const tip = landmarks[tipIds[i]]
    const pip = landmarks[pipIds[i]]
    if (tip.y < pip.y) fingersUp++
  }

  return fingersUp
}

// ─── Waving Detection Tuning Constants ────────────────────────────────────
// Min horizontal displacement to count as a direction change (6% of frame)
const MIN_WAVE_AMPLITUDE = 0.06
// Number of direction changes needed (2 = one full back-and-forth)
const MIN_DIRECTION_CHANGES = 2
// Time window for direction changes (ms)
const WAVE_WINDOW_MS = 2000
// Cooldown after confirmation (ms) — prevents re-triggering
const CONFIRM_COOLDOWN_MS = 5000
// Min interval between position samples (ms)
const SAMPLE_INTERVAL_MS = 80

// Max position history entries
const MAX_POSITION_HISTORY = 30

interface PositionSample {
  x: number
  timestamp: number
}

export function usePalmDetection(): UsePalmDetectionReturn {
  const [status, setStatus] = useState<PalmDetectionStatus>('unloaded')
  const [palmState, setPalmState] = useState<PalmState>('none')
  const [fingersExtended, setFingersExtended] = useState(0)
  const [isRunning, setIsRunning] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const handsRef = useRef<any>(null)
  const animFrameRef = useRef<number | null>(null)
  const videoRef = useRef<HTMLVideoElement | null>(null)
  const isDetectingRef = useRef(false)

  const callbacksRef = useRef<PalmDetectionCallbacks | null>(null)

  // ── Wave tracking refs ──
  const positionHistoryRef = useRef<PositionSample[]>([])
  const lastDirectionRef = useRef(0) // +1=right, -1=left, 0=unknown
  const directionChangeCountRef = useRef(0)
  const lastDirectionChangeTimeRef = useRef(0)
  const lastWristXRef = useRef(-1)
  const lastSampleTimeRef = useRef(0)
  const confirmedRef = useRef(false)
  const lastConfirmTimeRef = useRef(0)

  const resetWaveState = useCallback(() => {
    positionHistoryRef.current = []
    lastDirectionRef.current = 0
    directionChangeCountRef.current = 0
    lastDirectionChangeTimeRef.current = 0
    lastWristXRef.current = -1
    lastSampleTimeRef.current = 0
    confirmedRef.current = false
    lastConfirmTimeRef.current = 0
  }, [])

  const processResults = useCallback((results: any) => {
    if (!isDetectingRef.current) return

    const multiHandLandmarks = results.multiHandLandmarks || []

    // Count extended fingers for visual indicator only
    let maxFingers = 0
    for (const landmarks of multiHandLandmarks) {
      const count = countExtendedFingers(landmarks)
      if (count > maxFingers) maxFingers = count
    }
    setFingersExtended(maxFingers)

    const now = Date.now()
    const handVisible = multiHandLandmarks.length > 0

    // ── Cooldown check ──
    if (lastConfirmTimeRef.current > 0 && now - lastConfirmTimeRef.current < CONFIRM_COOLDOWN_MS) {
      setPalmState('none')
      return
    }

    if (handVisible) {
      const wrist = multiHandLandmarks[0][0] // First hand, wrist landmark
      const wristX = wrist.x

      // ── Sample at controlled interval ──
      if (now - lastSampleTimeRef.current >= SAMPLE_INTERVAL_MS) {
        lastSampleTimeRef.current = now

        if (lastWristXRef.current >= 0) {
          const deltaX = wristX - lastWristXRef.current

          // Determine current movement direction
          let currentDirection = lastDirectionRef.current
          if (deltaX > MIN_WAVE_AMPLITUDE) currentDirection = 1
          else if (deltaX < -MIN_WAVE_AMPLITUDE) currentDirection = -1

          // Check for direction change
          if (currentDirection !== 0 && lastDirectionRef.current !== 0 && currentDirection !== lastDirectionRef.current) {
            directionChangeCountRef.current++
            lastDirectionChangeTimeRef.current = now

            // Clean old samples outside window
            positionHistoryRef.current = positionHistoryRef.current.filter(
              (s) => now - s.timestamp < WAVE_WINDOW_MS
            )

            console.log(`[SAATIRIL Wave] Direction change #${directionChangeCountRef.current} (δx=${deltaX.toFixed(3)}, dir=${currentDirection})`)
          }

          if (currentDirection !== 0) {
            lastDirectionRef.current = currentDirection
          }
        }

        // Store position sample
        positionHistoryRef.current.push({ x: wristX, timestamp: now })
        if (positionHistoryRef.current.length > MAX_POSITION_HISTORY) {
          positionHistoryRef.current.shift()
        }

        lastWristXRef.current = wristX

        // ── Check if waving is confirmed ──
        if (
          !confirmedRef.current &&
          directionChangeCountRef.current >= MIN_DIRECTION_CHANGES &&
          lastDirectionChangeTimeRef.current > 0 &&
          now - lastDirectionChangeTimeRef.current < WAVE_WINDOW_MS
        ) {
          confirmedRef.current = true
          lastConfirmTimeRef.current = now
          setPalmState('confirmed')
          console.log(`[SAATIRIL Wave] Wave confirmed! (${directionChangeCountRef.current} direction changes) — triggering shutter`)
          callbacksRef.current?.onPalmConfirmed?.()
        } else if (!confirmedRef.current) {
          // Update UI state
          setPalmState(directionChangeCountRef.current > 0 ? 'waving' : 'hand_visible')
        }
      }
    } else {
      // No hand in frame
      if (confirmedRef.current) {
        // Photobooth behavior: timer continues
        console.log('[SAATIRIL Wave] Hand left frame — timer continues (photobooth mode)')
        callbacksRef.current?.onPalmReleased?.()
      }
      // Reset wave tracking for next attempt
      positionHistoryRef.current = []
      lastDirectionRef.current = 0
      directionChangeCountRef.current = 0
      lastDirectionChangeTimeRef.current = 0
      lastWristXRef.current = -1
      confirmedRef.current = false
      setPalmState('none')
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
        minDetectionConfidence: 0.4, // Lower for better responsiveness
        minTrackingConfidence: 0.4,
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
      console.error('[SAATIRIL Wave] Model initialization failed:', e)
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
      resetWaveState()

      setIsRunning(true)
      setPalmState('searching')
      setStatus('detecting')

      detectFrameRef.current()
    },
    [initialize, detectFrame, resetWaveState],
  )

  const stopDetection = useCallback(() => {
    isDetectingRef.current = false
    if (animFrameRef.current) {
      cancelAnimationFrame(animFrameRef.current)
      animFrameRef.current = null
    }
    setIsRunning(false)
    setFingersExtended(0)
    setPalmState('none')
    setStatus('model_ready')
    resetWaveState()
  }, [resetWaveState])

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
    fingersExtended,
    isRunning,
    error,
    initialize,
    startDetection,
    stopDetection,
    dispose,
  }
}
