'use client'

import { useEffect, useCallback, useLayoutEffect, Component, ReactNode } from 'react'
import { useSaatirilStore } from '@/store/use-saatiril-store'
import { ProjectHub } from '@/components/saatiril/project-hub'
import ProjectSetup from '@/components/saatiril/project-setup'
import { MainApp } from '@/components/saatiril/main-app'
import { Button } from '@/components/ui/button'
import { AlertTriangle } from 'lucide-react'

// ─── Screen-level Error Boundary ──────────────────────────────────────────
// Catches render errors in individual screens so the entire app doesn't crash.
// This is critical: if ProjectSetup or MainApp throws during render,
// the user can still go back to the hub instead of seeing a blank screen.
interface ErrorBoundaryProps {
  children: ReactNode
  fallbackScreen: 'hub' | 'setup' | 'app'
}

interface ErrorBoundaryState {
  hasError: boolean
  error: Error | null
}

class ScreenErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  constructor(props: ErrorBoundaryProps) {
    super(props)
    this.state = { hasError: false, error: null }
  }

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { hasError: true, error }
  }

  componentDidCatch(error: Error, errorInfo: React.ErrorInfo) {
    console.error(`[SAATIRIL] Screen render error (${this.props.fallbackScreen}):`, error, errorInfo)
  }

  handleReset = () => {
    this.setState({ hasError: false, error: null })
    // Navigate back to hub on error recovery
    useSaatirilStore.getState().setCurrentScreen('hub')
  }

  render() {
    if (this.state.hasError) {
      return (
        <div className="flex h-screen w-screen flex-col items-center justify-center gap-4 bg-[#1a0b2e] p-8">
          <div className="flex h-16 w-16 items-center justify-center rounded-full bg-red-500/20">
            <AlertTriangle className="h-8 w-8 text-red-400" />
          </div>
          <h2 className="text-lg font-bold text-white">Terjadi Kesalahan</h2>
          <p className="max-w-md text-center text-sm text-[#c4b5fd]/70">
            Layar gagal dimuat. Silakan kembali ke halaman utama dan coba lagi.
          </p>
          {this.state.error && (
            <p className="max-w-lg text-center text-xs text-red-400/70 font-mono">
              {this.state.error.message}
            </p>
          )}
          <Button
            onClick={this.handleReset}
            className="bg-[#d4af37] text-[#1a0b2e] hover:bg-[#d4af37]/90 font-semibold"
          >
            Kembali ke Halaman Utama
          </Button>
        </div>
      )
    }
    return this.props.children
  }
}

// ─── Main Page Component ──────────────────────────────────────────────────
export default function Home() {
  const currentScreen = useSaatirilStore((s) => s.currentScreen)
  const loadProjectsFromStorage = useSaatirilStore((s) => s.loadProjectsFromStorage)

  // ── URL parameter routing for LAN clients (MC/Operator) ─────────────────
  // Detect role from URL and bypass hub/setup screens for non-admin clients.
  // useLayoutEffect ensures this runs before browser paint, preventing flash
  // of the hub screen that the user should never see.
  useLayoutEffect(() => {
    const params = new URLSearchParams(window.location.search)
    const roleParam = params.get('role')
    if (roleParam === 'mc' || roleParam === 'operator') {
      const store = useSaatirilStore.getState()
      store.setMyRole(roleParam)
      const channelParam = params.get('channel')
      if (channelParam) {
        const ch = parseInt(channelParam, 10)
        if (ch >= 1 && ch <= 2) store.setMyChannel(ch)
      }
      store.setCurrentScreen('app')
      console.log(`[SAATIRIL] LAN client detected — role: ${roleParam}, channel: ${channelParam}`)
    }
  }, [])

  useEffect(() => {
    try {
      loadProjectsFromStorage()

      // ── Recover currentProject from localStorage for LAN clients ────────
      // MC/Operator may have previously received project data from admin
      // and saved it to localStorage. On page refresh, recover it so they
      // don't get stuck on "waiting for sync" when admin is temporarily offline.
      const store = useSaatirilStore.getState()
      if (store.myRole !== 'admin' && !store.currentProject && store.projects.length > 0) {
        store.setCurrentProject(store.projects[0])
        console.log('[SAATIRIL] Recovered currentProject from localStorage for', store.myRole)
      }

      console.log('[SAATIRIL] App loaded — currentScreen:', useSaatirilStore.getState().currentScreen)
    } catch (e) {
      console.error('[SAATIRIL] Failed to load projects from storage on mount:', e)
    }
  }, [loadProjectsFromStorage])

  // Global error handler for uncaught errors in the renderer
  useEffect(() => {
    const handler = (event: ErrorEvent) => {
      console.error('[SAATIRIL] Uncaught error:', event.error)
    }
    window.addEventListener('error', handler)
    return () => window.removeEventListener('error', handler)
  }, [])

  return (
    <div className="h-screen w-screen flex flex-col overflow-hidden">
      <ScreenErrorBoundary fallbackScreen="hub">
        {currentScreen === 'hub' && <ProjectHub />}
      </ScreenErrorBoundary>
      <ScreenErrorBoundary fallbackScreen="setup">
        {currentScreen === 'setup' && <ProjectSetup />}
      </ScreenErrorBoundary>
      <ScreenErrorBoundary fallbackScreen="app">
        {currentScreen === 'app' && <MainApp />}
      </ScreenErrorBoundary>
    </div>
  )
}
