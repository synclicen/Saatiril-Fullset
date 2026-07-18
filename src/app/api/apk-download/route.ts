import { NextRequest, NextResponse } from 'next/server'

const GITHUB_REPO = 'synclicen/Saatiril-Fullset'
const RELEASE_TAG = 'latest'

// Cache the APK info for 5 minutes to avoid hitting GitHub API rate limits
let cachedApkInfo: { url: string; size: number; sizeMB: string; lastModified: string; assetName: string } | null = null
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

async function fetchLatestReleaseInfo() {
  const now = Date.now()
  if (cachedApkInfo && (now - cacheTime) < CACHE_TTL) {
    return cachedApkInfo
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
  const apkAsset = release.assets?.find((a: { name: string }) => a.name.endsWith('.apk'))

  if (!apkAsset) {
    throw new Error('No APK asset found in latest release')
  }

  cachedApkInfo = {
    url: apkAsset.url, // This is the API URL that requires auth
    size: apkAsset.size,
    sizeMB: (apkAsset.size / (1024 * 1024)).toFixed(1),
    lastModified: apkAsset.updated_at || release.published_at,
    assetName: apkAsset.name,
  }
  cacheTime = now

  return cachedApkInfo
}

// GET /api/apk-download — Returns APK info (status check)
export async function GET() {
  try {
    const info = await fetchLatestReleaseInfo()
    return NextResponse.json({
      available: true,
      sizeMB: info.sizeMB,
      assetName: info.assetName,
      lastModified: info.lastModified,
    })
  } catch (error) {
    const message = error instanceof Error ? error.message : 'Unknown error'
    return NextResponse.json({
      available: false,
      error: message,
    })
  }
}

// POST /api/apk-download — Proxies the APK download from GitHub Releases
export async function POST(request: NextRequest) {
  try {
    const info = await fetchLatestReleaseInfo()

    const token = await getGitHubToken()
    const headers: Record<string, string> = {
      Accept: 'application/octet-stream',
    }
    if (token) {
      headers.Authorization = `Bearer ${token}`
    }

    const response = await fetch(info.url, { headers })

    if (!response.ok) {
      throw new Error(`GitHub download returned ${response.status}`)
    }

    const apkBuffer = await response.arrayBuffer()

    return new NextResponse(apkBuffer, {
      status: 200,
      headers: {
        'Content-Type': 'application/vnd.android.package-archive',
        'Content-Disposition': `attachment; filename="saatiril-operator.apk"`,
        'Content-Length': apkBuffer.byteLength.toString(),
      },
    })
  } catch (error) {
    const message = error instanceof Error ? error.message : 'Download failed'
    return NextResponse.json({ error: message }, { status: 500 })
  }
}
