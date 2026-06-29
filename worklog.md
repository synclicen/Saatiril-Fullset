---
Task ID: 1
Agent: Main Agent
Task: Fix password flow (MC stuck when admin uses password) and frame sync issues

Work Log:
- Examined project structure: Socket.io relay server, admin/MC/operator clients, Zustand store
- Identified ROOT CAUSE of password issue: socket.ts `identify` event sends `role: 'unknown'` for admin (because admin has no `?role=admin` URL param)
- Server blocks `lan-message` relay from 'unknown' role clients, and rejects `SET_SESSION_PASSWORD` from non-'admin' roles
- This means admin could never set session password or relay project data to MC/Operator
- Identified secondary bug: queueMicrotask in main-app.tsx incorrectly set `sessionPasswordVerified=true` when `authState.authenticated` was true, even if server required password
- Added SHA-256 fallback for insecure HTTP contexts (LAN connections via IP)
- Fixed frame sync: added safety check in handleRequestState to restore frame from localStorage if marker found
- Fixed reidentifyWithPassword to return error status and use correct default role
- Fixed periodic REQUEST_STATE interval to check authentication before sending

Stage Summary:
- **Root cause fixed**: Changed default role from 'unknown' to 'admin' in socket.ts identify payload
- **Password flow fixed**: Admin can now properly set session password, MC/Operator will see password prompt
- **Frame sync fixed**: Admin can now relay SYNC_DB with frame data, frame restoration safety net added
- **SHA-256 fallback**: Pure JS implementation for HTTP LAN connections without crypto.subtle
- **queueMicrotask fix**: sessionPasswordVerified no longer incorrectly set to true when password is required
- Files modified: src/lib/socket.ts, src/components/saatiril/main-app.tsx
