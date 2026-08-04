import { NextRequest, NextResponse } from 'next/server'

// Mark as force-dynamic for static export compatibility
export const dynamic = 'force-dynamic'

// @ts-ignore - static export compat
const GITHUB_REPO = 'synclicen/Saatiril-Fullset'
const RELEASE_TAG = 'latest'

interface ReleaseAssetInfo {
  url: string // API URL (requires auth for download)
  browserUrl: string // Direct download URL (public)
  size: number
  sizeMB: string
  lastModified: string
  assetName: string
}

interface CachedRelease {
  apk: ReleaseAssetInfo | null
  portable: ReleaseAssetInfo | null
}

// Cache the release info for 5 minutes to avoid hitting GitHub API rate limits
let cachedRelease: CachedRelease | null = null
let cacheTime = 0
const CACHE_TTL = 5 * 60 * 1000 // 5 minutes

async function getGitHubToken(): Promise<string> {
  // Try to get token from git remote
  try {
    const { execSync } = await import('child_process')
    const remoteUrl = execSync('git remote get-url origin', { encoding: 'utf-8' }).trim()
    const match = remoteUrl.match(/:\/\/[^:]*:([^@]*)@/)
    if (match?.[1]) return match[1]
  } catch {
    // git not available
  }

  // Fallback: try env variable
  if (process.env.GITHUB_TOKEN) return process.env.GITHUB_TOKEN

  return ''
}

async function fetchLatestReleaseInfo(): Promise<CachedRelease> {
  const now = Date.now()
  if (cachedRelease && (now - cacheTime) < CACHE_TTL) {
    return cachedRelease
  }

  const token = await getGitHubToken()
  const headers: Record<string, string> = {
    Accept: 'application/vnd.github+json',
  }
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }

  const res = await fetch(`https://api.github.com/repos/${GITHUB_REPO}/releases/tags/${RELEASE_TAG}`, { headers })

  if (!res.ok) {
    throw new Error(`GitHub API returned ${res.status}`)
  }

  const release = await res.json()
  const assets = release.assets || []

  const apkAsset = assets.find((a: { name: string }) => a.name.endsWith('.apk'))
  const portableAsset = assets.find((a: { name: string }) => a.name.endsWith('-portable.exe') || a.name === 'saatiril-portable.exe')

  const toAssetInfo = (a: { url: string; browser_download_url: string; size: number; updated_at: string; name: string }): ReleaseAssetInfo => ({
    url: a.url,
    browserUrl: a.browser_download_url,
    size: a.size,
    sizeMB: (a.size / (1024 * 1024)).toFixed(1),
    lastModified: a.updated_at || release.published_at,
    assetName: a.name,
  })

  cachedRelease = {
    apk: apkAsset ? toAssetInfo(apkAsset) : null,
    portable: portableAsset ? toAssetInfo(portableAsset) : null,
  }
  cacheTime = now

  return cachedRelease
}

// GET /api/apk-download — Returns release info for both APK and Portable
export async function GET() {
  try {
    const info = await fetchLatestReleaseInfo()
    return NextResponse.json({
      apk: info.apk ? {
        available: true,
        sizeMB: info.apk.sizeMB,
        assetName: info.apk.assetName,
        lastModified: info.apk.lastModified,
      } : {
        available: false,
        error: 'No APK asset found in latest release',
      },
      portable: info.portable ? {
        available: true,
        sizeMB: info.portable.sizeMB,
        assetName: info.portable.assetName,
        lastModified: info.portable.lastModified,
      } : {
        available: false,
        error: 'No Portable asset found in latest release',
      },
    })
  } catch (error) {
    const message = error instanceof Error ? error.message : 'Unknown error'
    return NextResponse.json({
      apk: { available: false, error: message },
      portable: { available: false, error: message },
    })
  }
}

// POST /api/apk-download — Proxies the download from GitHub Releases
// Body: { type: 'apk' | 'portable' }
export async function POST(request: NextRequest) {
  try {
    const body = await request.json().catch(() => ({}))
    const type = body.type || 'apk'
    const info = await fetchLatestReleaseInfo()

    const assetInfo = type === 'portable' ? info.portable : info.apk
    if (!assetInfo) {
      return NextResponse.json(
        { error: `${type === 'portable' ? 'Portable' : 'APK'} not available in latest release` },
        { status: 404 }
      )
    }

    const token = await getGitHubToken()
    const headers: Record<string, string> = {
      Accept: 'application/octet-stream',
    }
    if (token) {
      headers.Authorization = `Bearer ${token}`
    }

    const response = await fetch(assetInfo.url, { headers })

    if (!response.ok) {
      throw new Error(`GitHub download returned ${response.status}`)
    }

    const buffer = await response.arrayBuffer()

    const contentType = type === 'portable'
      ? 'application/x-msdownload'
      : 'application/vnd.android.package-archive'
    const filename = type === 'portable'
      ? 'saatiril-portable.exe'
      : 'saatiril-operator.apk'

    return new NextResponse(buffer, {
      status: 200,
      headers: {
        'Content-Type': contentType,
        'Content-Disposition': `attachment; filename="${filename}"`,
        'Content-Length': buffer.byteLength.toString(),
      },
    })
  } catch (error) {
    const message = error instanceof Error ? error.message : 'Download failed'
    return NextResponse.json({ error: message }, { status: 500 })
  }
}
