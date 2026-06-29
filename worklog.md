---
Task ID: 1
Agent: Main Agent
Task: Fix all critical bugs and add QR code feature

Work Log:
- Installed qrcode.react@4.2.0 for QR code generation
- Fixed BUG 1 (Password flow): Server now sends both auth-failed AND auth-requirement events when MC's identify is rejected due to password requirement; Client's handleAuthFailed now sets serverRequiresPassword=true when auth fails with session_password_required; Added sessionPasswordError+authFailedReason check to needsPassword condition
- Fixed BUG 2 (Frame selection): Added localStorage fallback in operator panel's frameData derivation - when config.frame==='__FRAME_SAVED__', tries to restore from localStorage before giving up
- Fixed BUG 3 (Mobile connectivity): Changed socket server to bind to 0.0.0.0 instead of default (localhost only)
- Added QR code feature: Added generateLink function (shared logic for copy & QR), showQrCode handler, QR Code dialog with QRCodeSVG, added QR code buttons next to all Copy Link buttons in LAN Access Distribution section (all 3 modes: single, dual-photoshoot, dual)
- Added 21.0.20.132 to allowedDevOrigins in next.config.ts

Stage Summary:
- All 3 critical bugs fixed (password flow, frame selection, mobile connectivity)
- QR code feature added for all MC and Operator links
- Lint passes successfully
- Both Next.js dev server (port 3000) and Socket.io server (port 3003) running
