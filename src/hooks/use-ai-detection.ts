'use client'

import { useCallback, useEffect, useRef, useState } from 'react'

export type AIStatus = 'unloaded' | 'loading_scripts' | 'loading_model' | 'model_ready' | 'detecting' | 'stopped' | 'error'
export type MomentType = 'toga' | 'ijazah'
export type MomentState = 'idle' | 'toga_possible' | 'toga_sustained' | 'ijazah_possible' | 'ijazah_sustained'

export interface AIMomentEvent {
  type: MomentType
  timestamp: number
  confidence: number
  poses: number
}

export interface AIConfig {
  sensitivity: number
  detectionInterval: number
  detectionCooldown: number
  sustainDuration: number
}

interface UseAIDetectionReturn {
  status: AIStatus
  momentState: MomentState
  posesDetected: number
  scriptsLoaded: boolean
  modelLoaded: boolean
  isRunning: boolean
  error: string | null
  config: AIConfig | null
  initialize: () => Promise<boolean>
  startDetection: (videoElement: HTMLVideoElement, onMoment: (event: AIMomentEvent) => void) => Promise<void>
  stopDetection: () => void
  updateConfig: (config: Partial<AIConfig>) => void
  dispose: () => void
}

let scriptsLoadPromise: Promise<boolean> | null = null

function loadScript(src: string): Promise<void> {
  return new Promise((resolve, reject) => {
    if (document.querySelector(`script[src="${src}"]`)) { resolve(); return }
    const s = document.createElement('script')
    s.src = src; s.async = true
    s.onload = () => resolve()
    s.onerror = () => reject(new Error(`Failed: ${src}`))
    document.head.appendChild(s)
  })
}

async function loadAIScripts(): Promise<boolean> {
  if (scriptsLoadPromise) return scriptsLoadPromise
  scriptsLoadPromise = (async () => {
    try {
      await loadScript('/ai/tf.min.js')
      await new Promise(r => setTimeout(r, 100))
      if (typeof (window as any).tf === 'undefined') throw new Error('TF.js global missing')
      await loadScript('/ai/pose-detection.min.js')
      await new Promise(r => setTimeout(r, 100))
      if (typeof (window as any).poseDetection === 'undefined') throw new Error('poseDetection global missing')
      await loadScript('/ai/saatiril-ai.js')
      if (!(window as any).SaatirilAI) throw new Error('SaatirilAI missing')
      return true
    } catch (e: any) {
      console.error('[SAATIRIL AI Hook] Script load failed:', e.message)
      scriptsLoadPromise = null
      return false
    }
  })()
  return scriptsLoadPromise
}

export function useAIDetection(): UseAIDetectionReturn {
  const [status, setStatus] = useState<AIStatus>('unloaded')
  const [momentState, setMomentState] = useState<MomentState>('idle')
  const [posesDetected, setPosesDetected] = useState(0)
  const [scriptsLoaded, setScriptsLoaded] = useState(false)
  const [modelLoaded, setModelLoaded] = useState(false)
  const [isRunning, setIsRunning] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [config, setConfig] = useState<AIConfig | null>(null)
  const onMomentRef = useRef<((event: AIMomentEvent) => void) | null>(null)

  useEffect(() => {
    const handler = (e: Event) => {
      const d = (e as CustomEvent).detail
      if (!d) return
      setStatus(d.status as AIStatus)
      setMomentState(d.momentState)
      setPosesDetected(d.posesDetected)
      setModelLoaded(d.isModelLoaded)
      setIsRunning(d.isRunning)
      if (d.status === 'error') setError(d.detail); else setError(null)
    }
    window.addEventListener('saatiril-ai-status', handler)
    return () => window.removeEventListener('saatiril-ai-status', handler)
  }, [])

  const initialize = useCallback(async (): Promise<boolean> => {
    setStatus('loading_scripts'); setError(null)
    const ok = await loadAIScripts()
    if (!ok) { setStatus('error'); setError('Failed to load AI scripts'); return false }
    setScriptsLoaded(true)
    const ai = (window as any).SaatirilAI
    if (ai) setConfig(ai.getConfig())
    return true
  }, [])

  const startDetection = useCallback(async (videoElement: HTMLVideoElement, onMoment: (event: AIMomentEvent) => void) => {
    const ai = (window as any).SaatirilAI
    if (!ai) { setError('AI module not loaded'); return }
    onMomentRef.current = onMoment
    await ai.start(videoElement, {
      onMomentDetected: (type: MomentType, data: { timestamp: number; confidence: number; poses: number }) => {
        onMomentRef.current?.({ type, timestamp: data.timestamp, confidence: data.confidence, poses: data.poses })
      },
      onStatusChange: (s: any) => {
        setStatus(s.status as AIStatus); setMomentState(s.momentState); setPosesDetected(s.posesDetected)
        setModelLoaded(s.isModelLoaded); setIsRunning(s.isRunning)
        if (s.status === 'error') setError(s.detail); else setError(null)
      },
    })
    setIsRunning(true); setConfig(ai.getConfig())
  }, [])

  const stopDetection = useCallback(() => {
    const ai = (window as any).SaatirilAI
    if (ai) ai.stop()
    setIsRunning(false); setMomentState('idle'); setPosesDetected(0)
  }, [])

  const updateConfig = useCallback((c: Partial<AIConfig>) => {
    const ai = (window as any).SaatirilAI
    if (ai) { ai.updateConfig(c); setConfig(ai.getConfig()) }
  }, [])

  const dispose = useCallback(() => {
    const ai = (window as any).SaatirilAI
    if (ai) ai.dispose()
    setIsRunning(false); setModelLoaded(false); setMomentState('idle'); setPosesDetected(0); setStatus('unloaded')
  }, [])

  useEffect(() => () => { const ai = (window as any).SaatirilAI; if (ai?.isRunning) ai.stop() }, [])

  return { status, momentState, posesDetected, scriptsLoaded, modelLoaded, isRunning, error, config, initialize, startDetection, stopDetection, updateConfig, dispose }
}
