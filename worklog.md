---
Task ID: 1
Agent: Main Agent
Task: Fix Dual Mode speed issues — MC to Operator communication is too slow

Work Log:
- Analyzed the complete event flow for all 4 modes (Single, Dual, Single Photoshoot, Dual Photoshoot)
- Read MC panel (1064 lines), Operator panel (~1450 lines), Socket service (250 lines), Admin dashboard (936 lines), Store (406 lines), Socket client (392 lines)
- Identified 4 critical bottlenecks causing slow Dual Mode:
  1. SYNC_DB sends ENTIRE photoHistory with base64 photos (grows from KB to hundreds of MB over time) — ROOT CAUSE
  2. MC blocked waiting for heavy PHOTOS_SAVED payload (~4MB base64) before unblocking
  3. 400ms of unnecessary setTimeout delays in operator's finalizeCapture (100ms + 300ms)
  4. No photo preservation when receiving SYNC_DB with stripped photos — would wipe local data

- Implemented Fix 1: Strip photos from SYNC_DB's photoHistory
  - Modified `stripFrameForSync()` in use-saatiril-store.ts to also strip photo base64 data
  - This reduces SYNC_DB from potentially hundreds of MB to just a few KB

- Implemented Fix 2: Added STUDENT_DONE lightweight event for immediate MC unblocking
  - Operator emits STUDENT_DONE ({studentId, channel}) BEFORE PHOTOS_SAVED
  - MC listens to STUDENT_DONE for non-photoshoot mode — gets unblocked instantly
  - Admin listens to STUDENT_DONE for immediate live-target clearing
  - Added to CRITICAL_EVENTS set in socket.ts and socket relay server

- Implemented Fix 3: Removed unnecessary 400ms delays in operator completion
  - Removed setTimeout(100ms) before SYNC_DB emission
  - Removed setTimeout(300ms) before resetOpState()
  - Both now execute immediately after photo capture
  - Flash animation reduced from 300ms to 200ms

- Implemented Fix 4: Added preservePhotoHistoryOnSync function
  - New exported function that merges incoming photoHistory with existing
  - Keeps local photos when incoming has stripped (empty) photos
  - Used by all 3 SYNC_DB handlers: MC panel, Operator panel, Admin dashboard

- Additional optimization: Reordered MC event emission
  - MC_CALL now emitted BEFORE SYNC_DB (operator gets student immediately)
  - Previously SYNC_DB was sent first, delaying the operator notification

Stage Summary:
- All 4 bottlenecks fixed systematically
- SYNC_DB payload reduced from MB→KB (most impactful fix)
- MC unblocking is now instant via STUDENT_DONE (no waiting for photo transfer)
- 400ms of delays eliminated per student in operator flow
- Lint passes cleanly with no errors
- Dev server compiles successfully (HTTP 200 responses confirmed)
