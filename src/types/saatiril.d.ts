// Global type declarations for SAATIRIL

interface LicenseStatusResult {
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
}

interface LicenseActivationResult {
  success: boolean
  error?: string
  licenseType?: string
}

interface MachineIdResult {
  machineId: string
  displayMachineId: string
}

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
  // License API
  getLicenseStatus: () => Promise<LicenseStatusResult>
  activateLicense: (activationCode: string) => Promise<LicenseActivationResult>
  getMachineId: () => Promise<MachineIdResult>
}

declare global {
  interface Window {
    saatirilAPI: SaatirilAPI
  }
}

export {}
