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
})
