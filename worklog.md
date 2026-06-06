---
Task ID: 1
Agent: main
Task: Fix dual photoshoot mode - MC blocking, operator queue, frame overlay

Work Log:
- Added `sent` status to StudentStatus type in store
- Updated getStatusPriority to handle `sent` status (priority 1, between pending=0 and active_N=2, done=3)
- Rewrote MC panel for non-blocking dual photoshoot flow
- Changed from active_1 status to sent status when MC sends students
- Removed completedChannels tracking (race condition bug)
- Now uses photoHistory-based per-channel completion checking
- Added sent students panel showing per-channel completion badges
- Rewrote operator panel with queue support
- Added MC_CALL buffer for students arriving before database updates
- Queue is derived from database + MC_CALL buffer
- Added searchable queue with clickable items
- Operators can select students from queue to photograph
- Fixed admin dashboard PHOTOS_SAVED handler for photoHistory-based completion
- Fixed frame overlay by adding preserveFrameOnSync to main-app.tsx SYNC_DB handler

Stage Summary:
- MC can now send multiple students without blocking (flexible flow)
- Operators have a queue with search functionality
- Frame overlay now preserved correctly across SYNC_DB events
- PhotoHistory-based completion checking replaces buggy completedChannels state
- All lint checks pass

---
Task ID: 2
Agent: main
Task: Add camera shutter options (Manual, Timer 3/5/10s, 5-finger detection, AI mode)

Work Log:
- Created `use-finger-detection.ts` hook using MediaPipe Hands from CDN
  - Loads @mediapipe/hands, camera_utils, drawing_utils scripts from jsdelivr CDN
  - Counts extended fingers (0-5) per hand using landmark positions
  - Sustained 5-finger detection (800ms hold) triggers capture callback
  - 3-second cooldown between triggers to prevent rapid firing
  - Uses requestAnimationFrame loop for efficient detection
- Added ShutterMode type: 'manual' | 'timer-3' | 'timer-5' | 'timer-10' | 'finger' | 'ai'
- Added SHUTTER_MODES config array with mode restrictions (AI only for single/dual)
- Replaced old AI auto-capture toggle with new shutter mode selector
- Implemented timer countdown logic with visual overlay on camera view
  - Large countdown circle with gold border and number display
  - Cancel button during countdown (shows "BATAL (Xs)")
  - Auto-captures when countdown reaches 0
  - Timer automatically cancels when capture phase changes away from ready
- AI mode restricted to Single and Dual modes only (not photoshoot modes)
  - Uses `effectiveShutterMode` derived value that falls back to 'manual' when AI not allowed
  - AI button not shown in photoshoot mode selector
- Finger detection mode with visual indicator on camera view
  - Shows "X/5" finger count badge in top-right of camera
  - Green highlight when 5 fingers detected
  - Auto-captures when 5 fingers held for 800ms
- AI detection mode with visual indicator on camera view
  - Shows AI status badge with pose count
  - Gold highlight when pose detected
  - Auto-captures on toga/ijazah pose detection (single/dual mode only)
- Shutter mode selector UI in both mobile and desktop layouts
  - Compact button group showing Manual, 3s, 5s, 10s, 5 Jari, AI
  - Active mode highlighted with gold border and background
  - Loading spinners while AI/finger models load
- Progress text updated to show shutter mode status (Timer: Xs, Jari: X/5, AI: Toga terdeteksi...)
- Capture button text changes based on shutter mode:
  - Manual: "FOTO" / "FOTO 1 — TOGA" / "FOTO 2 — IJAZAH"
  - Timer: Shows timer duration "FOTO (3s)" with countdown
  - Finger/AI: Shows detection status "Mendeteksi Jari (3/5)" / "AI Mendeteksi Pose..."
- All lint checks pass
- Verified with Agent Browser: app renders correctly, shutter mode selector visible with all 6 options

Stage Summary:
- 6 shutter modes: Manual, Timer 3s/5s/10s, 5-finger detection, AI
- AI mode restricted to Single & Dual modes only
- Timer modes show countdown overlay on camera with cancel option
- Finger detection uses MediaPipe Hands with sustained detection logic
- Full shutter mode selector UI in both mobile and desktop layouts
- All existing functionality preserved
