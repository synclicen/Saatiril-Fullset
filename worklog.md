---
Task ID: 4
Agent: Main
Task: Fix operator.html to match APK behavior for ALL capture modes

Work Log:
- Read APK OperatorViewModel.kt to understand CameraModes: single, dual, single-photoshoot, dual-photoshoot
- Read APK's MC_CALL, STUDENT_RESET, SYNC_DB handlers to understand correct flow
- Read operator.html to find all differences from APK behavior
- Added helper functions: isPhotoshootMode(), isDualMode(), photosPerSession(), channelCount(), isActiveStatus()
- Added mcCallBuffer and photoHistory state variables
- Fixed MC_CALL handler: photoshoot mode adds to buffer (operator selects manually), accepts any channel; non-photoshoot auto-sets target, filters by channel
- Fixed STUDENT_RESET handler: photoshoot mode accepts from any channel, cleans mcCallBuffer
- Fixed SYNC_DB recovery: only recovers active student in non-photoshoot modes, cleans mcCallBuffer
- Fixed updateQueueList: photoshoot mode combines DB sent students + mcCallBuffer with photoHistory filtering; non-photoshoot shows channel students
- Fixed selectQueueItem/selectOpSearchItem: removes from mcCallBuffer when selected
- Fixed sendPhotos: updates photoHistory, cleans mcCallBuffer, updates local DB status
- Replaced all 12 instances of mode?.includes('photoshoot') with isPhotoshootMode()
- Fixed operator-panel.tsx MC_CALL handler: photoshoot mode accepts from any channel (matching APK)
- Fixed operator-panel.tsx STUDENT_RESET handler: photoshoot mode accepts from any channel

Stage Summary:
- operator.html now has identical capture mode logic to APK for all 4 modes
- mcCallBuffer mechanism implemented for photoshoot modes (operator selects manually from queue)
- Channel filtering fixed: photoshoot modes accept events from any channel
- photoHistory tracking added for correct queue filtering
- Both operator.html and operator-panel.tsx now match APK behavior

---
Task ID: 5
Agent: Main
Task: Add Portable Windows download to admin dashboard alongside APK

Work Log:
- Analyzed uploaded images: APK download works but Portable doesn't appear
- Discovered root cause: Electron workflow only uploads artifacts to GitHub Actions, NOT to GitHub Release
- The Android workflow was deleting and recreating the release each time, wiping any Electron assets
- Updated Electron workflow (build-electron.yml): changed permissions from contents:read to contents:write, added steps to rename portable exe and upload to GitHub Release
- Updated Android workflow (build-android.yml): changed from deleting entire release to just replacing APK asset, preserving portable exe from Electron build
- Both workflows now share the same 'latest' release tag with their respective assets
- Updated API endpoint (/api/apk-download/route.ts): now returns both APK and portable info from GitHub Release, supports type=portable parameter in POST for downloading portable
- Updated admin dashboard: added portableInfo state, generatePortableLink/copyPortableLink/showPortableQrCode functions
- Added "Saatiril Portable Windows" download section with cyan/teal theme in all 3 mode views (single, dual-photoshoot, dual)
- Pushed workflow changes to GitHub repo (commit 5a847cd)
- Both GitHub Actions builds succeeded, release now has both saatiril-operator.apk (18.3 MB) and saatiril-portable.exe (117.2 MB)
- Verified API returns both assets as available
- Lint passes cleanly

Stage Summary:
- Both APK (18.3 MB) and Portable (117.2 MB) are now available in GitHub Release
- Electron workflow uploads portable exe to shared 'latest' release
- Android workflow no longer deletes entire release, just replaces APK asset
- API endpoint returns both APK and portable availability info
- Admin dashboard shows both download sections with Download/Copy/QR buttons
- Portable uses cyan/teal color theme to distinguish from green APK section
