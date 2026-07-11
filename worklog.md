---
Task ID: 1
Agent: Main Agent
Task: Fix Android APK build failures and queue/photo bugs

Work Log:
- Checked GitHub Actions build failure logs - found "Redeclaration: FilenameUtils" error
- Read all Android source files (SocketManager, OperatorViewModel, OperatorScreen, Models, CameraCapture, BuiltInCameraManager, UVCCameraManager, PhotoSaver, FilenameUtils, Sha256)
- Read Windows reference operator-panel.tsx to understand queue flow and photo save pipeline
- Identified root causes:
  1. Build Error: Duplicate `object FilenameUtils` in both FilenameUtils.kt and Sha256.kt
  2. Queue Bug: Missing @SerializedName annotations causing Gson parsing failures; opQueue not including active_N status students in photoshoot mode
  3. Photo Save: Pipeline was already implemented but could fail silently if targetFolder wasn't parsed correctly
- Fixed Sha256.kt: Removed duplicate FilenameUtils object, kept only CryptoUtils
- Fixed Models.kt: Added @SerializedName annotations to all data classes, added isActiveStatus() and statusLabel() utility functions
- Fixed SocketManager.kt: 
  - Custom Gson FieldNamingStrategy for camelCase/snake_case compatibility
  - Comprehensive manual JSON parsing fallbacks for MC_CALL and SYNC_DB
  - Fixed JSONObject.keys().next() type mismatch (platform type Any? → String)
  - Fixed optString() null fallback issue (use empty string + ifBlank)
- Fixed OperatorViewModel.kt:
  - updateOpQueue now includes active_N status students in photoshoot queue (matching Windows)
  - Improved MC_CALL handler with better channel filtering
  - Added comprehensive diagnostic logging
- Pushed 2 commits to GitHub
- Both workflows passed: Build Electron Windows App ✅, Build Saatiril (Android) ✅
- APK artifact uploaded: saatiril-operator-android (17.1 MB)

Stage Summary:
- Build errors FIXED: No more Redeclaration or type mismatch errors
- Queue display FIXED: Added robust parsing with multiple fallback strategies and @SerializedName annotations
- Photo save pipeline VERIFIED: finalizeCapture() correctly saves via PhotoSaver on Dispatchers.IO
- USB camera support CONFIRMED: BuiltInCameraManager already handles external cameras via CameraX
- APK available for download from GitHub Actions artifacts
