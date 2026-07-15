package com.saatiril.operator.camera

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager as AndroidCameraManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.TextureView
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ═════════════════════════════════════════════════════════════════════════
 * Unified Camera Manager — DUAL ENGINE architecture (v5)
 * ═════════════════════════════════════════════════════════════════════════
 *
 * ROOT CAUSE OF v4 FAILURE (chicken-and-egg problem):
 * The UI could only create ONE view at a time (PreviewView OR TextureView).
 * When init() first ran, useCamera2Engine=false → only PreviewView existed.
 * init() detected USB → activateUSBCamera() → TextureView was NULL → camera
 * NOT actually opened → useCamera2Engine set to true → recomposition created
 * TextureView → setTextureView() guard checked isConnected.value (false!) →
 * re-init NEVER happened. USB camera was lost every time!
 *
 * v5 FIX: The UI now creates BOTH views simultaneously and we manage which
 * is visible. init() receives BOTH views upfront, so when USB camera is
 * detected, the TextureView is immediately available for Camera2 engine.
 *
 * ENGINE ARCHITECTURE:
 * ┌─────────────────────────────────────────────────────────┐
 * │ Built-in cameras (BACK/FRONT) → CameraX engine          │
 * │   - CameraX handles lifecycle, preview, capture          │
 * │   - Works perfectly for phone's built-in cameras         │
 * │                                                          │
 * │ USB cameras (EXTERNAL) → Camera2 engine                  │
 * │   - Camera2 API opens USB camera directly                │
 * │   - CameraManager.getCameraIdList() ALWAYS sees USB      │
 * │   - CameraDevice + CameraCaptureSession for preview      │
 * │   - ImageReader for JPEG still capture                   │
 * │   - TextureView for preview rendering                    │
 * └─────────────────────────────────────────────────────────┘
 */
@androidx.camera.camera2.interop.ExperimentalCamera2Interop
class BuiltInCameraManager(private val context: Context) {

    companion object {
        private const val TAG = "BuiltInCameraManager"
        private const val USB_CAMERA_REGISTRATION_DELAY_MS = 2000L

        /**
         * Find external camera IDs using Android's Camera2 CameraManager.
         * This queries the OS directly and ALWAYS sees hot-plugged USB cameras.
         */
        fun findExternalCameraIds(context: Context): List<Pair<String, Int>> {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? AndroidCameraManager
                ?: return emptyList()

            val externalCameras = mutableListOf<Pair<String, Int>>()
            try {
                for (id in cameraManager.cameraIdList) {
                    try {
                        val characteristics = cameraManager.getCameraCharacteristics(id)
                        val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                        if (lensFacing == CameraCharacteristics.LENS_FACING_EXTERNAL) {
                            Log.i(TAG, "Camera2 discovered external camera: id=$id")
                            externalCameras.add(id to CameraSelector.LENS_FACING_EXTERNAL)
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Cannot check camera $id: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error enumerating Camera2 cameras: ${e.message}")
            }
            return externalCameras
        }

        /**
         * Get ALL camera IDs from Camera2 CameraManager (OS-level, always current).
         */
        fun getAllCameraIdsFromCamera2(context: Context): List<Triple<String, Int, String>> {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? AndroidCameraManager
                ?: return emptyList()

            val cameras = mutableListOf<Triple<String, Int, String>>()
            try {
                for (id in cameraManager.cameraIdList) {
                    try {
                        val characteristics = cameraManager.getCameraCharacteristics(id)
                        val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                        val facing = when (lensFacing) {
                            CameraCharacteristics.LENS_FACING_FRONT -> CameraSelector.LENS_FACING_FRONT
                            CameraCharacteristics.LENS_FACING_BACK -> CameraSelector.LENS_FACING_BACK
                            CameraCharacteristics.LENS_FACING_EXTERNAL -> CameraSelector.LENS_FACING_EXTERNAL
                            else -> -1
                        }
                        val displayName = when (lensFacing) {
                            CameraCharacteristics.LENS_FACING_EXTERNAL -> "USB Capture Card"
                            CameraCharacteristics.LENS_FACING_BACK -> "Kamera Belakang"
                            CameraCharacteristics.LENS_FACING_FRONT -> "Kamera Depan"
                            else -> "Kamera ($id)"
                        }
                        cameras.add(Triple(id, facing, displayName))
                    } catch (e: Exception) {
                        Log.d(TAG, "Cannot get characteristics for camera $id: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting camera ID list: ${e.message}")
            }
            return cameras
        }
    }

    // ═══════════════════════════════════════════════════════════
    // DUAL ENGINE: CameraX (built-in) + Camera2 (USB)
    // ═══════════════════════════════════════════════════════════

    // CameraX engine (for built-in cameras)
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var currentCameraSelector: CameraSelector? = null
    private var currentLensFacing: Int = CameraSelector.LENS_FACING_BACK

    // Camera2 engine (for USB cameras)
    private val externalCameraManager = ExternalCameraManager(context)

    // Common state
    private var isUsingExternalCamera: Boolean = false
    private var currentExternalCameraId: String? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var previewView: PreviewView? = null
    private var textureView: TextureView? = null

    // Track whether camera provider has been initialized
    private var providerInitialized: Boolean = false
    private var initInProgress: Boolean = false
    private var pendingUsbRescan: Boolean = false

    // v5: Track whether init has been called with valid views
    private var fullyInitialized: Boolean = false

    // ═══════════════════════════════════════════════════════════
    // STATE FLOWS (unified — same regardless of engine)
    // ═══════════════════════════════════════════════════════════

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _cameraType = MutableStateFlow("none")
    val cameraType: StateFlow<String> = _cameraType.asStateFlow()

    private val _currentCameraId = MutableStateFlow("")
    val currentCameraId: StateFlow<String> = _currentCameraId.asStateFlow()

    private val _availableCameras = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val availableCameras: StateFlow<List<Pair<String, String>>> = _availableCameras.asStateFlow()

    // Expose whether we're using the Camera2 engine (for UI to know which view to show)
    private val _useCamera2Engine = MutableStateFlow(false)
    val useCamera2Engine: StateFlow<Boolean> = _useCamera2Engine.asStateFlow()

    // ─── Permission Check ──────────────────────────────────────

    fun hasCameraPermission(): Boolean {
        return (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }

    // ═══════════════════════════════════════════════════════════
    // INITIALIZATION (v5: receives BOTH views upfront)
    // ═══════════════════════════════════════════════════════════

    /**
     * Initialize camera system. v5: receives BOTH PreviewView AND TextureView.
     *
     * CRITICAL v5 CHANGE: Both views are provided at init time so there's
     * NO chicken-and-egg problem. When USB camera is detected, TextureView
     * is already available.
     *
     * Camera selection priority:
     * 1. USB external camera (Camera2 engine) — if detected
     * 2. Built-in back camera (CameraX engine) — fallback
     * 3. Built-in front camera (CameraX engine) — last resort
     */
    fun init(lifecycleOwner: LifecycleOwner, previewView: PreviewView, textureView: TextureView? = null) {
        Log.i(TAG, "═══════════════════════════════════════════════════")
        Log.i(TAG, "init() called — v5 dual-engine initialization")
        Log.i(TAG, "  lifecycleOwner: ${lifecycleOwner.javaClass.simpleName}")
        Log.i(TAG, "  previewView: ${previewView != null}, textureView: ${textureView != null}")
        Log.i(TAG, "═══════════════════════════════════════════════════")

        this.lifecycleOwner = lifecycleOwner
        this.previewView = previewView
        if (textureView != null) {
            this.textureView = textureView
        }

        // Register callback from ExternalCameraManager to propagate connection state
        externalCameraManager.onConnectionStateChanged = { connected, cameraType ->
            Log.i(TAG, "ExternalCameraManager state changed: connected=$connected, type=$cameraType")
            _isConnected.value = connected
            _cameraType.value = cameraType
            if (connected && cameraType == "external") {
                _useCamera2Engine.value = true
            }
        }

        if (!hasCameraPermission()) {
            Log.e(TAG, "CAMERA permission not granted — cannot initialize")
            _cameraType.value = "none"
            _isConnected.value = false
            return
        }

        // Refresh camera list from Camera2 (OS-level)
        refreshAvailableCamerasFromCamera2()

        // Step 1: Check for USB camera FIRST
        val externalCameras = findExternalCameraIds(context)
        if (externalCameras.isNotEmpty()) {
            Log.i(TAG, "═══════════════════════════════════════════════════")
            Log.i(TAG, "USB CAMERA DETECTED at init: id=${externalCameras.first().first}")
            Log.i(TAG, "Activating Camera2 engine for USB camera")
            Log.i(TAG, "═══════════════════════════════════════════════════")
            activateUSBCamera(externalCameras.first().first)
            fullyInitialized = true
            return
        }

        // Step 2: No USB camera — initialize CameraX for built-in cameras
        Log.i(TAG, "No USB camera detected — initializing CameraX for built-in cameras")
        initCameraXProvider(lifecycleOwner, previewView)
        fullyInitialized = true
    }

    /**
     * Set the TextureView for Camera2 engine (USB camera preview).
     * v5 FIX: This is now called immediately when TextureView is created.
     * The guard condition now checks isUsingExternalCamera + currentExternalCameraId
     * instead of the broken isConnected.value check.
     */
    fun setTextureView(tv: TextureView) {
        Log.i(TAG, "setTextureView called — tv.isAvailable=${tv.isAvailable}")
        Log.i(TAG, "  Current state: isUsingExternalCamera=$isUsingExternalCamera, currentExternalCameraId=$currentExternalCameraId")
        Log.i(TAG, "  externalCameraManager.isConnected=${externalCameraManager.isConnected.value}")

        this.textureView = tv

        // v5 FIX: If we're supposed to be using external camera but it's not actually
        // connected yet (because TextureView was missing during activateUSBCamera),
        // NOW is the time to actually open the camera!
        if (isUsingExternalCamera && currentExternalCameraId != null) {
            if (externalCameraManager.isConnected.value) {
                // Already connected — might need to re-associate the TextureView
                Log.i(TAG, "setTextureView: USB camera already connected, re-associating TextureView")
                externalCameraManager.closeCamera()
                if (tv.isAvailable) {
                    externalCameraManager.init(tv)
                }
            } else {
                // NOT connected yet — this is the missing activation!
                Log.i(TAG, "═══════════════════════════════════════════════════")
                Log.i(TAG, "setTextureView: USB camera PENDING ACTIVATION")
                Log.i(TAG, "  Camera2 engine was selected but TextureView was missing before")
                Log.i(TAG, "  NOW activating USB camera with available TextureView")
                Log.i(TAG, "═══════════════════════════════════════════════════")

                if (tv.isAvailable) {
                    val cameraId = currentExternalCameraId!!
                    externalCameraManager.init(tv)
                    // Wait for the camera to actually open
                    // The ExternalCameraManager will set isConnected = true when ready
                    _cameraType.value = "external"
                    Log.i(TAG, "USB camera activation initiated via Camera2 engine (from setTextureView)")
                } else {
                    Log.w(TAG, "setTextureView: TextureView not available yet, setting listener")
                    tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                            Log.i(TAG, "TextureView now available, activating USB camera")
                            val cameraId = currentExternalCameraId ?: return
                            externalCameraManager.init(tv)
                            _cameraType.value = "external"
                        }
                        override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {}
                        override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean = true
                        override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {}
                    }
                }
            }
        }
    }

    /**
     * Re-initialize camera after permission is granted.
     */
    fun reinit(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        Log.i(TAG, "Re-initializing camera")
        closeAllCameras()
        init(lifecycleOwner, previewView, textureView)
    }

    // ═══════════════════════════════════════════════════════════
    // USB CAMERA (Camera2 Engine)
    // ═══════════════════════════════════════════════════════════

    /**
     * Activate USB camera using Camera2 engine.
     * This CLOSES the CameraX engine first, then opens the Camera2 engine.
     */
    private fun activateUSBCamera(cameraId: String) {
        Log.i(TAG, "activateUSBCamera: Switching to Camera2 engine for USB camera id=$cameraId")

        // Close CameraX engine if running
        closeCameraXEngine()

        // Set state FIRST (even before camera is opened)
        isUsingExternalCamera = true
        currentExternalCameraId = cameraId
        _useCamera2Engine.value = true
        _currentCameraId.value = cameraId

        val tv = textureView
        if (tv == null) {
            // v5 FIX: Don't just give up — set the state so that when TextureView
            // arrives (via setTextureView), it will activate the camera.
            // Also, DON'T set cameraType to "external" yet — it's not actually connected.
            Log.w(TAG, "═══════════════════════════════════════════════════")
            Log.w(TAG, "TextureView not set yet — USB camera will be activated")
            Log.w(TAG, "when setTextureView() is called with a valid TextureView")
            Log.w(TAG, "═══════════════════════════════════════════════════")
            // Mark as pending — setTextureView will detect this and activate
            _cameraType.value = "external_pending"
            return
        }

        if (!tv.isAvailable) {
            Log.w(TAG, "TextureView exists but not available yet — setting listener")
            _cameraType.value = "external_pending"
            tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                    Log.i(TAG, "TextureView now available, opening USB camera")
                    openUSBCameraWithTextureView(cameraId, tv)
                }
                override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {}
                override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
                    closeCamera2Engine()
                    return true
                }
                override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {}
            }
            return
        }

        // TextureView is available — open the camera now
        openUSBCameraWithTextureView(cameraId, tv)
    }

    /**
     * Actually open the USB camera with the TextureView.
     * Separated from activateUSBCamera so it can be called from setTextureView too.
     */
    private fun openUSBCameraWithTextureView(cameraId: String, tv: TextureView) {
        Log.i(TAG, "openUSBCameraWithTextureView: Opening USB camera id=$cameraId")

        val success = externalCameraManager.init(tv)
        if (success) {
            _cameraType.value = "external"
            Log.i(TAG, "═══════════════════════════════════════════════════")
            Log.i(TAG, "✓ USB camera activation initiated via Camera2 engine")
            Log.i(TAG, "═══════════════════════════════════════════════════")
        } else {
            Log.e(TAG, "═══════════════════════════════════════════════════")
            Log.e(TAG, "✗ USB camera activation FAILED — falling back to CameraX")
            Log.e(TAG, "═══════════════════════════════════════════════════")
            isUsingExternalCamera = false
            currentExternalCameraId = null
            _useCamera2Engine.value = false
            _cameraType.value = "none"
            // Fall back to CameraX
            val owner = lifecycleOwner ?: return
            val pv = previewView ?: return
            initCameraXProvider(owner, pv)
        }

        refreshAvailableCamerasFromCamera2()
    }

    // ═══════════════════════════════════════════════════════════
    // CameraX Engine (Built-in Cameras)
    // ═══════════════════════════════════════════════════════════

    private fun initCameraXProvider(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        if (providerInitialized && cameraProvider != null) {
            selectBestBuiltInCamera(lifecycleOwner, previewView)
            return
        }

        if (initInProgress) {
            Log.d(TAG, "CameraX init already in progress")
            return
        }

        initInProgress = true
        Log.i(TAG, "Initializing CameraX provider for built-in cameras")

        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                try {
                    cameraProvider = cameraProviderFuture.get()
                    providerInitialized = true
                    initInProgress = false
                    Log.i(TAG, "CameraX provider initialized. Cameras: ${cameraProvider?.availableCameraInfos?.size}")

                    if (pendingUsbRescan) {
                        pendingUsbRescan = false
                        Handler(Looper.getMainLooper()).postDelayed({
                            val extCams = findExternalCameraIds(context)
                            if (extCams.isNotEmpty()) {
                                activateUSBCamera(extCams.first().first)
                            } else {
                                selectBestBuiltInCamera(lifecycleOwner, previewView)
                            }
                        }, USB_CAMERA_REGISTRATION_DELAY_MS)
                    } else {
                        selectBestBuiltInCamera(lifecycleOwner, previewView)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get CameraX provider: ${e.message}")
                    initInProgress = false
                    _cameraType.value = "none"
                    _isConnected.value = false
                }
            }, ContextCompat.getMainExecutor(context))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get CameraX provider instance: ${e.message}")
            initInProgress = false
            _cameraType.value = "none"
            _isConnected.value = false
        }
    }

    /**
     * Select the best built-in camera (CameraX engine).
     * Only called when no USB camera is available.
     */
    private fun selectBestBuiltInCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val provider = cameraProvider ?: return

        _isConnected.value = false
        _cameraType.value = "none"
        _useCamera2Engine.value = false
        isUsingExternalCamera = false
        currentExternalCameraId = null

        // Try back camera
        try {
            val backSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()
            if (provider.hasCamera(backSelector)) {
                currentLensFacing = CameraSelector.LENS_FACING_BACK
                currentCameraSelector = backSelector
                startCameraX(lifecycleOwner, previewView)
                return
            }
        } catch (e: Exception) {
            Log.d(TAG, "No back camera: ${e.message}")
        }

        // Try front camera
        try {
            val frontSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build()
            if (provider.hasCamera(frontSelector)) {
                currentLensFacing = CameraSelector.LENS_FACING_FRONT
                currentCameraSelector = frontSelector
                startCameraX(lifecycleOwner, previewView)
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "No camera at all: ${e.message}")
        }

        Log.e(TAG, "NO CAMERA DETECTED")
        _cameraType.value = "none"
        _isConnected.value = false
    }

    /**
     * Start CameraX camera (built-in).
     */
    private fun startCameraX(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val provider = cameraProvider ?: return
        val selector = currentCameraSelector ?: return

        try {
            provider.unbindAll()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unbind: ${e.message}")
        }

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .build()

        try {
            camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
            _isConnected.value = true
            _cameraType.value = when (currentLensFacing) {
                CameraSelector.LENS_FACING_BACK -> "back"
                CameraSelector.LENS_FACING_FRONT -> "front"
                else -> "unknown"
            }
            _useCamera2Engine.value = false
            _currentCameraId.value = try {
                camera?.cameraInfo?.let { getCameraIdFromCameraInfo(it) } ?: ""
            } catch (e: Exception) { "" }
            refreshAvailableCamerasFromCamera2()
            Log.i(TAG, "CameraX started: type=${_cameraType.value}, id=${_currentCameraId.value}")
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied: ${e.message}")
            _isConnected.value = false
            _cameraType.value = "none"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start CameraX: ${e.message}")
            _isConnected.value = false
            _cameraType.value = "none"
        }
    }

    // ═══════════════════════════════════════════════════════════
    // CAMERA SWITCHING
    // ═══════════════════════════════════════════════════════════

    /**
     * Switch between cameras: USB → Back → Front → USB
     */
    fun switchCamera() {
        if (isUsingExternalCamera) {
            // Switch from USB to built-in back
            switchToBuiltInCamera(CameraSelector.LENS_FACING_BACK)
        } else if (currentLensFacing == CameraSelector.LENS_FACING_BACK) {
            // Try USB first, then front
            val extCams = findExternalCameraIds(context)
            if (extCams.isNotEmpty()) {
                activateUSBCamera(extCams.first().first)
            } else {
                switchToBuiltInCamera(CameraSelector.LENS_FACING_FRONT)
            }
        } else {
            // Front → try USB → back
            val extCams = findExternalCameraIds(context)
            if (extCams.isNotEmpty()) {
                activateUSBCamera(extCams.first().first)
            } else {
                switchToBuiltInCamera(CameraSelector.LENS_FACING_BACK)
            }
        }
    }

    /**
     * Switch to a specific camera by ID.
     */
    fun switchToCameraById(cameraId: String) {
        val allCameras = getAllCameraIdsFromCamera2(context)
        val target = allCameras.find { it.first == cameraId } ?: return

        if (target.second == CameraSelector.LENS_FACING_EXTERNAL) {
            activateUSBCamera(cameraId)
        } else {
            switchToBuiltInCamera(target.second)
        }
    }

    /**
     * Switch to a built-in camera (CameraX engine).
     */
    private fun switchToBuiltInCamera(lensFacing: Int) {
        val owner = lifecycleOwner ?: return
        val pv = previewView ?: return

        // Close USB camera engine if running
        closeCamera2Engine()

        // Close CameraX engine and restart with new lens
        isUsingExternalCamera = false
        currentExternalCameraId = null
        _useCamera2Engine.value = false
        currentLensFacing = lensFacing
        currentCameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        startCameraX(owner, pv)
    }

    // ═══════════════════════════════════════════════════════════
    // FORCE REINIT FOR USB (called by ViewModel when USB detected)
    // ═══════════════════════════════════════════════════════════

    /**
     * Force activation of USB camera.
     * Called when:
     * - UVC device is detected (ViewModel's uvcConnectedCollector)
     * - User taps "Pindai Ulang USB" button
     * - Periodic rescan finds UVC but we're still on built-in
     */
    fun forceReinitForUSB() {
        Log.i(TAG, "═══════════════════════════════════════════════════")
        Log.i(TAG, "forceReinitForUSB: USB camera activation requested")
        Log.i(TAG, "  Current state: isUsingExternalCamera=$isUsingExternalCamera")
        Log.i(TAG, "  currentExternalCameraId=$currentExternalCameraId")
        Log.i(TAG, "  cameraType=${_cameraType.value}")
        Log.i(TAG, "  textureView=$textureView")
        Log.i(TAG, "═══════════════════════════════════════════════════")

        val extCams = findExternalCameraIds(context)
        if (extCams.isEmpty()) {
            Log.w(TAG, "No USB camera found by Camera2 — scheduling delayed retry")
            Handler(Looper.getMainLooper()).postDelayed({
                val retry = findExternalCameraIds(context)
                if (retry.isNotEmpty()) {
                    Log.i(TAG, "Delayed retry found USB camera: ${retry.first().first}")
                    activateUSBCamera(retry.first().first)
                }
            }, USB_CAMERA_REGISTRATION_DELAY_MS)
            return
        }

        val cameraId = extCams.first().first
        if (isUsingExternalCamera && currentExternalCameraId == cameraId && externalCameraManager.isConnected.value) {
            Log.d(TAG, "USB camera $cameraId is already active and connected")
            return
        }

        activateUSBCamera(cameraId)
    }

    /**
     * Called when USB device is attached/detached.
     */
    fun onUsbDeviceChanged() {
        if (!providerInitialized) {
            pendingUsbRescan = true
        }
    }

    /**
     * Rescan for external cameras.
     */
    fun rescanForExternalCamera() {
        refreshAvailableCamerasFromCamera2()
        val extCams = findExternalCameraIds(context)

        if (extCams.isNotEmpty() && !isUsingExternalCamera) {
            Log.i(TAG, "Rescan found USB camera, activating")
            activateUSBCamera(extCams.first().first)
        } else if (extCams.isEmpty() && isUsingExternalCamera) {
            Log.w(TAG, "USB camera lost, falling back to built-in")
            closeCamera2Engine()
            val owner = lifecycleOwner ?: return
            val pv = previewView ?: return
            isUsingExternalCamera = false
            currentExternalCameraId = null
            _useCamera2Engine.value = false
            selectBestBuiltInCamera(owner, pv)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // PHOTO CAPTURE (delegates to the active engine)
    // ═══════════════════════════════════════════════════════════

    /**
     * Capture a photo. Delegates to Camera2 engine (USB) or CameraX engine (built-in).
     */
    fun capturePhoto(onResult: (Bitmap?) -> Unit) {
        if (isUsingExternalCamera) {
            externalCameraManager.capturePhoto(onResult)
        } else {
            capturePhotoCameraX(onResult)
        }
    }

    private fun capturePhotoCameraX(onResult: (Bitmap?) -> Unit) {
        val capture = imageCapture ?: run {
            Log.e(TAG, "ImageCapture not initialized")
            onResult(null)
            return
        }

        capture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val bitmap = image.toBitmap()
                        val rotation = image.imageInfo.rotationDegrees
                        val rotated = if (rotation != 0) {
                            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                        } else {
                            bitmap
                        }
                        onResult(rotated)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to process captured image: ${e.message}")
                        onResult(null)
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exception.message}")
                    onResult(null)
                }
            }
        )
    }

    private fun ImageProxy.toBitmap(): Bitmap {
        if (format == android.graphics.ImageFormat.JPEG ||
            format == android.graphics.ImageFormat.DEPTH_JPEG) {
            val buffer = planes[0].buffer
            val bytes = ByteArray(buffer.capacity())
            buffer.get(bytes)
            return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IllegalStateException("Failed to decode JPEG image")
        }

        return try {
            val yBuffer = planes[0].buffer
            val uBuffer = planes[1].buffer
            val vBuffer = planes[2].buffer
            val yRowStride = planes[0].rowStride
            val uvRowStride = planes[1].rowStride
            val uvPixelStride = planes[1].pixelStride
            val width = width
            val height = height
            val argb = IntArray(width * height)

            for (y in 0 until height) {
                for (x in 0 until width) {
                    val yIndex = y * yRowStride + x
                    val uvIndex = (y / 2) * uvRowStride + (x / 2) * uvPixelStride
                    val yValue = yBuffer.get(yIndex).toInt() and 0xFF
                    val uValue = uBuffer.get(uvIndex).toInt() and 0xFF
                    val vValue = vBuffer.get(uvIndex).toInt() and 0xFF
                    val r = (yValue + 1.370705 * (vValue - 128)).toInt().coerceIn(0, 255)
                    val g = (yValue - 0.337633 * (uValue - 128) - 0.698001 * (vValue - 128)).toInt().coerceIn(0, 255)
                    val b = (yValue + 1.732446 * (uValue - 128)).toInt().coerceIn(0, 255)
                    argb[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) {
            Log.e(TAG, "YUV conversion failed: ${e.message}")
            val buffer = planes[0].buffer
            val bytes = ByteArray(buffer.capacity())
            buffer.get(bytes)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IllegalStateException("Failed to convert image format: $format")
        }
    }

    // ═══════════════════════════════════════════════════════════
    // CAMERA LIST / PICKER
    // ═══════════════════════════════════════════════════════════

    fun getAvailableCameras(): List<Pair<String, String>> {
        refreshAvailableCamerasFromCamera2()
        return _availableCameras.value
    }

    fun refreshAvailableCameras() {
        refreshAvailableCamerasFromCamera2()
    }

    private fun refreshAvailableCamerasFromCamera2() {
        val cameras = getAllCameraIdsFromCamera2(context)
        _availableCameras.value = cameras.map { (id, _, name) -> id to name }
    }

    // ═══════════════════════════════════════════════════════════
    // ENGINE MANAGEMENT
    // ═══════════════════════════════════════════════════════════

    private fun closeCameraXEngine() {
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "Error unbinding CameraX: ${e.message}")
        }
        camera = null
        preview = null
        imageCapture = null
        _isConnected.value = false
    }

    private fun closeCamera2Engine() {
        externalCameraManager.closeCamera()
        _isConnected.value = false
    }

    private fun closeAllCameras() {
        closeCameraXEngine()
        closeCamera2Engine()
        isUsingExternalCamera = false
        currentExternalCameraId = null
        _useCamera2Engine.value = false
        _cameraType.value = "none"
        _isConnected.value = false
    }

    private fun getCameraIdFromCameraInfo(cameraInfo: CameraInfo): String? {
        return try {
            Camera2CameraInfo.from(cameraInfo).cameraId
        } catch (e: Exception) {
            null
        }
    }

    // ═══════════════════════════════════════════════════════════
    // CLEANUP
    // ═══════════════════════════════════════════════════════════

    fun destroy() {
        closeAllCameras()
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "Error unbinding during destroy: ${e.message}")
        }
        externalCameraManager.destroy()
        cameraProvider = null
        preview = null
        imageCapture = null
        camera = null
        lifecycleOwner = null
        previewView = null
        textureView = null
        currentCameraSelector = null
        currentExternalCameraId = null
        isUsingExternalCamera = false
        providerInitialized = false
        initInProgress = false
        pendingUsbRescan = false
        fullyInitialized = false
        _currentCameraId.value = ""
        _availableCameras.value = emptyList()
    }
}
