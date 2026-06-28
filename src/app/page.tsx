'use client'

import { useEffect, useCallback, useLayoutEffect, useState, Component, ReactNode } from 'react'
import { useSaatirilStore, sanitizeProject } from '@/store/use-saatiril-store'
import { ProjectHub } from '@/components/saatiril/project-hub'
import ProjectSetup from '@/components/saatiril/project-setup'
import { MainApp } from '@/components/saatiril/main-app'
import { LicenseGate } from '@/components/saatiril/license-gate'
import { Button } from '@/components/ui/button'
import { AlertTriangle, Wrench, Home as HomeIcon } from 'lucide-react'

// ─── Screen-level Error Boundary ──────────────────────────────────────────
// Catches render errors in individual screens so the entire app doesn't crash.
// This is critical: if ProjectSetup or MainApp throws during render,
// the user can still go back to the hub instead of seeing a blank screen.
//
// CRITICAL FOR EVENTS: If the crash was caused by corrupted project data
// (e.g. a photoHistory entry with missing student.nama), simply going back
// to the hub and reopening the project would crash AGAIN (infinite loop)
// because the corrupted data persists in localStorage.
//
// The "Perbaiki Data & Buka Ulang" button sanitizes the current project's
// data (removing corrupted entries) so reopening succeeds.
interface ErrorBoundaryProps {
  children: ReactNode
  fallbackScreen: 'hub' | 'setup' | 'app'
}

interface ErrorBoundaryState {
  hasError: boolean
  error: Error | null
  repairAttempted: boolean
}

class ScreenErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  constructor(props: ErrorBoundaryProps) {
    super(props)
    this.state = { hasError: false, error: null, repairAttempted: false }
  }

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { hasError: true, error, repairAttempted: false }
  }

  componentDidCatch(error: Error, errorInfo: React.ErrorInfo) {
    console.error(`[SAATIRIL] Screen render error (${this.props.fallbackScreen}):`, error, errorInfo)
  }

  handleReset = () => {
    this.setState({ hasError: false, error: null, repairAttempted: false })
    // Navigate back to hub on error recovery
    useSaatirilStore.getState().setCurrentScreen('hub')
  }

  // CRITICAL: Repair the current project by sanitizing its data (removing
  // corrupted photoHistory entries, ensuring all student fields are strings).
  // This breaks the crash-on-reopen loop.
  handleRepair = () => {
    const store = useSaatirilStore.getState()
    if (store.currentProject) {
      const repaired = sanitizeProject(store.currentProject)
      console.log('[SAATIRIL] Repairing current project — sanitized data', {
        before: store.currentProject.photoHistory.length,
        after: repaired.photoHistory.length,
      })
      store.updateCurrentProject(repaired)
      store.saveProjectsToStorageNow()
    }
    // Also sanitize all projects in the list
    const allProjects = store.projects.map(sanitizeProject)
    store.setProjects(allProjects)
    store.saveProjectsToStorageNow()

    this.setState({ hasError: false, error: null, repairAttempted: true })
  }

  render() {
    if (this.state.hasError) {
      const isTrimError = this.state.error?.message?.includes('trim') ||
                         this.state.error?.message?.includes('undefined')
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
          <div className="flex flex-col sm:flex-row gap-2 mt-2">
            {/* CRITICAL: Repair button — sanitizes corrupted project data so
                reopening doesn't crash again. Shown prominently when the error
                looks like a data-corruption issue (trim/undefined). */}
            {isTrimError && (
              <Button
                onClick={this.handleRepair}
                className="bg-emerald-600 text-white hover:bg-emerald-700 font-semibold gap-2"
              >
                <Wrench className="size-4" />
                Perbaiki Data & Buka Ulang
              </Button>
            )}
            <Button
              onClick={this.handleReset}
              variant="outline"
              className="gap-2"
            >
              <HomeIcon className="size-4" />
              Kembali ke Halaman Utama
            </Button>
          </div>
          {isTrimError && (
            <p className="max-w-md text-center text-[11px] text-emerald-400/60 mt-2">
              Tombol &quot;Perbaiki Data&quot; akan membersihkan data peserta yang rusak
              (penyebab error) tanpa menghapus foto yang sudah tersimpan di disk.
            </p>
          )}
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

  // ── Check if LAN client (MC/Operator) — they bypass license check ──────────
  const [isLanClient] = useState(() => {
    if (typeof window === 'undefined') return false
    const params = new URLSearchParams(window.location.search)
    const roleParam = params.get('role')
    return roleParam === 'mc' || roleParam === 'operator'
  })

  // License is valid if: LAN client (no license needed) OR license gate passes
  const [licenseValid, setLicenseValid] = useState(isLanClient)

  // ── URL parameter routing for LAN clients (MC/Operator) ─────────────────
  // Detect role from URL and bypass hub/setup/license screens for non-admin clients.
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

  // ── License gate: show lock screen until license is valid ──────────────
  // In Electron: LicenseGate checks license status, shows activation UI if invalid
  // In browser (LAN client): LicenseGate auto-bypasses (no Electron API available)
  if (!licenseValid) {
    return <LicenseGate onLicenseValid={() => setLicenseValid(true)} />
  }

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
