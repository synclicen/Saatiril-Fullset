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
---
Task ID: 3
Agent: main
Task: Remove 5-finger detection from Shutter Modes, make it auto-trigger for timer modes only with progress bar

Work Log:
- Removed 'finger' from ShutterMode type: now 'manual' | 'timer-3' | 'timer-5' | 'timer-10' | 'ai'
- Removed finger entry from SHUTTER_MODES array (no longer a standalone shutter mode)
- Added `sustainProgress` (0-1) to useFingerDetection hook return value for progress bar
- Added `fingerGestureActive` derived value: true when timer mode is selected AND camera ready AND has active target AND timer not already running
- Finger detection now auto-initializes when any timer mode is selected (not just finger mode)
- Finger detection callback now calls `startTimer()` instead of `handleCapture()` — triggers timer countdown instead of direct capture
- Added `fingerTriggeredTimer` state to show "Timer dimulai! Turunkan tangan" overlay when finger gesture successfully started the timer
- Updated shutter mode selector: removed finger button, added hint text below timer modes showing gesture status
- Updated camera view overlays:
  - Removed old finger detection indicator (top-right corner)
  - Added new bottom-center finger gesture overlay with:
    - Instruction text: "Tunjukkan 5 jari (X/5)" or "Tahan jari..." when 5 detected
    - Progress bar that fills as fingers are held sustained
    - Green color when 5 fingers detected, gold while building
  - Timer countdown overlay now shows "Timer dimulai! Turunkan tangan" pill when finger-triggered
- Updated capture button rendering:
  - Removed all `effectiveShutterMode === 'finger'` logic from button states
  - `isAutoMode` now only checks for AI mode
  - `isDetecting` simplified to only check AI detection
- Updated progressText to show finger count only during active gesture in timer mode
- All lint checks pass
- App verified loading correctly in browser

Stage Summary:
- 5-finger detection removed from Shutter Mode selector (was a standalone mode, now auto-feature)
- When Timer mode (3s/5s/10s) is selected, finger gesture detection activates automatically
- Flow: hold 5 fingers → progress bar fills → timer countdown starts → "Timer dimulai! Turunkan tangan" → photo captured after countdown
- User can still manually press the button to start timer (finger gesture is alternative trigger)
- Progress bar with instruction text shows on camera view bottom
- Shutter modes now: Manual, Timer 3s, Timer 5s, Timer 10s, AI (single/dual only)
