'use client'

import { useCallback, useEffect, useRef, useState } from 'react'

export type FingerDetectionStatus =
  | 'unloaded'
  | 'loading_scripts'
  | 'loading_model'
  | 'model_ready'
  | 'detecting'
  | 'stopped'
  | 'error'

export interface FingerDetectionResult {
  fingerCount: number
  confidence: number
  handsDetected: number
  sustainProgress: number // 0-1, progress toward sustain duration when 5 fingers held
}

interface UseFingerDetectionReturn {
  status: FingerDetectionStatus
  fingerCount: number
  handsDetected: number
  isRunning: boolean
  sustainProgress: number // 0-1, progress toward sustain duration when 5 fingers held
  error: string | null
  initialize: () => Promise<boolean>
  startDetection: (
    videoElement: HTMLVideoElement,
    onFiveFingers: () => void,
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

async function loadFingerScripts(): Promise<boolean> {
  if (scriptsLoadPromise) return scriptsLoadPromise
  scriptsLoadPromise = (async () => {
    try {
      // Load MediaPipe Hands from CDN
      await loadScript('https://cdn.jsdelivr.net/npm/@mediapipe/camera_utils@0.3/camera_utils.js')
      await loadScript('https://cdn.jsdelivr.net/npm/@mediapipe/drawing_utils@0.3/drawing_utils.js')
      await loadScript('https://cdn.jsdelivr.net/npm/@mediapipe/hands@0.4/hands.js')
      await new Promise((r) => setTimeout(r, 100))

      // Verify globals
      if (typeof (window as any).Hands === 'undefined') {
        throw new Error('MediaPipe Hands global missing')
      }
      return true
    } catch (e: any) {
      console.error('[SAATIRIL Finger] Script load failed:', e.message)
      scriptsLoadPromise = null
      return false
    }
  })()
  return scriptsLoadPromise
}

// ─── Count fingers from hand landmarks ────────────────────────────────────
// Returns number of extended fingers (0-5) per hand
function countFingers(landmarks: any[]): number {
  // Landmark indices for each finger
  // Thumb: 1,2,3,4 | Index: 5,6,7,8 | Middle: 9,10,11,12 | Ring: 13,14,15,16 | Pinky: 17,18,19,20
  const tipIds = [4, 8, 12, 16, 20]
  const pipIds = [3, 6, 10, 14, 18] // Proximal interphalangeal joints

  let fingersUp = 0

  // Thumb: compare x position (left hand vs right hand)
  // If thumb tip is further from palm than thumb IP joint
  const thumbTip = landmarks[tipIds[0]]
  const thumbIp = landmarks[pipIds[0]]
  const wrist = landmarks[0]

  // Determine hand orientation based on wrist and middle finger MCP
  const middleMcp = landmarks[9]
  const isRightHand = wrist.x < middleMcp.x

  if (isRightHand) {
    if (thumbTip.x < thumbIp.x) fingersUp++
  } else {
    if (thumbTip.x > thumbIp.x) fingersUp++
  }

  // Other 4 fingers: compare y position (tip vs PIP)
  // If tip is above PIP (lower y = higher on screen), finger is extended
  for (let i = 1; i < 5; i++) {
    const tip = landmarks[tipIds[i]]
    const pip = landmarks[pipIds[i]]
    if (tip.y < pip.y) fingersUp++
  }

  return fingersUp
}

export function useFingerDetection(): UseFingerDetectionReturn {
  const [status, setStatus] = useState<FingerDetectionStatus>('unloaded')
  const [fingerCount, setFingerCount] = useState(0)
  const [handsDetected, setHandsDetected] = useState(0)
  const [isRunning, setIsRunning] = useState(false)
  const [sustainProgress, setSustainProgress] = useState(0)
  const [error, setError] = useState<string | null>(null)

  const handsRef = useRef<any>(null)
  const animFrameRef = useRef<number | null>(null)
  const onFiveFingersRef = useRef<(() => void) | null>(null)
  const sustainStartRef = useRef<number>(0)
  const lastTriggerRef = useRef<number>(0)
  const videoRef = useRef<HTMLVideoElement | null>(null)
  const isDetectingRef = useRef(false)

  // Sustain duration: 5 fingers must be held for this long (ms) before triggering
  const SUSTAIN_DURATION = 800
  // Cooldown: after triggering, wait this long before allowing another trigger
  const TRIGGER_COOLDOWN = 3000

  const processResults = useCallback((results: any) => {
    if (!isDetectingRef.current) return

    const multiHandLandmarks = results.multiHandLandmarks || []
    const numHands = multiHandLandmarks.length
    setHandsDetected(numHands)

    if (numHands === 0) {
      setFingerCount(0)
      sustainStartRef.current = 0
      setSustainProgress(0)
      return
    }

    // Check all hands for 5 fingers
    let maxFingers = 0
    for (const landmarks of multiHandLandmarks) {
      const count = countFingers(landmarks)
      maxFingers = Math.max(maxFingers, count)
    }
    setFingerCount(maxFingers)

    const now = Date.now()

    if (maxFingers >= 5) {
      // 5 fingers detected
      if (sustainStartRef.current === 0) {
        // Start counting sustain duration
        sustainStartRef.current = now
        setSustainProgress(0)
      } else {
        // Calculate progress
        const progress = Math.min(1, (now - sustainStartRef.current) / SUSTAIN_DURATION)
        setSustainProgress(progress)
        if (now - sustainStartRef.current >= SUSTAIN_DURATION) {
          // Sustained for long enough — trigger
          if (now - lastTriggerRef.current >= TRIGGER_COOLDOWN) {
            lastTriggerRef.current = now
            sustainStartRef.current = 0
            setSustainProgress(0)
            console.log('[SAATIRIL Finger] 5 fingers sustained — triggering')
            onFiveFingersRef.current?.()
          }
        }
      }
    } else {
      // Not 5 fingers — reset sustain timer
      sustainStartRef.current = 0
      setSustainProgress(0)
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

  // Keep ref in sync
  useEffect(() => { detectFrameRef.current = detectFrame }, [detectFrame])

  const initialize = useCallback(async (): Promise<boolean> => {
    setStatus('loading_scripts')
    setError(null)

    const ok = await loadFingerScripts()
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
        modelComplexity: 0, // Use lite model for faster detection
        minDetectionConfidence: 0.6,
        minTrackingConfidence: 0.5,
      })

      hands.onResults(processResults)

      // Initialize the model by sending a dummy frame
      const tempCanvas = document.createElement('canvas')
      tempCanvas.width = 1
      tempCanvas.height = 1
      await hands.send({ image: tempCanvas })

      handsRef.current = hands
      setStatus('model_ready')
      return true
    } catch (e: any) {
      console.error('[SAATIRIL Finger] Model initialization failed:', e)
      setStatus('error')
      setError(e.message || 'Model initialization failed')
      return false
    }
  }, [processResults])

  const startDetection = useCallback(
    async (videoElement: HTMLVideoElement, onFiveFingers: () => void) => {
      if (!handsRef.current) {
        const ok = await initialize()
        if (!ok) return
      }

      videoRef.current = videoElement
      onFiveFingersRef.current = onFiveFingers
      isDetectingRef.current = true
      sustainStartRef.current = 0
      lastTriggerRef.current = 0

      setIsRunning(true)
      setStatus('detecting')

      // Start detection loop
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
    setFingerCount(0)
    setHandsDetected(0)
    setSustainProgress(0)
    setStatus('model_ready')
    sustainStartRef.current = 0
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
    fingerCount,
    handsDetected,
    isRunning,
    sustainProgress,
    error,
    initialize,
    startDetection,
    stopDetection,
    dispose,
  }
}
