---
Task ID: 1
Agent: Main Agent
Task: Fix USB capture card camera not working in Saatiril APK (v4 - DUAL ENGINE)

Work Log:
- Analyzed user's WhatsApp screenshots showing USB camera detected on connection screen but not working after login
- Used VLM skill to analyze both screenshots in detail
- Identified DEFINITIVE ROOT CAUSE: CameraX 1.3.x CANNOT bind to USB cameras AT ALL
  - ProcessCameraProvider.availableCameraInfos is a FROZEN SNAPSHOT
  - ProcessCameraProvider is a SINGLETON (can't get fresh instance)
  - addCameraFilter + bindToLifecycle throws IllegalArgumentException when camera ID isn't in registry
  - LENS_FACING_EXTERNAL + hasCamera() returns false on most devices
  - ALL previous approaches (forceReinit, Camera2 discovery, pendingRescan) failed because CameraX fundamentally cannot use cameras it doesn't enumerate
- Created ExternalCameraManager.kt: Camera2 API DIRECT camera manager for USB cameras
  - Uses CameraDevice + CameraCaptureSession for preview
  - Uses SurfaceTexture (TextureView) for preview rendering
  - Uses ImageReader for JPEG still capture
  - Semaphore for thread safety
  - Handles async camera open/close lifecycle
- Rewrote BuiltInCameraManager.kt: DUAL ENGINE architecture
  - Built-in cameras → CameraX engine (PreviewView, ImageCapture)
  - USB cameras → Camera2 engine (TextureView, ImageReader)
  - Auto-detects USB cameras on init, activates Camera2 engine
  - useCamera2Engine StateFlow tells UI which view to use
  - capturePhoto() delegates to active engine
  - switchCamera()/switchToCameraById() handles engine switching seamlessly
- Updated OperatorViewModel.kt:
  - Exposes useCamera2Engine StateFlow
  - setTextureView() method for Camera2 engine
  - Camera init sends both PreviewView and TextureView references
- Updated OperatorScreen.kt:
  - Shows TextureView when useCamera2Engine=true (USB camera active)
  - Shows PreviewView when useCamera2Engine=false (built-in camera)
  - Both views in same aspect-ratio-constrained box
- Built debug APK successfully (18.7MB)
- Pushed to GitHub (commit 86034f7)

Stage Summary:
- v4 DUAL ENGINE architecture: CameraX for built-in, Camera2 DIRECT for USB
- This is the ONLY architecture that can work because CameraX cannot use USB cameras
- Debug APK: /home/z/my-project/android-operator/apk-output/app-debug.apk
- GitHub: commit 86034f7 pushed to synclicen/Saatiril-Fullset
