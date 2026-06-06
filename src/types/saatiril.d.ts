// Global type declarations for SAATIRIL

interface SaatirilAPI {
  isElectron: boolean
  selectFolder: (defaultPath: string) => Promise<string | null>
  createFolder: (path: string) => Promise<{ success: boolean; path?: string; error?: string }>
  savePhoto: (data: { base64Data: string; filename: string; targetFolder: string }) => Promise<string | null>
  getLanInfo: () => Promise<{
    httpPort: number
    socketPort: number
    ips: { name: string; address: string }[]
  }>
}

declare global {
  interface Window {
    saatirilAPI: SaatirilAPI
  }
}

export {}
