package com.saatiril.operator.camera

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

/**
 * Hand Trigger Detector — "Trigger Tangan"
 *
 * Uses MediaPipe Hand Landmarker to detect ANY hand in the camera frame
 * (open palm or closed fist) and trigger the camera shutter.
 *
 * This matches the Chrome/Electron version's use-palm-detection.ts behavior:
 * - ANY hand visible = trigger (not just 5 fingers extended)
 * - 300ms sustain required before confirming (debounce against flicker)
 * - onHandConfirmed fires ONCE → triggers capture (starts timer or direct capture)
 * - onHandReleased fires when hand leaves frame → does NOT cancel timer
 *   (photobooth behavior: once hand confirms, timer always completes so the
 *   person being photographed can pose while the countdown runs)
 *
 * MediaPipe processes camera preview bitmaps (from TextureView.getBitmap())
 * on a background thread — no JavaScript/MediaPipe scripts needed.
 *
 * NOTE: com.google.mlkit:hand-detection does NOT exist on Maven.
 * Google's hand detection is only available via MediaPipe Tasks Vision
 * (com.google.mediapipe:tasks-vision).
 */
object HandTriggerDetector {
    private const val TAG = "HandTrigger"

    // How long the hand must be visible before confirming (ms)
    private const val CONFIRM_SUSTAIN_MS = 300L

    // Cooldown after a confirmation before another can fire (ms)
    // Prevents re-triggering while the capture/timer flow is still running
    private const val CONFIRM_COOLDOWN_MS = 5000L

    // Minimum detection confidence
    private const val MIN_CONFIDENCE = 0.5f

    // Model file in assets folder
    private const val MODEL_PATH = "hand_landmarker.task"

    private var handLandmarker: HandLandmarker? = null
    private var isInitialized = false
    private var isRunning = false

    // State tracking
    private var handVisibleSince = 0L
    private var isConfirmed = false
    private var lastConfirmTime = 0L  // Cooldown tracking

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
     * Initialize the MediaPipe Hand Landmarker.
     * Call once before starting detection.
     *
     * @param context Application context (needed for asset loading)
     */
    fun initialize(context: Context): Boolean {
        if (isInitialized) return true

        return try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_PATH)
                .build()

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setMinHandDetectionConfidence(MIN_CONFIDENCE)
                .setMinTrackingConfidence(MIN_CONFIDENCE)
                .setMinHandPresenceConfidence(MIN_CONFIDENCE)
                .setNumHands(1)
                .setRunningMode(RunningMode.IMAGE)
                .build()

            handLandmarker = HandLandmarker.createFromOptions(context, options)
            isInitialized = true
            Log.i(TAG, "MediaPipe Hand Landmarker initialized successfully")
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
        lastConfirmTime = 0L
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
        lastConfirmTime = 0L
        handState = HandState.NONE
        fingersExtended = 0
        Log.i(TAG, "Hand trigger detection stopped")
    }

    /**
     * Process a camera preview bitmap.
     * Call this from a background thread/coroutine with each preview frame.
     *
     * MediaPipe's IMAGE mode detect() is synchronous (blocking) —
     * it returns the result directly without needing Tasks.await().
     *
     * PHOTOBOOTH BEHAVIOR:
     * Once a hand is confirmed and triggers capture (timer or direct),
     * the timer/capture ALWAYS runs to completion — removing the hand
     * does NOT cancel it. This allows the person being photographed to
     * remove their hand and pose while the countdown runs.
     *
     * @param bitmap Camera preview frame (from TextureView.getBitmap())
     * @return true if a hand was detected in this frame
     */
    fun processFrame(bitmap: Bitmap): Boolean {
        if (!isRunning || !isInitialized) return false

        try {
            // Build MediaPipe image from bitmap
            val mpImage = BitmapImageBuilder(bitmap).build()

            // Synchronous detection in IMAGE mode
            val result: HandLandmarkerResult? = handLandmarker?.detect(mpImage)
            if (result == null) return false

            // Check if any hands detected
            val landmarks = result.landmarks()
            val handDetected = landmarks.isNotEmpty()

            // Count extended fingers for UI indicator (first hand only)
            fingersExtended = if (handDetected) {
                countExtendedFingers(landmarks[0])
            } else {
                0
            }

            val now = System.currentTimeMillis()

            if (handDetected) {
                // Check cooldown — skip detection if recently confirmed
                if (lastConfirmTime > 0 && now - lastConfirmTime < CONFIRM_COOLDOWN_MS) {
                    // Still in cooldown period — keep state as NONE
                    handState = HandState.NONE
                    return true
                }

                if (handVisibleSince == 0L) {
                    // Hand just appeared
                    handVisibleSince = now
                    handState = HandState.HELD
                } else if (!isConfirmed && now - handVisibleSince >= CONFIRM_SUSTAIN_MS) {
                    // Sustained long enough → confirm
                    isConfirmed = true
                    lastConfirmTime = now
                    handState = HandState.CONFIRMED
                    Log.i(TAG, "Hand confirmed — triggering shutter")
                    onHandConfirmed?.invoke()
                }
            } else {
                // No hand in frame
                if (isConfirmed) {
                    // Hand was confirmed and now left — photobooth behavior:
                    // Do NOT cancel the timer. Just reset detection state so
                    // the next hand can trigger a new capture later.
                    Log.i(TAG, "Hand left frame after confirmation — timer continues")
                    onHandReleased?.invoke()
                }
                handVisibleSince = 0L
                isConfirmed = false
                handState = HandState.NONE
            }

            return handDetected
        } catch (e: Exception) {
            // MediaPipe can occasionally throw on bad frames — skip silently
            return false
        }
    }

    /**
     * Count extended fingers from hand landmarks (for UI indicator only).
     * Returns 0–5. This is NOT used for triggering — any hand triggers.
     *
     * Each NormalizedLandmark has .x, .y, .z (normalized 0-1).
     * 21 landmarks per hand:
     *   0: WRIST, 1-4: THUMB (CMC,MCP,IP,TIP), 5-8: INDEX (MCP,PIP,DIP,TIP),
     *   9-12: MIDDLE, 13-16: RING, 17-20: PINKY
     */
    private fun countExtendedFingers(handLandmarks: List<NormalizedLandmark>): Int {
        if (handLandmarks.size < 21) return 0

        val tipIds = listOf(4, 8, 12, 16, 20)
        val pipIds = listOf(3, 6, 10, 14, 18)

        var fingersUp = 0

        // Thumb: compare x position relative to hand orientation
        val thumbTip = handLandmarks[tipIds[0]]
        val thumbIp = handLandmarks[pipIds[0]]
        val wrist = handLandmarks[0]
        val middleMcp = handLandmarks[9]
        val isRightHand = wrist.x() < middleMcp.x()

        if (isRightHand) {
            if (thumbTip.x() < thumbIp.x()) fingersUp++
        } else {
            if (thumbTip.x() > thumbIp.x()) fingersUp++
        }

        // Other 4 fingers: tip above PIP (lower y = higher) means extended
        for (i in 1..4) {
            val tip = handLandmarks[tipIds[i]]
            val pip = handLandmarks[pipIds[i]]
            if (tip.y() < pip.y()) fingersUp++
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
