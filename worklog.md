---
Task ID: 1
Agent: Main Agent
Task: Systematically fix USB capture card camera not working after login in Saatiril APK

Work Log:
- Read all relevant source files: BuiltInCameraManager.kt, UVCCameraManager.kt, OperatorViewModel.kt, OperatorScreen.kt, MainActivity.kt, ConnectionScreen.kt
- Identified ROOT CAUSE: CameraX 1.3.x's ProcessCameraProvider.availableCameraInfos is a SNAPSHOT that doesn't update when USB cameras are hot-plugged. ProcessCameraProvider is a SINGLETON - even destroying and recreating it returns the SAME stale instance.
- Rewrote BuiltInCameraManager.kt with new Camera2-first architecture:
  - findExternalCameraIds(): Uses Android Camera2 CameraManager.getCameraIdList() (OS-level, always current) for discovery
  - getAllCameraIdsFromCamera2(): Enumerates ALL cameras via OS, not CameraX
  - attemptBindExternalCamera(): Multi-strategy binding (ID filter selector → LENS_FACING_EXTERNAL → full reinit with delay)
  - selectBestCamera(): Camera2 for discovery first, CameraX for fallback
  - refreshAvailableCamerasFromCamera2(): Replaces stale CameraX-based refresh
  - switchToCameraById(): Uses Camera2 to verify camera exists, then constructs selector
- Updated OperatorViewModel.kt:
  - Initialize UVC manager FIRST before BuiltInCameraManager
  - Immediate check for already-connected USB at init time
  - Safety net delayed activation (3s) if UVC was connected during init
  - Periodic rescan with early check (3s) + regular check (5s)
  - Increased delay from 1.5s to 2s for Camera2 registration
- Fixed build environment: installed Android SDK, configured local.properties
- Built both debug and release APKs successfully
- Pushed all changes to GitHub

Stage Summary:
- Root cause: CameraX ProcessCameraProvider is a singleton with stale camera list snapshot
- Fix: Use Camera2 CameraManager (OS-level) for discovery + CameraX for binding
- APKs built: app-debug.apk (18.7MB), app-release-unsigned.apk (12.8MB)
- GitHub pushed: commit 43e3301 to synclicen/Saatiril-Fullset
