import { NextRequest, NextResponse } from 'next/server'
import { writeFile, unlink, stat } from 'fs/promises'
import path from 'path'

const APK_PATH = path.join(process.cwd(), 'public', 'saatiril-operator.apk')

export async function POST(request: NextRequest) {
  try {
    const formData = await request.formData()
    const file = formData.get('apk') as File | null

    if (!file) {
      return NextResponse.json({ error: 'No file uploaded' }, { status: 400 })
    }

    // Validate it's an APK file
    if (!file.name.endsWith('.apk')) {
      return NextResponse.json({ error: 'File must be an .apk file' }, { status: 400 })
    }

    // Validate file size (max 100MB)
    if (file.size > 100 * 1024 * 1024) {
      return NextResponse.json({ error: 'File too large (max 100MB)' }, { status: 400 })
    }

    const bytes = await file.arrayBuffer()
    const buffer = Buffer.from(bytes)

    // Remove old APK if exists
    try {
      await unlink(APK_PATH)
    } catch {
      // File might not exist, that's ok
    }

    // Write new APK
    await writeFile(APK_PATH, buffer)

    return NextResponse.json({
      success: true,
      fileName: file.name,
      size: file.size,
      sizeMB: (file.size / (1024 * 1024)).toFixed(1),
    })
  } catch (error) {
    console.error('APK upload error:', error)
    return NextResponse.json({ error: 'Upload failed' }, { status: 500 })
  }
}

export async function GET() {
  try {
    const stats = await stat(APK_PATH).catch(() => null)

    if (!stats) {
      return NextResponse.json({ exists: false })
    }

    return NextResponse.json({
      exists: true,
      size: stats.size,
      sizeMB: (stats.size / (1024 * 1024)).toFixed(1),
      lastModified: stats.mtime.toISOString(),
    })
  } catch {
    return NextResponse.json({ exists: false })
  }
}

export async function DELETE() {
  try {
    await unlink(APK_PATH)
    return NextResponse.json({ success: true })
  } catch {
    return NextResponse.json({ error: 'No APK to delete' }, { status: 404 })
  }
}
