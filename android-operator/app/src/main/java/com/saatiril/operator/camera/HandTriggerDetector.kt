package com.saatiril.operator.camera

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.handlandmarks.HandLandmark
import com.google.mlkit.vision.handlandmarks.HandLandmarker
import com.google.mlkit.vision.handlandmarks.HandLandmarkingOptions
import com.google.mlkit.vision.handlandmarks.HandLandmarks

/**
 * Hand Trigger Detector — "Trigger Tangan"
 *
 * Uses ML Kit Hand Landmarker to detect ANY hand in the camera frame
 * (open palm or closed fist) and trigger the camera shutter.
 *
 * This matches the Chrome/Electron version's use-palm-detection.ts behavior:
 * - ANY hand visible = trigger (not just 5 fingers extended)
 * - 300ms sustain required before confirming (debounce against flicker)
 * - onHandConfirmed fires ONCE → triggers capture
 * - onHandReleased fires when hand leaves frame → cancels countdown
 *
 * ML Kit processes camera preview bitmaps (from TextureView.getBitmap())
 * on a background thread — no JS/MediaPipe scripts needed.
 */
object HandTriggerDetector {
    private const val TAG = "HandTrigger"

    // How long the hand must be visible before confirming (ms)
    private const val CONFIRM_SUSTAIN_MS = 300L

    // Minimum detection confidence
    private const val MIN_CONFIDENCE = 0.5f

    private var handLandmarker: HandLandmarker? = null
    private var isInitialized = false
    private var isRunning = false

    // State tracking
    private var handVisibleSince = 0L
    private var isConfirmed = false

    // Callbacks
    var onHandConfirmed: (() -> Unit)? = null
    var onHandReleased: (() -> Unit)? = null

    // UI state (observable via StateFlow in ViewModel)
    var handState: HandState = HandState.NONE
        private set
    var fingersExtended: Int = 0
        private set

    enum class HandState {
        NONE,       // No hand detected
        HELD,       // Hand visible, waiting for sustain
        CONFIRMED   // Hand confirmed → trigger shutter
    }

    /**
     * Initialize the ML Kit Hand Landmarker.
     * Call once before starting detection.
     */
    fun initialize(): Boolean {
        if (isInitialized) return true

        return try {
            val options = HandLandmarkingOptions.Builder()
                .setMinHandDetectionConfidence(MIN_CONFIDENCE)
                .setMinHandTrackingConfidence(MIN_CONFIDENCE)
                .setMinHandPresenceConfidence(MIN_CONFIDENCE)
                .setRunningMode(com.google.mlkit.vision.handlandmarks.RunningMode.IMAGE)
                .build()

            handLandmarker = HandLandmarker.getClient(options)
            isInitialized = true
            Log.i(TAG, "ML Kit Hand Landmarker initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Hand Landmarker: ${e.message}")
            false
        }
    }

    /**
     * Start hand detection. Must call initialize() first.
     */
    fun start() {
        if (!isInitialized) {
            Log.w(TAG, "Cannot start — not initialized")
            return
        }
        isRunning = true
        handVisibleSince = 0L
        isConfirmed = false
        handState = HandState.NONE
        fingersExtended = 0
        Log.i(TAG, "Hand trigger detection started")
    }

    /**
     * Stop hand detection.
     */
    fun stop() {
        isRunning = false
        handVisibleSince = 0L
        isConfirmed = false
        handState = HandState.NONE
        fingersExtended = 0
        Log.i(TAG, "Hand trigger detection stopped")
    }

    /**
     * Process a camera preview bitmap.
     * Call this from a background thread/coroutine with each preview frame.
     *
     * @param bitmap Camera preview frame (from TextureView.getBitmap())
     * @return true if a hand was detected in this frame
     */
    fun processFrame(bitmap: Bitmap): Boolean {
        if (!isRunning || !isInitialized) return false

        try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val result = handLandmarker?.process(inputImage) ?: return false

            val hands = result.handLandmarks
            val handDetected = hands.isNotEmpty()

            // Count extended fingers for UI indicator (first hand only)
            fingersExtended = if (handDetected) {
                countExtendedFingers(hands[0])
            } else {
                0
            }

            val now = System.currentTimeMillis()

            if (handDetected) {
                if (handVisibleSince == 0L) {
                    // Hand just appeared
                    handVisibleSince = now
                    handState = HandState.HELD
                } else if (!isConfirmed && now - handVisibleSince >= CONFIRM_SUSTAIN_MS) {
                    // Sustained long enough → confirm
                    isConfirmed = true
                    handState = HandState.CONFIRMED
                    Log.i(TAG, "Hand confirmed — triggering shutter")
                    onHandConfirmed?.invoke()
                }
            } else {
                // No hand
                if (isConfirmed) {
                    Log.i(TAG, "Hand left frame — cancelling")
                    onHandReleased?.invoke()
                }
                handVisibleSince = 0L
                isConfirmed = false
                handState = HandState.NONE
            }

            return handDetected
        } catch (e: Exception) {
            // ML Kit can occasionally throw on bad frames — skip silently
            return false
        }
    }

    /**
     * Count extended fingers from hand landmarks (for UI indicator only).
     * Returns 0–5. This is NOT used for triggering — any hand triggers.
     */
    private fun countExtendedFingers(landmarks: List<HandLandmark>): Int {
        if (landmarks.size < 21) return 0

        val tipIds = listOf(4, 8, 12, 16, 20)
        val pipIds = listOf(3, 6, 10, 14, 18)

        var fingersUp = 0

        // Thumb: compare x position relative to hand orientation
        val thumbTip = landmarks[tipIds[0]]
        val thumbIp = landmarks[pipIds[0]]
        val wrist = landmarks[0]
        val middleMcp = landmarks[9]
        val isRightHand = wrist.position.x < middleMcp.position.x

        if (isRightHand) {
            if (thumbTip.position.x < thumbIp.position.x) fingersUp++
        } else {
            if (thumbTip.position.x > thumbIp.position.x) fingersUp++
        }

        // Other 4 fingers: tip above PIP (lower y = higher) means extended
        for (i in 1..4) {
            val tip = landmarks[tipIds[i]]
            val pip = landmarks[pipIds[i]]
            if (tip.position.y < pip.position.y) fingersUp++
        }

        return fingersUp
    }

    /**
     * Release resources.
     */
    fun dispose() {
        stop()
        handLandmarker?.close()
        handLandmarker = null
        isInitialized = false
        onHandConfirmed = null
        onHandReleased = null
        Log.i(TAG, "Hand trigger detector disposed")
    }

    /**
     * Check if detection is currently running.
     */
    fun isDetecting(): Boolean = isRunning
}
