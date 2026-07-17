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
