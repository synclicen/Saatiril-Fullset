---
Task ID: 1
Agent: main
Task: Fix APK/Portable download "belum tersedia" issue in Electron portable build

Work Log:
- Analyzed user screenshot: both APK and Portable show "belum tersedia" in portable Electron
- Confirmed GitHub Releases API returns both assets correctly (saatiril-operator.apk + saatiril-portable.exe)
- Identified root cause: admin-dashboard.tsx uses client-side fetch('https://api.github.com/...') which fails in Electron portable due to CORS/network restrictions from localhost renderer
- The Electron main.ts already had an /api/apk-download HTTP route but frontend never used it
- Solution: Use Electron IPC (main process fetches GitHub API via Node.js) instead of renderer-side fetch
- Added 'get-release-info' IPC handler in electron/main.ts (fetches from Node.js, no CORS)
- Added getReleaseInfo() method in electron/preload.ts
- Updated admin-dashboard.tsx to detect Electron and use IPC, fallback to direct fetch for web/dev
- Also improved error messages in catch block to show actual error reason
- TypeScript compilation passes (npx tsc -p electron/tsconfig.json)
- Dev preview verified working at http://localhost:3000
- Committed changes in saatiril-repo but CANNOT push (no GitHub credentials in environment)

Stage Summary:
- Root cause: client-side fetch to api.github.com fails in Electron portable (CORS from localhost)
- Fix: Use IPC → main process (Node.js) fetch instead of renderer fetch
- Files changed: electron/main.ts, electron/preload.ts, admin-dashboard.tsx, .gitignore
- Commit: c70537c "fix: use Electron IPC for GitHub Release info instead of client-side fetch"
- User needs to push to GitHub manually (no credentials available)
