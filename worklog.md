---
Task ID: 1
Agent: Main Agent
Task: Fix APK download not working in portable Electron version

Work Log:
- Investigated the root cause: Electron workflow removes `src/app/api` directory before building static export, so `/api/apk-download` route doesn't exist in portable version
- Verified GitHub Release with tag "latest" exists and contains both APK (18.3 MB) and Portable (117.2 MB) assets
- Fixed `admin-dashboard.tsx`: Replaced server-side API dependency (`fetch('/api/apk-download')`) with direct client-side GitHub API call
- Updated state types to include `downloadUrl` field for direct GitHub download URLs
- Fixed fallback download URL pattern: `/releases/download/latest/` instead of `/releases/latest/download/` (latter fails for prerelease releases)
- Updated all 6 download buttons (3 APK + 3 Portable across single/dual-photoshoot/dual-camera modes) to use `window.open(url, '_blank')` with direct GitHub URLs
- Updated `showApkQrCode`, `showPortableQrCode`, `copyApkLink`, `copyPortableLink` to use synchronous `generateDownloadLink()`
- Added `/api/apk-download` route handler to `electron/main.ts` for LAN proxy support (GET for info, POST for proxy download)
- Pushed changes to GitHub repo `synclicen/Saatiril-Fullset`

Stage Summary:
- Root cause: Frontend depended on server-side API route that doesn't exist in portable Electron build
- Fix: Client-side GitHub API call works in both dev and portable environments
- Additional fix: Electron main.ts now includes API route handler for LAN proxy support
- GitHub push confirmed: commit 2c65940 pushed to main
- Both APK (18.3 MB) and Portable (117.2 MB) are available in GitHub Releases
