/**
 * SAATIRIL — Electron Preload Script
 *
 * Exposes safe IPC methods to the renderer process via window.saatirilAPI.
 * This runs in a sandboxed context with contextIsolation enabled.
 */

import { contextBridge, ipcRenderer } from 'electron'

contextBridge.exposeInMainWorld('saatirilAPI', {
  isElectron: true,

  selectFolder: (defaultPath: string): Promise<string | null> => {
    return ipcRenderer.invoke('select-folder', defaultPath)
  },

  createFolder: (folderPath: string): Promise<{ success: boolean; path?: string; error?: string }> => {
    return ipcRenderer.invoke('create-folder', folderPath)
  },

  savePhoto: (data: { base64Data: string; filename: string; targetFolder: string }): Promise<string | null> => {
    return ipcRenderer.invoke('save-photo', data)
  },

  getLanInfo: (): Promise<{
    httpPort: number
    socketPort: number
    ips: { name: string; address: string }[]
  }> => {
    return ipcRenderer.invoke('get-lan-info')
  },

  // ── License API ──────────────────────────────────────────────────────────
  getLicenseStatus: (): Promise<{
    isValid: boolean
    isGracePeriod: boolean
    isExpired: boolean
    daysRemaining: number
    graceDaysRemaining: number
    licenseType: string | null
    expiresAt: string | null
    machineId: string
    displayMachineId: string
    firstRunDate: string | null
  }> => {
    return ipcRenderer.invoke('get-license-status')
  },

  activateLicense: (activationCode: string): Promise<{
    success: boolean
    error?: string
    licenseType?: string
  }> => {
    return ipcRenderer.invoke('activate-license', activationCode)
  },

  getMachineId: (): Promise<{
    machineId: string
    displayMachineId: string
  }> => {
    return ipcRenderer.invoke('get-machine-id')
  },

  // ── Generate License Code (admin/developer) ────────────────────────────
  generateLicenseCode: (machineId: string, adminKey: string): Promise<{
    success: boolean
    error?: string
    data?: {
      machineId: string
      displayMachineId: string
      licenseType: string
      activationCode: string
      expiresAt: string
      expiresAtFormatted: string
      daysRemaining: number
      verified: boolean
    }
  }> => {
    return ipcRenderer.invoke('generate-license-code', machineId, adminKey)
  },

  // ── Release Info (GitHub Releases) ────────────────────────────────────
  // Uses main-process fetch (Node.js) to avoid CORS issues in Electron
  getReleaseInfo: (): Promise<{
    apk: { available: boolean; sizeMB?: string; assetName?: string; lastModified?: string; downloadUrl?: string; error?: string }
    portable: { available: boolean; sizeMB?: string; assetName?: string; lastModified?: string; downloadUrl?: string; error?: string }
  }> => {
    return ipcRenderer.invoke('get-release-info')
  },
})
