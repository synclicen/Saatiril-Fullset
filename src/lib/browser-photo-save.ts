'use client'

/**
 * SAATIRIL — Browser-mode photo saving via File System Access API.
 *
 * When the operator opens the app in Google Chrome (NOT the Electron desktop
 * wrapper) — which they do intentionally to enable Chrome flags for camera /
 * MediaPipe performance — `window.saatirilAPI.savePhoto` is unavailable.
 *
 * Previously the app just showed a toast "Mode Browser — Foto Tidak ke Disk"
 * and silently discarded the photo. For a live event with 4000–5000 photos
 * that is unacceptable.
 *
 * This module uses the File System Access API (Chrome / Edge / Opera only) to
 * let the operator pick a real folder on disk ONCE, then writes every captured
 * photo there as a real .jpg file — exactly like the Electron path does.
 *
 * The directory handle is persisted in IndexedDB so it survives page reloads
 * (the user only re-grants permission with a single click after reload, no
 * re-picking required).
 *
 * Fallback: if the File System Access API is unavailable (Firefox / Safari /
 * old Chrome), we trigger a browser download of the .jpg so the photo is at
 * least not lost.
 */

// ─── Types ──────────────────────────────────────────────────────────────────
// Minimal type declarations for the File System Access API (not yet in TS lib).
interface FileSystemDirectoryHandleWritable {
  write: (data: BufferSource | Blob | string) => Promise<void>
  close: () => Promise<void>
}
interface FileSystemDirectoryHandleExt {
  getFileHandle: (
    name: string,
    options?: { create?: boolean },
  ) => Promise<FileSystemFileHandle>
  requestPermission: (opts: { mode: 'read' | 'readwrite' }) => Promise<'granted' | 'denied'>
  queryPermission: (opts: { mode: 'read' | 'readwrite' }) => Promise<'granted' | 'denied' | 'prompt'>
}
interface FileSystemFileHandleExt {
  createWritable: () => Promise<FileSystemDirectoryHandleWritable>
}
interface WindowFSAccess {
  showDirectoryPicker?: (opts?: {
    mode?: 'read' | 'readwrite'
    id?: string
    startIn?: string | FileSystemHandle
  }) => Promise<FileSystemDirectoryHandle>
}

// ─── IndexedDB handle persistence ───────────────────────────────────────────
// We store the directory handle in IndexedDB keyed by project id so each
// project can have its own save folder (matching the Electron targetFolder
// behaviour). The handle itself is structured-cloneable and can be stored.
const DB_NAME = 'saatiril_fs'
const DB_STORE = 'dir_handles'
const DB_VERSION = 1

function openFsDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION)
    req.onupgradeneeded = () => {
      const db = req.result
      if (!db.objectStoreNames.contains(DB_STORE)) {
        db.createObjectStore(DB_STORE)
      }
    }
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error)
  })
}

async function idbGet<T>(key: string): Promise<T | undefined> {
  try {
    const db = await openFsDb()
    return new Promise<T | undefined>((resolve, reject) => {
      const tx = db.transaction(DB_STORE, 'readonly')
      const req = tx.objectStore(DB_STORE).get(key)
      req.onsuccess = () => resolve(req.result as T | undefined)
      req.onerror = () => reject(req.error)
    })
  } catch {
    return undefined
  }
}

async function idbSet(key: string, value: unknown): Promise<void> {
  try {
    const db = await openFsDb()
    return new Promise<void>((resolve, reject) => {
      const tx = db.transaction(DB_STORE, 'readwrite')
      tx.objectStore(DB_STORE).put(value, key)
      tx.oncomplete = () => resolve()
      tx.onerror = () => reject(tx.error)
    })
  } catch {
    /* ignore — best effort persistence */
  }
}

async function idbDelete(key: string): Promise<void> {
  try {
    const db = await openFsDb()
    return new Promise<void>((resolve, reject) => {
      const tx = db.transaction(DB_STORE, 'readwrite')
      tx.objectStore(DB_STORE).delete(key)
      tx.oncomplete = () => resolve()
      tx.onerror = () => reject(tx.error)
    })
  } catch {
    /* ignore */
  }
}

// ─── Public API ─────────────────────────────────────────────────────────────

/**
 * Whether the browser supports the File System Access API
 * (showDirectoryPicker). Chrome / Edge / Opera do; Firefox / Safari don't.
 */
export function isBrowserSaveSupported(): boolean {
  if (typeof window === 'undefined') return false
  const w = window as unknown as WindowFSAccess
  return typeof w.showDirectoryPicker === 'function'
}

/**
 * Whether we are running inside the Electron desktop wrapper (which exposes
 * window.saatirilAPI.savePhoto). When true, the Electron path is preferred
 * over the File System Access API path.
 */
export function isElectronSaveAvailable(): boolean {
  if (typeof window === 'undefined') return false
  return !!(window as any).saatirilAPI?.savePhoto
}

/**
 * Ask the user to pick a directory for saving photos. The handle is persisted
 * in IndexedDB under `storageKey` so subsequent reloads can reuse it (after a
 * single permission re-grant click).
 *
 * Returns the handle, or null if the user cancelled.
 */
export async function requestBrowserSaveDirectory(
  storageKey: string,
): Promise<FileSystemDirectoryHandle | null> {
  if (!isBrowserSaveSupported()) return null
  const w = window as unknown as WindowFSAccess
  try {
    const handle = await w.showDirectoryPicker!({ mode: 'readwrite' })
    await idbSet(storageKey, handle)
    return handle
  } catch (err: any) {
    // User cancelled — AbortError. Not a real error.
    if (err?.name === 'AbortError') return null
    console.error('[SAATIRIL FS] Failed to pick directory:', err)
    return null
  }
}

/**
 * Get the previously-picked directory handle for `storageKey`, re-requesting
 * permission if needed. Returns null if:
 *  - no handle was ever picked
 *  - the user denies permission
 *  - the API is unavailable
 */
export async function getBrowserSaveDirectory(
  storageKey: string,
): Promise<FileSystemDirectoryHandle | null> {
  if (!isBrowserSaveSupported()) return null
  const handle = await idbGet<FileSystemDirectoryHandle>(storageKey)
  if (!handle) return null

  const ext = handle as unknown as FileSystemDirectoryHandleExt
  // Check current permission state
  let perm = await ext.queryPermission({ mode: 'readwrite' })
  if (perm === 'granted') return handle
  // Request permission (prompts the user on first access after reload)
  perm = await ext.requestPermission({ mode: 'readwrite' })
  return perm === 'granted' ? handle : null
}

/**
 * Check whether a saved directory handle exists (without prompting for
 * permission). Used to decide whether to show the "pick folder" button.
 */
export async function hasBrowserSaveDirectory(storageKey: string): Promise<boolean> {
  if (!isBrowserSaveSupported()) return false
  const handle = await idbGet<FileSystemDirectoryHandle>(storageKey)
  return !!handle
}

/** Remove the stored directory handle (e.g. when switching projects). */
export async function clearBrowserSaveDirectory(storageKey: string): Promise<void> {
  await idbDelete(storageKey)
}

/**
 * Save a base64-encoded JPEG to the picked directory under `filename`.
 *
 * Returns the filename on success, or null on failure (incl. when no directory
 * has been picked yet — caller should prompt the user to pick one first).
 */
export async function savePhotoInBrowser(
  storageKey: string,
  base64Data: string,
  filename: string,
): Promise<string | null> {
  const handle = await getBrowserSaveDirectory(storageKey)
  if (!handle) return null

  try {
    const dirExt = handle as unknown as FileSystemDirectoryHandleExt
    const fileHandle = await dirExt.getFileHandle(filename, { create: true })
    const fileExt = fileHandle as unknown as FileSystemFileHandleExt
    const writable = await fileExt.createWritable()

    // Strip data URL prefix if present
    const base64 = base64Data.replace(/^data:image\/\w+;base64,/, '')
    // Decode base64 to binary
    const byteString = atob(base64)
    const bytes = new Uint8Array(byteString.length)
    for (let i = 0; i < byteString.length; i++) {
      bytes[i] = byteString.charCodeAt(i)
    }
    await writable.write(bytes)
    await writable.close()

    console.log(`[SAATIRIL FS] Photo saved to browser folder: ${filename} (${(bytes.length / 1024).toFixed(1)}KB)`)
    return filename
  } catch (err: any) {
    console.error(`[SAATIRIL FS] Failed to save photo "${filename}":`, err)
    return null
  }
}

/**
 * Last-resort fallback: trigger a browser download of the photo.
 * Used when the File System Access API is unavailable.
 */
export function downloadPhotoFallback(base64Data: string, filename: string): void {
  try {
    const base64 = base64Data.replace(/^data:image\/\w+;base64,/, '')
    const byteString = atob(base64)
    const bytes = new Uint8Array(byteString.length)
    for (let i = 0; i < byteString.length; i++) {
      bytes[i] = byteString.charCodeAt(i)
    }
    const blob = new Blob([bytes], { type: 'image/jpeg' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    setTimeout(() => URL.revokeObjectURL(url), 1000)
    console.log(`[SAATIRIL FS] Photo downloaded as fallback: ${filename}`)
  } catch (err) {
    console.error('[SAATIRIL FS] Download fallback failed:', err)
  }
}

/**
 * Storage key for a project's directory handle. Includes project id so each
 * project can have its own save folder.
 */
export function browserSaveStorageKey(projectId: string): string {
  return `project_${projectId}`
}
