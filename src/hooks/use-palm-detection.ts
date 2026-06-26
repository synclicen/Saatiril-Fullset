'use client'

import { useCallback, useEffect, useRef, useState } from 'react'

/**
 * PALM DETECTION (selfie-style shutter)
 *
 * Behaves like a phone selfie palm timer:
 *   1. Operator shows an OPEN PALM to the camera (all 5 fingers extended).
 *   2. Palm must be held ~500ms to be "confirmed" (debounce against flicker).
 *   3. onPalmConfirmed fires ONCE → operator-panel starts a 3s countdown.
 *   4. If the palm is removed (hand closes, drops, or leaves frame) while the
 *      countdown is still running, onPalmReleased fires → countdown cancels.
 *   5. If the palm stays up through the whole countdown → photo is taken.
 *
 * This is NOT the old "count fingers 1→5" gesture. The only gesture is an
 * open palm held up to the camera.
 */

export type PalmDetectionStatus =
  | 'unloaded'
  | 'loading_scripts'
  | 'loading_model'
  | 'model_ready'
  | 'detecting'
  | 'stopped'
  | 'error'

/** Current palm state, for UI feedback */
export type PalmState = 'none' | 'searching' | 'held' | 'confirmed'

export interface PalmDetectionCallbacks {
  /** Fires once when an open palm has been held long enough to confirm */
  onPalmConfirmed: () => void
  /** Fires when the confirmed palm is removed (hand closes / leaves frame) */
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
      // Load MediaPipe Hands from CDN (same model — detects hand landmarks)
      await loadScript('https://cdn.jsdelivr.net/npm/@mediapipe/camera_utils@0.3/camera_utils.js')
      await loadScript('https://cdn.jsdelivr.net/npm/@mediapipe/drawing_utils@0.3/drawing_utils.js')
      await loadScript('https://cdn.jsdelivr.net/npm/@mediapipe/hands@0.4/hands.js')
      await new Promise((r) => setTimeout(r, 100))

      if (typeof (window as any).Hands === 'undefined') {
        throw new Error('MediaPipe Hands global missing')
      }
      return true
    } catch (e: any) {
      console.error('[SAATIRIL Palm] Script load failed:', e.message)
      scriptsLoadPromise = null
      return false
    }
  })()
  return scriptsLoadPromise
}

// ─── Count extended fingers from hand landmarks ───────────────────────────
// An OPEN PALM = all 5 fingers extended. Returns 0–5.
function countExtendedFingers(landmarks: any[]): number {
  const tipIds = [4, 8, 12, 16, 20]
  const pipIds = [3, 6, 10, 14, 18] // Proximal interphalangeal joints

  let fingersUp = 0

  // Thumb: compare x position relative to hand orientation
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

  // Other 4 fingers: tip above PIP (lower y = higher) means extended
  for (let i = 1; i < 5; i++) {
    const tip = landmarks[tipIds[i]]
    const pip = landmarks[pipIds[i]]
    if (tip.y < pip.y) fingersUp++
  }

  return fingersUp
}

// ─── Tuning constants ─────────────────────────────────────────────────────
// Palm must be held this long before "confirmed" (debounce against flicker)
const PALM_CONFIRM_SUSTAIN_MS = 500
// How many fingers count as "an open palm". 5 = strict open palm.
const PALM_FINGER_THRESHOLD = 5

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
  // Track whether we've already fired onPalmConfirmed for the current hold
  const confirmedRef = useRef<boolean>(false)
  // Timestamp when palm first reached the threshold (0 = not currently held)
  const palmSinceRef = useRef<number>(0)

  const processResults = useCallback((results: any) => {
    if (!isDetectingRef.current) return

    const multiHandLandmarks = results.multiHandLandmarks || []

    // Find the hand with the most extended fingers
    let maxFingers = 0
    for (const landmarks of multiHandLandmarks) {
      const count = countExtendedFingers(landmarks)
      if (count > maxFingers) maxFingers = count
    }
    setFingersExtended(maxFingers)

    const now = Date.now()
    const palmCurrentlyUp = maxFingers >= PALM_FINGER_THRESHOLD

    if (palmCurrentlyUp) {
      if (palmSinceRef.current === 0) {
        // Palm just appeared — start the sustain clock
        palmSinceRef.current = now
        setPalmState('held')
      } else if (!confirmedRef.current && now - palmSinceRef.current >= PALM_CONFIRM_SUSTAIN_MS) {
        // Sustained long enough → confirm (fires countdown start)
        confirmedRef.current = true
        setPalmState('confirmed')
        console.log('[SAATIRIL Palm] Palm confirmed — starting countdown')
        callbacksRef.current?.onPalmConfirmed?.()
      }
    } else {
      // No open palm right now
      if (confirmedRef.current) {
        // Was confirmed, now removed → release (cancels countdown)
        console.log('[SAATIRIL Palm] Palm released — cancelling countdown')
        callbacksRef.current?.onPalmReleased?.()
      }
      palmSinceRef.current = 0
      confirmedRef.current = false
      setPalmState(multiHandLandmarks.length > 0 ? 'searching' : 'none')
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
        modelComplexity: 0, // lite model for speed
        minDetectionConfidence: 0.6,
        minTrackingConfidence: 0.5,
      })

      hands.onResults(processResults)

      // Initialize the model with a dummy frame
      const tempCanvas = document.createElement('canvas')
      tempCanvas.width = 1
      tempCanvas.height = 1
      await hands.send({ image: tempCanvas })

      handsRef.current = hands
      setStatus('model_ready')
      return true
    } catch (e: any) {
      console.error('[SAATIRIL Palm] Model initialization failed:', e)
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
      confirmedRef.current = false
      palmSinceRef.current = 0

      setIsRunning(true)
      setPalmState('searching')
      setStatus('detecting')

      detectFrameRef.current()
    },
    [initialize, detectFrame],
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
    confirmedRef.current = false
    palmSinceRef.current = 0
  }, [])

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
