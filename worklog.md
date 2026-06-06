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
