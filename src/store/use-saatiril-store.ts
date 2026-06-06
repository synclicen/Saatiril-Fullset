'use client'

import { create } from 'zustand'

export type StudentStatus = 'pending' | 'done' | `active_${number}`

export interface Student {
  id: string
  nim: string
  nama: string
  status: StudentStatus
  assignedChannel: number
}

export interface ProjectConfig {
  mode: 'single' | 'dual'
  ratio: string
  preset: string
  targetFolder: string
  frame: string | null
}

export interface PhotoHistoryItem {
  student: Student
  photos: string[]
  channel: number
}

export interface Project {
  id: string
  name: string
  config: ProjectConfig
  database: Student[]
  photoHistory: PhotoHistoryItem[]
}

export type Role = 'admin' | 'mc' | 'operator'
export type AppScreen = 'hub' | 'setup' | 'app'
export type AppTab = 'admin' | 'mc' | 'operator'

// ─── Memory guard: max photo history items kept in memory ──────────────────
// With thousands of participants, we can't keep all base64 photos in memory.
// Admin keeps last N items for live gallery; MC/Operator only need current target.
// Photos are still saved to disk by the Operator's SYNC_DB handler.
const MAX_PHOTO_HISTORY_IN_MEMORY = 200

// ─── Frame storage: separate localStorage keys for frame base64 data ──────────
// Frame data can be 500KB-2MB and must survive page reloads.
// We store it in separate localStorage keys so it's not lost when
// the main project list is saved with '__FRAME_SAVED__' markers.
const FRAME_KEY_PREFIX = 'saatiril_frame_'

function saveFrameToStorage(projectId: string, frameData: string | null) {
  try {
    if (frameData && frameData !== '__FRAME_SAVED__') {
      localStorage.setItem(`${FRAME_KEY_PREFIX}${projectId}`, frameData)
    } else {
      localStorage.removeItem(`${FRAME_KEY_PREFIX}${projectId}`)
    }
  } catch (e) {
    console.error('[SAATIRIL] Failed to save frame to separate storage:', e)
  }
}

function loadFrameFromStorage(projectId: string): string | null {
  try {
    return localStorage.getItem(`${FRAME_KEY_PREFIX}${projectId}`)
  } catch (e) {
    console.error('[SAATIRIL] Failed to load frame from separate storage:', e)
    return null
  }
}

function removeFrameFromStorage(projectId: string) {
  try {
    localStorage.removeItem(`${FRAME_KEY_PREFIX}${projectId}`)
  } catch (e) {
    console.error('[SAATIRIL] Failed to remove frame from separate storage:', e)
  }
}

// ─── Student status priority for merge ───────────────────────────────────────
// When merging databases from different clients, we keep the "most advanced" status.
// pending (0) < active_N (1) < done (2)
function getStatusPriority(status: StudentStatus): number {
  if (status === 'pending') return 0
  if (status === 'done') return 2
  // active_N statuses get priority 1
  return 1
}

/**
 * Merge two student databases, keeping the "most advanced" status for each student.
 * This prevents data regression when SYNC_DB payloads from different channels
 * overwrite each other's progress in dual mode.
 */
export function mergeDatabases(
  localDb: Student[],
  incomingDb: Student[],
): Student[] {
  const studentMap = new Map<string, Student>()

  // Add all local students
  for (const s of localDb) {
    studentMap.set(s.id, s)
  }

  // Merge incoming — only update if incoming status is more advanced
  for (const s of incomingDb) {
    const existing = studentMap.get(s.id)
    if (!existing) {
      studentMap.set(s.id, s)
    } else {
      const existingPriority = getStatusPriority(existing.status)
      const incomingPriority = getStatusPriority(s.status)
      if (incomingPriority > existingPriority) {
        studentMap.set(s.id, s)
      }
    }
  }

  return Array.from(studentMap.values())
}

/**
 * Strip frame base64 data from a project for SYNC_DB transmission.
 * Frame data can be 500KB-2MB and doesn't need to be re-sent every time.
 * Recipients who already have the frame don't need it again.
 * 
 * NOTE: The initial REQUEST_STATE response should NOT use this — new clients
 * need the frame data. Only use this for subsequent SYNC_DB updates.
 */
export function stripFrameForSync(project: Project): Project {
  if (!project.config.frame) return project
  return {
    ...project,
    config: { ...project.config, frame: '__FRAME_SAVED__' },
  }
}

/**
 * Preserve frame data when receiving SYNC_DB with '__FRAME_SAVED__' marker.
 * 
 * When a client receives a SYNC_DB where the frame was stripped (marked as
 * '__FRAME_SAVED__'), this function keeps the existing frame data from the
 * current project. This ensures the frame overlay stays visible on the
 * operator camera and photos continue to be captured with the frame applied.
 */
export function preserveFrameOnSync(
  incomingConfig: ProjectConfig,
  existingConfig: ProjectConfig | undefined,
): ProjectConfig {
  if (
    incomingConfig.frame === '__FRAME_SAVED__' &&
    existingConfig?.frame &&
    existingConfig.frame !== '__FRAME_SAVED__'
  ) {
    return { ...incomingConfig, frame: existingConfig.frame }
  }
  // If incoming has actual frame data, or no existing frame, use incoming as-is
  return incomingConfig
}

// ─── Debounced save ───────────────────────────────────────────────────────
let saveTimeout: ReturnType<typeof setTimeout> | null = null
const SAVE_DEBOUNCE_MS = 500 // Debounce saves to avoid thrashing localStorage

interface SaatirilState {
  // Projects
  projects: Project[]
  currentProject: Project | null

  // User role & channel
  myRole: Role
  myChannel: number

  // Screen & Tab
  currentScreen: AppScreen
  currentTab: AppTab

  // Operator state
  opCurrentTarget: Student | null
  opCapturedPhotos: string[]

  // Actions
  setProjects: (projects: Project[]) => void
  addProject: (project: Project) => void
  deleteProject: (id: string) => void
  setCurrentProject: (project: Project | null) => void
  updateCurrentProject: (project: Project) => void
  setMyRole: (role: Role) => void
  setMyChannel: (channel: number) => void
  setCurrentScreen: (screen: AppScreen) => void
  setCurrentTab: (tab: AppTab) => void
  setOpCurrentTarget: (target: Student | null) => void
  setOpCapturedPhotos: (photos: string[]) => void
  addOpCapturedPhoto: (photo: string) => void
  resetOpState: () => void
  loadProjectsFromStorage: () => void
  saveProjectsToStorage: () => void
  saveProjectsToStorageNow: () => void
  updateStudentStatus: (studentId: string, status: StudentStatus) => void
}

/**
 * Trim photoHistory to prevent memory bloat with thousands of participants.
 * Only keeps the most recent N items (by array order = chronological).
 * Photo data is still saved to disk via the Operator's file save logic.
 */
function trimPhotoHistory(history: PhotoHistoryItem[]): PhotoHistoryItem[] {
  if (history.length <= MAX_PHOTO_HISTORY_IN_MEMORY) return history
  // Keep the most recent items (last N)
  return history.slice(history.length - MAX_PHOTO_HISTORY_IN_MEMORY)
}

export const useSaatirilStore = create<SaatirilState>((set, get) => ({
  projects: [],
  currentProject: null,
  myRole: 'admin',
  myChannel: 1,
  currentScreen: 'hub',
  currentTab: 'admin',
  opCurrentTarget: null,
  opCapturedPhotos: [],

  setProjects: (projects) => set({ projects }),
  addProject: (project) => set((s) => {
    // Save frame data to separate localStorage key immediately
    saveFrameToStorage(project.id, project.config.frame)
    return { projects: [...s.projects, project] }
  }),
  deleteProject: (id) => set((s) => {
    const newProjects = s.projects.filter(p => p.id !== id)
    const shouldClearCurrent = s.currentProject?.id === id
    // Remove frame data from separate localStorage key
    removeFrameFromStorage(id)
    return {
      projects: newProjects,
      ...(shouldClearCurrent ? { currentProject: null } : {}),
    }
  }),
  setCurrentProject: (project) => {
    // Ensure frame data is in separate storage when setting current project
    if (project) {
      saveFrameToStorage(project.id, project.config.frame)
    }
    set({ currentProject: project })
  },
  updateCurrentProject: (project) => set((s) => {
    // Auto-trim photo history to prevent memory bloat
    const trimmedProject = {
      ...project,
      photoHistory: trimPhotoHistory(project.photoHistory),
    }
    // Save frame data to separate localStorage key
    saveFrameToStorage(trimmedProject.id, trimmedProject.config.frame)
    const idx = s.projects.findIndex(p => p.id === trimmedProject.id)
    const newProjects = [...s.projects]
    if (idx !== -1) newProjects[idx] = trimmedProject
    return { currentProject: trimmedProject, projects: newProjects }
  }),
  setMyRole: (role) => set({ myRole: role }),
  setMyChannel: (channel) => set({ myChannel: channel }),
  setCurrentScreen: (screen) => set({ currentScreen: screen }),
  setCurrentTab: (tab) => set({ currentTab: tab }),
  setOpCurrentTarget: (target) => set({ opCurrentTarget: target }),
  setOpCapturedPhotos: (photos) => set({ opCapturedPhotos: photos }),
  addOpCapturedPhoto: (photo) => set((s) => ({ opCapturedPhotos: [...s.opCapturedPhotos, photo] })),
  resetOpState: () => set({ opCurrentTarget: null, opCapturedPhotos: [] }),

  loadProjectsFromStorage: () => {
    try {
      // Simple marker check: if no version marker, this is a fresh start
      // (Electron main process handles version-change clearing before first load)
      const storedVersion = localStorage.getItem('saatiril_app_version')
      const isElectron = typeof window !== 'undefined' && !!(window as any).saatirilAPI?.isElectron
      const currentVersion = isElectron ? 'electron' : 'web'

      if (!storedVersion) {
        // First time or fresh install — ensure clean state
        localStorage.removeItem('saatiril_projects')
        localStorage.setItem('saatiril_app_version', currentVersion)
        return
      }

      // Load saved projects
      const saved = localStorage.getItem('saatiril_projects')
      if (saved) {
        const projects = JSON.parse(saved)
        // Restore frame data from separate localStorage keys
        // (frames are saved separately because they're too large for the main JSON)
        const restoredProjects = projects.map((p: Project) => {
          const savedFrame = loadFrameFromStorage(p.id)
          if (savedFrame && (!p.config.frame || p.config.frame === '__FRAME_SAVED__')) {
            console.log(`[SAATIRIL] Restored frame for project: ${p.name}`)
            return { ...p, config: { ...p.config, frame: savedFrame } }
          }
          return p
        })
        set({ projects: restoredProjects })
      }
    } catch (e) {
      console.error('Failed to load projects from storage', e)
    }
  },

  saveProjectsToStorage: () => {
    // Debounced — prevents localStorage thrashing during rapid state changes
    if (saveTimeout) clearTimeout(saveTimeout)
    saveTimeout = setTimeout(() => {
      try {
        const { projects } = get()
        // Save frame data to separate localStorage keys first
        for (const p of projects) {
          saveFrameToStorage(p.id, p.config.frame)
        }
        // Save lightweight metadata (no base64 photos, frame in separate keys)
        // so admin gallery shows entries after reload (just without thumbnails)
        const safeProjects = projects.map(p => ({
          ...p,
          photoHistory: p.photoHistory.map(h => ({ ...h, photos: [] })),
          config: { ...p.config, frame: p.config.frame ? '__FRAME_SAVED__' : null },
        }))
        localStorage.setItem('saatiril_projects', JSON.stringify(safeProjects))
        console.log('[SAATIRIL] Projects saved to localStorage (debounced)')
      } catch (e) {
        console.error('Failed to save projects to storage', e)
      }
    }, SAVE_DEBOUNCE_MS)
  },

   saveProjectsToStorageNow: () => {
    // IMMEDIATE save — use before navigation to prevent data loss
    if (saveTimeout) { clearTimeout(saveTimeout); saveTimeout = null }
    try {
      const { projects } = get()
      // Save frame data to separate localStorage keys first
      for (const p of projects) {
        saveFrameToStorage(p.id, p.config.frame)
      }
      // Save lightweight metadata (no base64 photos, frame in separate keys)
      const safeProjects = projects.map(p => ({
        ...p,
        photoHistory: p.photoHistory.map(h => ({ ...h, photos: [] })),
        config: { ...p.config, frame: p.config.frame ? '__FRAME_SAVED__' : null },
      }))
      localStorage.setItem('saatiril_projects', JSON.stringify(safeProjects))
      console.log('[SAATIRIL] Projects saved to localStorage (immediate)')
    } catch (e) {
      console.error('Failed to save projects to storage (immediate)', e)
    }
  },

  updateStudentStatus: (studentId, status) => set((s) => {
    if (!s.currentProject) return {}
    const newDb = s.currentProject.database.map(st =>
      st.id === studentId ? { ...st, status } : st
    )
    const updatedProject = { ...s.currentProject, database: newDb }
    const idx = s.projects.findIndex(p => p.id === updatedProject.id)
    const newProjects = [...s.projects]
    if (idx !== -1) newProjects[idx] = updatedProject
    return { currentProject: updatedProject, projects: newProjects }
  }),
}))
