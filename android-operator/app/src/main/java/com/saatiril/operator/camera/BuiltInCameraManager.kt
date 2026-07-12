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
 * Manages camera using CameraX — supports BOTH built-in cameras and
 * USB HDMI capture cards (which Android exposes as external cameras).
 *
 * ═══════════════════════════════════════════════════════════════
 * CRITICAL ARCHITECTURE FIX (v3):
 * ═══════════════════════════════════════════════════════════════
 *
 * ROOT CAUSE OF USB CAMERA FAILURE:
 * CameraX 1.3.x's ProcessCameraProvider.availableCameraInfos is a SNAPSHOT
 * taken when the provider is first initialized. It does NOT update when
 * USB cameras are hot-plugged. Even destroying and recreating the provider
 * returns the SAME singleton instance with the SAME stale camera list.
 *
 * THE FIX:
 * 1. Use Android's Camera2 CameraManager.getCameraIdList() for DISCOVERY
 *    — this ALWAYS sees USB cameras because it queries the OS directly
 * 2. Use CameraX for BINDING (preview + capture) — better lifecycle handling
 * 3. When a USB camera is found by Camera2 but NOT in CameraX's list,
 *    construct a CameraSelector by camera ID and attempt bindToLifecycle()
 *    directly. CameraX CAN bind to cameras it doesn't enumerate in
 *    availableCameraInfos — the selector just needs to match a valid camera.
 * 4. If direct binding fails, fall back to using the ProcessCameraProvider's
 *    own camera list (which may not include USB cameras).
 *
 * Camera selection strategy:
 * 1. EXTERNAL (USB HDMI capture cards) — highest priority
 * 2. BACK — built-in rear camera (fallback)
 * 3. FRONT — built-in front camera (last resort)
 */
@androidx.camera.camera2.interop.ExperimentalCamera2Interop
class BuiltInCameraManager(private val context: Context) {

    companion object {
        private const val TAG = "BuiltInCameraManager"
        private const val USB_CAMERA_REGISTRATION_DELAY_MS = 2000L

        /**
         * Find external camera IDs using Android's Camera2 CameraManager.
         * This queries the OS directly and ALWAYS sees hot-plugged USB cameras,
         * unlike CameraX's availableCameraInfos which is a stale snapshot.
         *
         * Returns list of (cameraId, lensFacing) pairs for external cameras.
         */
        fun findExternalCameraIds(context: Context): List<Pair<String, Int>> {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? AndroidCameraManager
                ?: return emptyList()

            val externalCameras = mutableListOf<Pair<String, Int>>()
            try {
                val cameraIds = cameraManager.cameraIdList
                for (id in cameraIds) {
                    try {
                        val characteristics = cameraManager.getCameraCharacteristics(id)
                        val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                        if (lensFacing == CameraCharacteristics.LENS_FACING_EXTERNAL) {
                            Log.i(TAG, "Camera2 discovered external camera: id=$id, LENS_FACING_EXTERNAL")
                            externalCameras.add(id to CameraSelector.LENS_FACING_EXTERNAL)
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Cannot check camera $id: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error enumerating Camera2 cameras: ${e.message}")
            }

            if (externalCameras.isEmpty()) {
                Log.i(TAG, "Camera2 found NO external cameras among ${cameraManager.cameraIdList?.size ?: 0} total")
            } else {
                Log.i(TAG, "Camera2 found ${externalCameras.size} external camera(s): ${externalCameras.map { it.first }}")
            }

            return externalCameras
        }

        /**
         * Get ALL camera IDs from Camera2 CameraManager (OS-level, always current).
         * Returns list of (cameraId, displayName) pairs.
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

    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var currentCameraSelector: CameraSelector? = null
    private var currentLensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var isUsingExternalCamera: Boolean = false
    private var currentExternalCameraId: String? = null  // Track which external camera ID we're using
    private var lifecycleOwner: LifecycleOwner? = null
    private var previewView: PreviewView? = null

    // Track whether camera provider has been initialized
    private var providerInitialized: Boolean = false
    private var initInProgress: Boolean = false

    // Pending USB rescan flag
    private var pendingUsbRescan: Boolean = false

    // Track last attempted camera to avoid infinite retry loops
    private var lastAttemptedCameraId: String? = null
    private var lastAttemptTime: Long = 0

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    // Camera source: "external", "back", "front", "none"
    private val _cameraType = MutableStateFlow("none")
    val cameraType: StateFlow<String> = _cameraType.asStateFlow()

    // Current camera ID — tracks which camera is actively in use
    private val _currentCameraId = MutableStateFlow("")
    val currentCameraId: StateFlow<String> = _currentCameraId.asStateFlow()

    // Available cameras — reactive list from Camera2 (OS-level, always current)
    private val _availableCameras = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val availableCameras: StateFlow<List<Pair<String, String>>> = _availableCameras.asStateFlow()

    // ─── Permission Check ──────────────────────────────────────

    fun hasCameraPermission(): Boolean {
        return (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }

    // ─── Setup ──────────────────────────────────────────────────

    /**
     * Initialize camera with the given LifecycleOwner and PreviewView.
     *
     * IDEMPOTENT: Calling this multiple times is safe.
     * CRITICAL: Must only be called after CAMERA permission is granted.
     */
    fun init(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        if (!hasCameraPermission()) {
            Log.e(TAG, "CAMERA permission not granted — cannot initialize camera")
            _cameraType.value = "none"
            _isConnected.value = false
            return
        }

        val ownerChanged = this.lifecycleOwner != lifecycleOwner
        val previewChanged = this.previewView != previewView
        this.lifecycleOwner = lifecycleOwner
        this.previewView = previewView

        if (providerInitialized && cameraProvider != null) {
            if (ownerChanged || previewChanged) {
                Log.i(TAG, "Camera provider already initialized, rebinding with new lifecycle/preview")
                selectBestCamera(lifecycleOwner, previewView)
            } else if (_isConnected.value) {
                Log.d(TAG, "Camera already initialized and connected — skipping")
                return
            } else {
                Log.i(TAG, "Camera provider exists but not connected — retrying camera selection")
                selectBestCamera(lifecycleOwner, previewView)
            }
            return
        }

        if (initInProgress) {
            Log.d(TAG, "Camera init already in progress — skipping duplicate call")
            return
        }
        Log.i(TAG, "Initializing camera provider for the first time")
        initInProgress = true

        // CRITICAL: Refresh available cameras from Camera2 BEFORE initializing provider
        // This ensures we know about USB cameras even if CameraX doesn't enumerate them
        refreshAvailableCamerasFromCamera2()

        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

            cameraProviderFuture.addListener({
                try {
                    cameraProvider = cameraProviderFuture.get()
                    providerInitialized = true
                    initInProgress = false

                    Log.i(TAG, "CameraProvider initialized. availableCameraInfos count: ${cameraProvider?.availableCameraInfos?.size}")

                    // Log CameraX's camera list
                    cameraProvider?.availableCameraInfos?.forEach { camInfo ->
                        val camId = getCameraIdFromCameraInfo(camInfo)
                        Log.d(TAG, "  CameraX camera: id=$camId, lensFacing=${camInfo.lensFacing}")
                    }

                    // Log Camera2's camera list (OS-level, should include USB cameras)
                    val camera2Cameras = getAllCameraIdsFromCamera2(context)
                    Log.i(TAG, "Camera2 (OS) cameras: ${camera2Cameras.size}")
                    camera2Cameras.forEach { (id, facing, name) ->
                        Log.d(TAG, "  Camera2 camera: id=$id, facing=$facing, name=$name")
                    }

                    if (pendingUsbRescan) {
                        Log.i(TAG, "USB was detected before provider ready — delaying selection for Camera2 registration")
                        pendingUsbRescan = false
                        Handler(Looper.getMainLooper()).postDelayed({
                            selectBestCamera(lifecycleOwner, previewView)
                        }, USB_CAMERA_REGISTRATION_DELAY_MS)
                    } else {
                        selectBestCamera(lifecycleOwner, previewView)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get camera provider from future: ${e.message}")
                    initInProgress = false
                    _cameraType.value = "none"
                    _isConnected.value = false
                }
            }, ContextCompat.getMainExecutor(context))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get camera provider instance: ${e.message}")
            initInProgress = false
            _cameraType.value = "none"
            _isConnected.value = false
        }
    }

    /**
     * Re-initialize camera after permission is granted.
     */
    fun reinit(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        Log.i(TAG, "Re-initializing camera (permission may have just been granted)")
        _isConnected.value = false
        _cameraType.value = "none"
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "Error unbinding during reinit: ${e.message}")
        }
        providerInitialized = false
        cameraProvider = null
        initInProgress = false
        init(lifecycleOwner, previewView)
    }

    /**
     * CRITICAL FIX (v3): Force re-initialization when USB camera is detected.
     *
     * Since ProcessCameraProvider is a singleton in CameraX 1.3.x and its
     * availableCameraInfos is a stale snapshot, we take a different approach:
     *
     * 1. First, check if Camera2 CameraManager sees the external camera
     * 2. Try to bind to it directly via camera ID (even if CameraX doesn't list it)
     * 3. If that fails, try unbinding and rebinding
     * 4. As last resort, try full reinit of the provider
     */
    fun forceReinitForUSB() {
        val owner = lifecycleOwner ?: return
        val pv = previewView ?: return

        Log.i(TAG, "═══════════════════════════════════════════════════")
        Log.i(TAG, "forceReinitForUSB: USB camera detected, attempting to activate it")
        Log.i(TAG, "═══════════════════════════════════════════════════")

        // Step 1: Check what Camera2 (OS) sees
        val externalCameras = findExternalCameraIds(context)
        Log.i(TAG, "Camera2 (OS) external cameras: ${externalCameras.size}")

        if (externalCameras.isEmpty()) {
            Log.w(TAG, "No external cameras found by Camera2 — USB device may not be registered yet")
            // Schedule a delayed retry
            Handler(Looper.getMainLooper()).postDelayed({
                val retryExternal = findExternalCameraIds(context)
                if (retryExternal.isNotEmpty()) {
                    Log.i(TAG, "Delayed retry found external camera: ${retryExternal.first().first}")
                    attemptBindExternalCamera(retryExternal.first().first, owner, pv)
                } else {
                    Log.w(TAG, "Delayed retry still found no external camera — giving up this attempt")
                }
            }, USB_CAMERA_REGISTRATION_DELAY_MS)
            return
        }

        // Step 2: We found an external camera via Camera2 — try to bind to it
        val externalCameraId = externalCameras.first().first
        attemptBindExternalCamera(externalCameraId, owner, pv)
    }

    /**
     * Attempt to bind to an external camera by its Camera2 ID.
     *
     * Strategy:
     * 1. Try direct binding with CameraSelector filtered by camera ID
     * 2. If CameraX can't find it (not in availableCameraInfos), try
     *    LENS_FACING_EXTERNAL selector (API 30+)
     * 3. If that fails, try full provider reinit
     */
    private fun attemptBindExternalCamera(externalCameraId: String, owner: LifecycleOwner, pv: PreviewView) {
        Log.i(TAG, "attemptBindExternalCamera: Trying to bind to external camera id=$externalCameraId")

        // Avoid rapid retry of the same camera
        val now = System.currentTimeMillis()
        if (lastAttemptedCameraId == externalCameraId && (now - lastAttemptTime) < 3000) {
            Log.d(TAG, "Skipping duplicate attempt for camera $externalCameraId (too recent)")
            return
        }
        lastAttemptedCameraId = externalCameraId
        lastAttemptTime = now

        val provider = cameraProvider
        if (provider == null) {
            Log.w(TAG, "Provider not initialized yet — setting pendingUsbRescan and doing full reinit")
            pendingUsbRescan = true
            providerInitialized = false
            initInProgress = false
            init(owner, pv)
            return
        }

        // Method 1: Try CameraSelector with camera ID filter
        // This works even if the camera isn't in availableCameraInfos — CameraX can
        // still bind to cameras it discovers through the selector's camera filter
        val selectorById = CameraSelector.Builder()
            .addCameraFilter { cameras ->
                val matched = cameras.filter { cam ->
                    val id = getCameraIdFromCameraInfo(cam)
                    Log.d(TAG, "  CameraFilter: checking camera id=$id against target=$externalCameraId")
                    id == externalCameraId
                }
                if (matched.isEmpty()) {
                    Log.w(TAG, "  CameraFilter: NO camera matched id=$externalCameraId among ${cameras.size} cameras")
                } else {
                    Log.i(TAG, "  CameraFilter: matched camera id=$externalCameraId")
                }
                matched
            }
            .build()

        // Try binding with ID-based selector
        try {
            Log.i(TAG, "Attempting bindToLifecycle with ID-based selector for camera $externalCameraId")
            cameraProvider?.unbindAll()

            preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .build()
                .also { it.setSurfaceProvider(pv.surfaceProvider) }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .build()

            camera = provider.bindToLifecycle(owner, selectorById, preview, imageCapture)
            isUsingExternalCamera = true
            currentExternalCameraId = externalCameraId
            currentCameraSelector = selectorById
            currentLensFacing = CameraSelector.LENS_FACING_EXTERNAL
            _isConnected.value = true
            _cameraType.value = "external"
            _currentCameraId.value = externalCameraId
            refreshAvailableCamerasFromCamera2()

            Log.i(TAG, "═══════════════════════════════════════════════════")
            Log.i(TAG, "✓ SUCCESS: External camera $externalCameraId is now ACTIVE!")
            Log.i(TAG, "═══════════════════════════════════════════════════")
            return
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "ID-based selector failed for camera $externalCameraId: ${e.message}")
            Log.w(TAG, "CameraX doesn't know about this camera — trying LENS_FACING_EXTERNAL selector")
        } catch (e: Exception) {
            Log.w(TAG, "ID-based selector bind failed: ${e.javaClass.simpleName}: ${e.message}")
        }

        // Method 2: Try LENS_FACING_EXTERNAL selector (API 30+)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            try {
                val externalSelector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_EXTERNAL)
                    .build()

                if (provider.hasCamera(externalSelector)) {
                    Log.i(TAG, "LENS_FACING_EXTERNAL selector found camera — binding")
                    try {
                        cameraProvider?.unbindAll()

                        preview = Preview.Builder()
                            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                            .build()
                            .also { it.setSurfaceProvider(pv.surfaceProvider) }

                        imageCapture = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                            .build()

                        camera = provider.bindToLifecycle(owner, externalSelector, preview, imageCapture)
                        isUsingExternalCamera = true
                        currentExternalCameraId = externalCameraId
                        currentCameraSelector = externalSelector
                        currentLensFacing = CameraSelector.LENS_FACING_EXTERNAL
                        _isConnected.value = true
                        _cameraType.value = "external"
                        _currentCameraId.value = try {
                            camera?.cameraInfo?.let { getCameraIdFromCameraInfo(it) } ?: externalCameraId
                        } catch (e: Exception) { externalCameraId }
                        refreshAvailableCamerasFromCamera2()

                        Log.i(TAG, "═══════════════════════════════════════════════════")
                        Log.i(TAG, "✓ SUCCESS: External camera bound via LENS_FACING_EXTERNAL!")
                        Log.i(TAG, "═══════════════════════════════════════════════════")
                        return
                    } catch (e: Exception) {
                        Log.w(TAG, "LENS_FACING_EXTERNAL bindToLifecycle failed: ${e.message}")
                    }
                } else {
                    Log.w(TAG, "hasCamera(LENS_FACING_EXTERNAL) = false")
                }
            } catch (e: Exception) {
                Log.w(TAG, "LENS_FACING_EXTERNAL selector failed: ${e.message}")
            }
        }

        // Method 3: Full provider reinit with delay
        // This gives CameraX time to discover the USB camera through its own mechanisms
        Log.i(TAG, "Direct binding failed — attempting full provider reinit with delay")
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "Error unbinding during force reinit: ${e.message}")
        }

        cameraProvider = null
        providerInitialized = false
        initInProgress = false
        pendingUsbRescan = false

        // Add delay to let Camera2 service register the USB camera
        Handler(Looper.getMainLooper()).postDelayed({
            Log.i(TAG, "Delayed full reinit — checking Camera2 for external cameras first")
            val extCams = findExternalCameraIds(context)
            Log.i(TAG, "Before reinit, Camera2 sees ${extCams.size} external camera(s)")
            init(owner, pv)
        }, USB_CAMERA_REGISTRATION_DELAY_MS)
    }

    /**
     * Select the best available camera by enumerating all cameras.
     * Priority: External (USB capture card) → Back → Front
     *
     * CRITICAL FIX (v3): Uses Camera2 CameraManager for discovery FIRST,
     * then falls back to CameraX's availableCameraInfos.
     */
    private fun selectBestCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val provider = cameraProvider ?: return

        _isConnected.value = false
        _cameraType.value = "none"

        // ── Step 1: Use Camera2 (OS) to find external cameras ──
        // Camera2 ALWAYS sees USB cameras; CameraX may not.
        val externalCameras = findExternalCameraIds(context)
        Log.i(TAG, "selectBestCamera: Camera2 found ${externalCameras.size} external camera(s)")

        // Log CameraX's camera list for comparison
        val cameraXCameras = provider.availableCameraInfos
        Log.i(TAG, "selectBestCamera: CameraX has ${cameraXCameras.size} camera(s)")
        for (camInfo in cameraXCameras) {
            val camId = getCameraIdFromCameraInfo(camInfo)
            Log.d(TAG, "  CameraX camera: id=$camId, lensFacing=${camInfo.lensFacing}")
        }

        // ── Step 2: If Camera2 found an external camera, try to use it ──
        if (externalCameras.isNotEmpty()) {
            val externalId = externalCameras.first().first
            Log.i(TAG, "External camera found by Camera2 (id=$externalId) — attempting to bind")

            // First: Check if CameraX also knows about this camera
            val cameraXKnowsExternal = cameraXCameras.any { camInfo ->
                getCameraIdFromCameraInfo(camInfo) == externalId
            }
            Log.i(TAG, "CameraX knows about external camera $externalId: $cameraXKnowsExternal")

            // Try to build a selector for this camera
            val selector = CameraSelector.Builder()
                .addCameraFilter { cameras ->
                    cameras.filter { cam -> getCameraIdFromCameraInfo(cam) == externalId }
                }
                .build()

            // Attempt to bind
            try {
                cameraProvider?.unbindAll()

                preview = Preview.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                    .build()

                camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)

                isUsingExternalCamera = true
                currentExternalCameraId = externalId
                currentCameraSelector = selector
                currentLensFacing = CameraSelector.LENS_FACING_EXTERNAL
                _isConnected.value = true
                _cameraType.value = "external"
                _currentCameraId.value = externalId
                refreshAvailableCamerasFromCamera2()

                Log.i(TAG, "═══════════════════════════════════════════════════")
                Log.i(TAG, "✓ EXTERNAL CAMERA ACTIVE: id=$externalId")
                Log.i(TAG, "═══════════════════════════════════════════════════")
                return
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "External camera selector failed (IllegalArgumentException): ${e.message}")
                Log.w(TAG, "CameraX may not have this camera in its list — it was found by Camera2 but not CameraX")
            } catch (e: Exception) {
                Log.w(TAG, "External camera bind failed: ${e.javaClass.simpleName}: ${e.message}")
            }

            // If CameraX can't bind the external camera by ID, try LENS_FACING_EXTERNAL (API 30+)
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                try {
                    val externalSelector = CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_EXTERNAL)
                        .build()
                    if (provider.hasCamera(externalSelector)) {
                        Log.i(TAG, "Trying LENS_FACING_EXTERNAL selector (API 30+)")
                        try {
                            cameraProvider?.unbindAll()

                            preview = Preview.Builder()
                                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                                .build()
                                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                            imageCapture = ImageCapture.Builder()
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                                .build()

                            camera = provider.bindToLifecycle(lifecycleOwner, externalSelector, preview, imageCapture)
                            isUsingExternalCamera = true
                            currentExternalCameraId = externalId
                            currentCameraSelector = externalSelector
                            currentLensFacing = CameraSelector.LENS_FACING_EXTERNAL
                            _isConnected.value = true
                            _cameraType.value = "external"
                            _currentCameraId.value = try {
                                camera?.cameraInfo?.let { getCameraIdFromCameraInfo(it) } ?: externalId
                            } catch (e: Exception) { externalId }
                            refreshAvailableCamerasFromCamera2()

                            Log.i(TAG, "═══════════════════════════════════════════════════")
                            Log.i(TAG, "✓ EXTERNAL CAMERA ACTIVE via LENS_FACING_EXTERNAL!")
                            Log.i(TAG, "═══════════════════════════════════════════════════")
                            return
                        } catch (e: Exception) {
                            Log.w(TAG, "LENS_FACING_EXTERNAL bindToLifecycle failed: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "LENS_FACING_EXTERNAL selector not available: ${e.message}")
                }
            }

            // Camera2 found the external camera but CameraX can't bind it
            // This is a known limitation — log clearly and fall through to built-in
            Log.e(TAG, "╔══════════════════════════════════════════════════╗")
            Log.e(TAG, "║ USB camera detected by Camera2 but CameraX      ║")
            Log.e(TAG, "║ CANNOT bind to it. Falling back to built-in.     ║")
            Log.e(TAG, "║ This may require a full provider reinit.        ║")
            Log.e(TAG, "╚══════════════════════════════════════════════════╝")
        }

        // ── Step 3: Fall back to built-in cameras via CameraX ──
        Log.i(TAG, "No external camera available — trying built-in cameras")
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
                startCamera(lifecycleOwner, previewView)
                return
            }
        } catch (e: Exception) {
            Log.d(TAG, "No back camera found: ${e.message}")
        }

        // Try front camera
        try {
            val frontSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build()
            if (provider.hasCamera(frontSelector)) {
                currentLensFacing = CameraSelector.LENS_FACING_FRONT
                currentCameraSelector = frontSelector
                startCamera(lifecycleOwner, previewView)
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "No camera available at all: ${e.message}")
        }

        Log.e(TAG, "NO CAMERA DETECTED — device may have no camera or permission denied")
        _cameraType.value = "none"
        _isConnected.value = false
    }

    /**
     * Start the camera with the current selector.
     * This is used for built-in cameras. External cameras use direct binding
     * in selectBestCamera() / attemptBindExternalCamera().
     */
    private fun startCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val provider = cameraProvider ?: return
        val selector = currentCameraSelector ?: return

        try {
            provider.unbindAll()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unbind camera use cases: ${e.message}")
        }

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .build()
            .also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .build()

        try {
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                selector,
                preview,
                imageCapture
            )
            _isConnected.value = true

            _cameraType.value = when {
                isUsingExternalCamera -> "external"
                currentLensFacing == CameraSelector.LENS_FACING_BACK -> "back"
                currentLensFacing == CameraSelector.LENS_FACING_FRONT -> "front"
                else -> "unknown"
            }

            _currentCameraId.value = try {
                camera?.cameraInfo?.let { getCameraIdFromCameraInfo(it) } ?: ""
            } catch (e: Exception) {
                ""
            }

            refreshAvailableCamerasFromCamera2()

            Log.i(TAG, "Camera started successfully (type: ${_cameraType.value}, id: ${_currentCameraId.value}, external: $isUsingExternalCamera)")
        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission not granted (SecurityException): ${e.message}")
            _isConnected.value = false
            _cameraType.value = "none"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start camera (type: ${_cameraType.value}): ${e.message}")
            _isConnected.value = false

            if (isUsingExternalCamera) {
                Log.w(TAG, "External camera failed, falling back to back camera")
                isUsingExternalCamera = false
                currentExternalCameraId = null
                try {
                    val backSelector = CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .build()
                    if (provider.hasCamera(backSelector)) {
                        currentLensFacing = CameraSelector.LENS_FACING_BACK
                        currentCameraSelector = backSelector
                        startCamera(lifecycleOwner, previewView)
                        return
                    }
                } catch (e2: Exception) {
                    Log.e(TAG, "Back camera also failed: ${e2.message}")
                }
            }

            try {
                val frontSelector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                    .build()
                if (provider.hasCamera(frontSelector)) {
                    currentLensFacing = CameraSelector.LENS_FACING_FRONT
                    currentCameraSelector = frontSelector
                    startCamera(lifecycleOwner, previewView)
                    return
                }
            } catch (e3: Exception) {
                Log.e(TAG, "No camera available: ${e3.message}")
            }

            _cameraType.value = "none"
        }
    }

    /**
     * Switch between cameras in order: external → back → front → external
     */
    fun switchCamera() {
        val owner = lifecycleOwner ?: return
        val pv = previewView ?: return
        val provider = cameraProvider ?: return

        if (isUsingExternalCamera) {
            isUsingExternalCamera = false
            currentExternalCameraId = null
            currentLensFacing = CameraSelector.LENS_FACING_BACK
            currentCameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()
        } else if (currentLensFacing == CameraSelector.LENS_FACING_BACK) {
            // Try external first
            val externalCameras = findExternalCameraIds(context)
            if (externalCameras.isNotEmpty()) {
                val extId = externalCameras.first().first
                val selector = CameraSelector.Builder()
                    .addCameraFilter { cameras -> cameras.filter { getCameraIdFromCameraInfo(it) == extId } }
                    .build()
                isUsingExternalCamera = true
                currentExternalCameraId = extId
                currentCameraSelector = selector
            } else {
                currentLensFacing = CameraSelector.LENS_FACING_FRONT
                currentCameraSelector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                    .build()
            }
        } else {
            // Front → try external → back
            val externalCameras = findExternalCameraIds(context)
            if (externalCameras.isNotEmpty()) {
                val extId = externalCameras.first().first
                val selector = CameraSelector.Builder()
                    .addCameraFilter { cameras -> cameras.filter { getCameraIdFromCameraInfo(it) == extId } }
                    .build()
                isUsingExternalCamera = true
                currentExternalCameraId = extId
                currentCameraSelector = selector
            } else {
                currentLensFacing = CameraSelector.LENS_FACING_BACK
                currentCameraSelector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                    .build()
            }
        }

        startCamera(owner, pv)
    }

    /**
     * Refresh available cameras list using Camera2 CameraManager (OS-level).
     * This ALWAYS sees USB cameras, unlike CameraX's stale snapshot.
     */
    fun refreshAvailableCamerasFromCamera2() {
        val cameras = getAllCameraIdsFromCamera2(context)
        _availableCameras.value = cameras.map { (id, _, name) -> id to name }
        Log.d(TAG, "Available cameras (from Camera2): ${cameras.map { "${it.first}=${it.third}" }}")
    }

    /**
     * Get list of available camera descriptions for UI camera picker.
     * Uses Camera2 for discovery (always current).
     */
    fun getAvailableCameras(): List<Pair<String, String>> {
        refreshAvailableCamerasFromCamera2()
        return _availableCameras.value
    }

    fun refreshAvailableCameras() {
        refreshAvailableCamerasFromCamera2()
    }

    /**
     * Switch to a specific camera by its ID.
     * Uses Camera2 to verify the camera exists, then constructs selector.
     */
    fun switchToCameraById(cameraId: String) {
        val owner = lifecycleOwner ?: return
        val pv = previewView ?: return
        val provider = cameraProvider ?: return

        // Check if this is an external camera via Camera2
        val allCameras = getAllCameraIdsFromCamera2(context)
        val targetCamera = allCameras.find { it.first == cameraId }

        if (targetCamera == null) {
            Log.w(TAG, "switchToCameraById: Camera $cameraId not found in Camera2 list")
            return
        }

        val isExternal = targetCamera.second == CameraSelector.LENS_FACING_EXTERNAL

        if (isExternal) {
            // External camera — use direct binding approach
            attemptBindExternalCamera(cameraId, owner, pv)
        } else {
            // Built-in camera — use standard selector
            val lensFacing = targetCamera.second
            val selector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            isUsingExternalCamera = false
            currentExternalCameraId = null
            currentLensFacing = lensFacing
            currentCameraSelector = selector
            _currentCameraId.value = cameraId

            Log.i(TAG, "switchToCameraById: Switching to built-in camera $cameraId (facing=$lensFacing)")
            startCamera(owner, pv)
        }
    }

    /**
     * Called when USB device is attached/detached.
     */
    fun onUsbDeviceChanged() {
        if (cameraProvider == null || !providerInitialized) {
            Log.i(TAG, "onUsbDeviceChanged: provider not ready, setting pendingUsbRescan flag")
            pendingUsbRescan = true
            return
        }
        // Provider is ready — handled by ViewModel's delayed forceReinitForUSB
    }

    /**
     * Re-scan for external cameras. Called by ViewModel with proper timing.
     */
    fun rescanForExternalCamera() {
        if (cameraProvider == null) {
            Log.i(TAG, "rescanForExternalCamera: provider not ready, setting pendingUsbRescan flag")
            pendingUsbRescan = true
            return
        }

        refreshAvailableCamerasFromCamera2()

        val owner = lifecycleOwner ?: return
        val pv = previewView ?: return

        val externalCameras = findExternalCameraIds(context)
        if (externalCameras.isNotEmpty() && !isUsingExternalCamera) {
            Log.i(TAG, "External camera detected after rescan, switching to it")
            attemptBindExternalCamera(externalCameras.first().first, owner, pv)
        } else if (externalCameras.isEmpty() && isUsingExternalCamera) {
            Log.w(TAG, "External camera lost after rescan, falling back to built-in")
            isUsingExternalCamera = false
            currentExternalCameraId = null
            currentLensFacing = CameraSelector.LENS_FACING_BACK
            currentCameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()
            startCamera(owner, pv)
        }
    }

    /**
     * Capture a photo and return the Bitmap via callback.
     */
    fun capturePhoto(onResult: (Bitmap?) -> Unit) {
        val capture = imageCapture ?: run {
            Log.e(TAG, "ImageCapture not initialized — camera not started?")
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
                            Bitmap.createBitmap(
                                bitmap, 0, 0, bitmap.width, bitmap.height,
                                matrix, true
                            )
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

    // ─── Utility ────────────────────────────────────────────────

    /**
     * Get the camera ID string from a CameraInfo object using Camera2 interop.
     */
    private fun getCameraIdFromCameraInfo(cameraInfo: CameraInfo): String? {
        return try {
            Camera2CameraInfo.from(cameraInfo).cameraId
        } catch (e: Exception) {
            Log.d(TAG, "Cannot get Camera2 camera ID: ${e.message}")
            null
        }
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
            Log.e(TAG, "YUV conversion failed, trying direct buffer decode: ${e.message}")
            val buffer = planes[0].buffer
            val bytes = ByteArray(buffer.capacity())
            buffer.get(bytes)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IllegalStateException("Failed to convert image format: $format")
        }
    }

    fun destroy() {
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "Error unbinding during destroy: ${e.message}")
        }
        cameraProvider = null
        preview = null
        imageCapture = null
        camera = null
        lifecycleOwner = null
        previewView = null
        currentCameraSelector = null
        currentExternalCameraId = null
        isUsingExternalCamera = false
        providerInitialized = false
        initInProgress = false
        pendingUsbRescan = false
        lastAttemptedCameraId = null
        _isConnected.value = false
        _cameraType.value = "none"
        _currentCameraId.value = ""
        _availableCameras.value = emptyList()
    }
}
