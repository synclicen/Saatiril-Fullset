package com.saatiril.operator.camera

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
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
 * Camera selection strategy:
 * 1. EXTERNAL (USB HDMI capture cards) — highest priority
 * 2. BACK — built-in rear camera (fallback)
 * 3. FRONT — built-in front camera (last resort)
 *
 * CRITICAL FIXES in this version:
 * - forceReinitForUSB(): Re-initializes ProcessCameraProvider when USB device
 *   is detected, because CameraX 1.3.x's availableCameraInfos is a SNAPSHOT
 *   that does NOT update when cameras are hot-plugged.
 * - findExternalCamera() uses Camera2 LENS_FACING characteristic for reliable
 *   detection (not just string matching on camera IDs which are numeric on
 *   most devices like "2", "3" etc.)
 * - isExternalCameraId() improved: removed overly broad regex, added
 *   Camera2 characteristic-based detection
 * - Pending rescan with delay: when USB detected before provider is ready,
 *   the provider re-init includes a delay for Camera2 service registration
 * - Periodic rescan support: ViewModel can trigger periodic checks for
 *   USB cameras that weren't detected on first attempt
 */
@androidx.camera.camera2.interop.ExperimentalCamera2Interop
class BuiltInCameraManager(private val context: Context) {

    companion object {
        private const val TAG = "BuiltInCameraManager"
        private const val USB_CAMERA_REGISTRATION_DELAY_MS = 1500L
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var currentCameraSelector: CameraSelector? = null
    private var currentLensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var isUsingExternalCamera: Boolean = false
    private var lifecycleOwner: LifecycleOwner? = null
    private var previewView: PreviewView? = null

    // Track whether camera provider has been initialized
    private var providerInitialized: Boolean = false
    private var initInProgress: Boolean = false

    // Pending rescan flag — set when USB device is detected before provider is ready.
    // The rescan is executed with a delay once the provider initializes.
    private var pendingUsbRescan: Boolean = false

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    // Camera source: "external", "back", "front", "none"
    private val _cameraType = MutableStateFlow("none")
    val cameraType: StateFlow<String> = _cameraType.asStateFlow()

    // Current camera ID — tracks which camera is actively in use
    private val _currentCameraId = MutableStateFlow("")
    val currentCameraId: StateFlow<String> = _currentCameraId.asStateFlow()

    // Available cameras — reactive list that updates when cameras are discovered/lost
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
        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

            cameraProviderFuture.addListener({
                try {
                    cameraProvider = cameraProviderFuture.get()
                    providerInitialized = true
                    initInProgress = false

                    // CRITICAL FIX: If USB was detected before provider was ready,
                    // delay camera selection to allow Camera2 service to register
                    // the external USB camera. Without this delay, availableCameraInfos
                    // won't include the USB camera because CameraX 1.3.x takes a
                    // snapshot at provider init time.
                    if (pendingUsbRescan) {
                        Log.i(TAG, "USB was detected before provider ready — delaying selection for Camera2 registration")
                        pendingUsbRescan = false
                        Handler(Looper.getMainLooper()).postDelayed({
                            // Re-init provider to get fresh camera list including USB camera
                            forceReinitProviderThenSelect()
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
        init(lifecycleOwner, previewView)
    }

    /**
     * CRITICAL FIX: Force full re-initialization of ProcessCameraProvider
     * when a USB camera is detected. This is necessary because CameraX 1.3.x's
     * availableCameraInfos is a SNAPSHOT and does NOT update when cameras are
     * hot-plugged. We must get a fresh ProcessCameraProvider instance to
     * enumerate cameras including the newly attached USB camera.
     *
     * Called by OperatorViewModel when UVC device is detected, with a delay
     * to allow Camera2 service time to register the external camera.
     */
    fun forceReinitForUSB() {
        val owner = lifecycleOwner ?: return
        val pv = previewView ?: return

        Log.i(TAG, "forceReinitForUSB: Full re-init to detect USB camera")

        // Clean up existing provider completely
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "Error unbinding during force reinit: ${e.message}")
        }

        // Reset all provider state so init() gets a fresh ProcessCameraProvider
        cameraProvider = null
        providerInitialized = false
        initInProgress = false
        pendingUsbRescan = false
        _isConnected.value = false
        _cameraType.value = "none"

        // Re-initialize — this gets a fresh ProcessCameraProvider
        // which will enumerate all cameras including the USB camera
        init(owner, pv)
    }

    /**
     * Internal: Force reinitialize the ProcessCameraProvider and then select best camera.
     * Used when we detect that a USB camera was attached but the current provider
     * doesn't list it (stale snapshot).
     */
    private fun forceReinitProviderThenSelect() {
        val owner = lifecycleOwner ?: return
        val pv = previewView ?: return

        Log.i(TAG, "forceReinitProviderThenSelect: Resetting provider for fresh camera enumeration")

        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "Error unbinding during forced provider reinit: ${e.message}")
        }

        // Reset provider state completely so ProcessCameraProvider.getInstance()
        // returns a new instance with fresh camera list
        cameraProvider = null
        providerInitialized = false
        initInProgress = false

        init(owner, pv)
    }

    /**
     * Select the best available camera by enumerating all cameras.
     * Priority: External (USB capture card) → Back → Front
     */
    private fun selectBestCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val provider = cameraProvider ?: return

        _isConnected.value = false
        _cameraType.value = "none"

        val availableCameras = provider.availableCameraInfos
        Log.i(TAG, "Available cameras: ${availableCameras.size}")

        // Log each camera for debugging with external detection info
        for (cameraInfo in availableCameras) {
            val cameraId = getCameraId(cameraInfo)
            val lensFacing = cameraInfo.lensFacing
            val isExt = isExternalByCamera2(cameraInfo, cameraId ?: "")
            Log.d(TAG, "  Camera: id=$cameraId, lensFacing=$lensFacing, isExternal=$isExt")
        }

        // Try to find an external camera (USB HDMI capture card)
        val externalCamera = findExternalCamera(provider)
        if (externalCamera != null) {
            Log.i(TAG, "Found external camera, using it")
            isUsingExternalCamera = true
            currentCameraSelector = externalCamera
            startCamera(lifecycleOwner, previewView)
            return
        }

        Log.i(TAG, "No external camera found, trying built-in cameras")
        isUsingExternalCamera = false

        // Fallback to back camera
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

        // Fallback to front camera
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
     * Find an external camera (USB HDMI capture card).
     *
     * Uses multiple detection methods in order of reliability:
     * 1. Camera2 LENS_FACING characteristic (most reliable)
     * 2. LENS_FACING_EXTERNAL selector (API 30+)
     * 3. String-based ID matching ("external", "usb", "uvc")
     * 4. Heuristic: extra camera with UVC device present
     */
    private fun findExternalCamera(provider: ProcessCameraProvider): CameraSelector? {
        try {
            val cameraInfos = provider.availableCameraInfos

            // ── Method 1: Camera2 LENS_FACING characteristic ──
            // This is the MOST RELIABLE method. Camera IDs on most devices
            // are just numbers ("0", "1", "2"), so string matching fails.
            // Camera2's LENS_FACING_EXTERNAL = 2 reliably identifies external cameras.
            for (cameraInfo in cameraInfos) {
                val cameraId = getCameraId(cameraInfo) ?: continue
                if (isExternalByCamera2(cameraInfo, cameraId)) {
                    Log.i(TAG, "Found external camera by Camera2 LENS_FACING: id=$cameraId")
                    val targetCameraId = cameraId
                    val selector = CameraSelector.Builder()
                        .addCameraFilter { cameras ->
                            cameras.filter { cam -> getCameraId(cam) == targetCameraId }
                        }
                        .build()
                    return selector
                }
            }

            // ── Method 2: LENS_FACING_EXTERNAL selector (API 30+) ──
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                try {
                    val externalSelector = CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_EXTERNAL)
                        .build()
                    if (provider.hasCamera(externalSelector)) {
                        Log.i(TAG, "Found camera via LENS_FACING_EXTERNAL selector")
                        currentLensFacing = CameraSelector.LENS_FACING_EXTERNAL
                        return externalSelector
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "LENS_FACING_EXTERNAL not supported or no external camera: ${e.message}")
                }
            }

            // ── Method 3: String-based ID matching ──
            for (cameraInfo in cameraInfos) {
                val cameraId = getCameraId(cameraInfo)
                if (cameraId != null && isExternalCameraId(cameraId)) {
                    Log.i(TAG, "Found external camera by ID pattern: $cameraId")
                    val targetCameraId = cameraId
                    val selector = CameraSelector.Builder()
                        .addCameraFilter { cameras ->
                            cameras.filter { cam -> getCameraId(cam) == targetCameraId }
                        }
                        .build()
                    return selector
                }
            }

            // ── Method 4: Heuristic — extra camera + UVC device present ──
            // If UVC device is physically connected AND there are more than 2 cameras,
            // the camera with ID >= 2 is likely the USB capture card.
            if (UVCCameraManager.hasUVCDevice(context) && cameraInfos.size > 2) {
                Log.i(TAG, "UVC device present with ${cameraInfos.size} cameras — looking for extra camera")
                for (cameraInfo in cameraInfos) {
                    val cameraId = getCameraId(cameraInfo) ?: continue
                    val idNum = cameraId.toIntOrNull()
                    // Camera IDs "0" and "1" are typically back and front.
                    // ID "2" or higher is likely the USB capture card.
                    if (idNum != null && idNum >= 2) {
                        Log.i(TAG, "Found likely USB camera (id >= 2 with UVC device): id=$cameraId")
                        val targetCameraId = cameraId
                        val selector = CameraSelector.Builder()
                            .addCameraFilter { cameras ->
                                cameras.filter { cam -> getCameraId(cam) == targetCameraId }
                            }
                            .build()
                        return selector
                    }
                }
            }

            Log.i(TAG, "No external camera detected among ${cameraInfos.size} cameras")
        } catch (e: Exception) {
            Log.e(TAG, "Error finding external camera: ${e.message}")
        }

        return null
    }

    /**
     * Check if a camera is external using Camera2 characteristics.
     * This is the most reliable method — checks LENS_FACING value.
     * LENS_FACING_EXTERNAL = 2 in Camera2 API.
     */
    private fun isExternalByCamera2(cameraInfo: CameraInfo, cameraId: String): Boolean {
        return try {
            val camera2Info = Camera2CameraInfo.from(cameraInfo)
            val characteristics = camera2Info.cameraCharacteristics
            val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
            lensFacing == CameraCharacteristics.LENS_FACING_EXTERNAL
        } catch (e: Exception) {
            Log.d(TAG, "Cannot check Camera2 characteristics for camera $cameraId: ${e.message}")
            false
        }
    }

    /**
     * Get the camera ID string from a CameraInfo object.
     */
    private fun getCameraId(cameraInfo: CameraInfo): String? {
        return try {
            Camera2CameraInfo.from(cameraInfo).cameraId
        } catch (e: Exception) {
            Log.d(TAG, "Cannot get Camera2 camera ID: ${e.message}")
            null
        }
    }

    /**
     * Check if a camera ID string indicates an external camera.
     * Note: On most devices, external cameras have simple numeric IDs like "2", "3"
     * which WON'T match here. Use isExternalByCamera2() for reliable detection.
     * This is a secondary/fallback method.
     */
    private fun isExternalCameraId(cameraId: String): Boolean {
        val lowerId = cameraId.lowercase()
        return lowerId.contains("external") ||
               lowerId.contains("usb") ||
               lowerId.contains("uvc")
        // REMOVED: overly broad regex ".*\\d+-.*" which caused false positives
    }

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
                camera?.cameraInfo?.let { getCameraId(it) } ?: ""
            } catch (e: Exception) {
                ""
            }

            refreshAvailableCameras()

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
     * Switch between front and back camera.
     */
    fun switchCamera() {
        val owner = lifecycleOwner ?: return
        val pv = previewView ?: return
        val provider = cameraProvider ?: return

        if (isUsingExternalCamera) {
            isUsingExternalCamera = false
            currentLensFacing = CameraSelector.LENS_FACING_BACK
            currentCameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()
        } else if (currentLensFacing == CameraSelector.LENS_FACING_BACK) {
            val external = findExternalCamera(provider)
            if (external != null) {
                isUsingExternalCamera = true
                currentCameraSelector = external
            } else {
                currentLensFacing = CameraSelector.LENS_FACING_FRONT
                currentCameraSelector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                    .build()
            }
        } else {
            val external = findExternalCamera(provider)
            if (external != null) {
                isUsingExternalCamera = true
                currentCameraSelector = external
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
     * Get list of available camera descriptions for UI camera picker.
     * Also updates the reactive _availableCameras StateFlow.
     */
    fun getAvailableCameras(): List<Pair<String, String>> {
        val provider = cameraProvider ?: return emptyList()
        val cameras = mutableListOf<Pair<String, String>>()

        for (cameraInfo in provider.availableCameraInfos) {
            val cameraId = getCameraId(cameraInfo) ?: continue
            val isExt = isExternalByCamera2(cameraInfo, cameraId)
            val displayName = when {
                isExt -> "USB Capture Card"
                cameraInfo.lensFacing == CameraSelector.LENS_FACING_BACK -> "Kamera Belakang"
                cameraInfo.lensFacing == CameraSelector.LENS_FACING_FRONT -> "Kamera Depan"
                else -> "Kamera ($cameraId)"
            }
            cameras.add(cameraId to displayName)
        }
        _availableCameras.value = cameras
        return cameras
    }

    fun refreshAvailableCameras() {
        getAvailableCameras()
    }

    /**
     * Switch to a specific camera by its ID.
     */
    fun switchToCameraById(cameraId: String) {
        val owner = lifecycleOwner ?: return
        val pv = previewView ?: return
        val provider = cameraProvider ?: return

        val targetCameraInfo = provider.availableCameraInfos.find { getCameraId(it) == cameraId } ?: return

        val targetId = cameraId
        val selector = CameraSelector.Builder()
            .addCameraFilter { cameras ->
                cameras.filter { getCameraId(it) == targetId }
            }
            .build()

        isUsingExternalCamera = isExternalByCamera2(targetCameraInfo, cameraId) || isExternalCameraId(cameraId)
        currentLensFacing = when {
            isUsingExternalCamera -> CameraSelector.LENS_FACING_EXTERNAL
            targetCameraInfo.lensFacing == CameraSelector.LENS_FACING_BACK -> CameraSelector.LENS_FACING_BACK
            targetCameraInfo.lensFacing == CameraSelector.LENS_FACING_FRONT -> CameraSelector.LENS_FACING_FRONT
            else -> CameraSelector.LENS_FACING_BACK
        }
        currentCameraSelector = selector
        _currentCameraId.value = cameraId

        Log.i(TAG, "switchToCameraById: Switching to camera $cameraId (external=$isUsingExternalCamera)")
        startCamera(owner, pv)
    }

    /**
     * Called when USB device is attached/detached.
     * Sets pendingUsbRescan flag if provider isn't ready yet.
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
     * If an external camera is found and we're not using it, switch to it.
     * If external camera is lost, fall back to built-in.
     */
    fun rescanForExternalCamera() {
        if (cameraProvider == null) {
            Log.i(TAG, "rescanForExternalCamera: provider not ready, setting pendingUsbRescan flag")
            pendingUsbRescan = true
            return
        }

        refreshAvailableCameras()

        val owner = lifecycleOwner ?: return
        val pv = previewView ?: return
        val provider = cameraProvider ?: return

        val external = findExternalCamera(provider)
        if (external != null && !isUsingExternalCamera) {
            Log.i(TAG, "External camera detected after rescan, switching to it")
            isUsingExternalCamera = true
            currentCameraSelector = external
            startCamera(owner, pv)
        } else if (external == null && isUsingExternalCamera) {
            Log.w(TAG, "External camera lost after rescan, falling back to built-in")
            isUsingExternalCamera = false
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
        isUsingExternalCamera = false
        providerInitialized = false
        initInProgress = false
        pendingUsbRescan = false
        _isConnected.value = false
        _cameraType.value = "none"
        _currentCameraId.value = ""
        _availableCameras.value = emptyList()
    }
}
