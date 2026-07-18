package com.saatiril.operator.camera

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

/**
 * Hand Trigger Detector — "Trigger Tangan"
 *
 * PHOTOBOOTH TRIGGER: Hand appears → hand leaves frame → timer starts.
 *
 * Flow:
 *   1. Person shows hand to camera
 *   2. Hand must be visible for 500ms to be "confirmed" (debounce)
 *   3. Indicator turns green: "Tangan terdeteksi ✓"
 *   4. Person removes hand from frame → TIMER STARTS immediately
 *   5. Person poses during countdown → photo taken at 0
 *
 * This is the most intuitive photobooth trigger because:
 * - Showing hand = "I'm ready"
 * - Removing hand = "Start the timer!"
 * - Person already has hand away = ready to pose immediately
 * - No complex gesture needed, just show then remove hand
 *
 * After timer starts, it ALWAYS completes (photobooth behavior).
 * 5-second cooldown prevents re-triggering.
 */
object HandTriggerDetector {
    private const val TAG = "HandTrigger"

    // How long the hand must be visible before confirmed (ms)
    private const val CONFIRM_SUSTAIN_MS = 500L

    // Cooldown after trigger fires (ms) — prevents re-triggering
    private const val TRIGGER_COOLDOWN_MS = 5000L

    // Minimum detection confidence (lower = more responsive)
    private const val MIN_CONFIDENCE = 0.3f

    // Model file in assets folder
    private const val MODEL_PATH = "hand_landmarker.task"

    private var handLandmarker: HandLandmarker? = null
    private var isInitialized = false
    private var isRunning = false

    // ─── State tracking ─────────────────────────────────────────────
    // Timestamp when hand first appeared (0 = not currently visible)
    private var handVisibleSince = 0L

    // Whether the hand has been confirmed (visible for sustain duration)
    private var isConfirmed = false

    // Timestamp of last trigger (for cooldown)
    private var lastTriggerTime = 0L

    // Whether trigger has already fired for this hand appearance
    private var triggerFired = false

    // Callbacks
    var onHandConfirmed: (() -> Unit)? = null   // Hand confirmed (sustain passed)
    var onHandLeft: (() -> Unit)? = null         // Hand left frame → START TIMER
    var onHandAppeared: (() -> Unit)? = null     // Hand just appeared

    // UI state
    var handState: HandState = HandState.NONE
        private set

    enum class HandState {
        NONE,               // No hand detected
        HAND_DETECTED,      // Hand visible, waiting for sustain
        CONFIRMED,          // Hand confirmed — waiting to leave frame to trigger
        TRIGGERED           // Hand left → timer started
    }

    /**
     * Initialize the MediaPipe Hand Landmarker.
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
            Log.i(TAG, "MediaPipe Hand Landmarker initialized (photobooth trigger mode)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Hand Landmarker: ${e.message}")
            false
        }
    }

    /**
     * Start hand detection.
     */
    fun start() {
        if (!isInitialized) {
            Log.w(TAG, "Cannot start — not initialized")
            return
        }
        isRunning = true
        resetState()
        handState = HandState.NONE
        Log.i(TAG, "Photobooth hand trigger detection started")
    }

    /**
     * Stop hand detection.
     */
    fun stop() {
        isRunning = false
        resetState()
        handState = HandState.NONE
        Log.i(TAG, "Hand trigger detection stopped")
    }

    private fun resetState() {
        handVisibleSince = 0L
        isConfirmed = false
        lastTriggerTime = 0L
        triggerFired = false
    }

    /**
     * Process a camera preview bitmap.
     *
     * PHOTOBOOTH TRIGGER LOGIC:
     *   1. Hand detected → track sustain time
     *   2. Hand sustained 500ms → CONFIRMED (indicator turns green)
     *   3. Hand leaves frame → TRIGGER (timer starts)
     *   4. Timer ALWAYS completes — person poses during countdown
     *   5. 5-second cooldown prevents re-triggering
     *
     * @param bitmap Camera preview frame
     * @return true if a hand was detected in this frame
     */
    fun processFrame(bitmap: Bitmap): Boolean {
        if (!isRunning || !isInitialized) return false

        try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result: HandLandmarkerResult? = handLandmarker?.detect(mpImage)
            if (result == null) return false

            val handDetected = result.landmarks().isNotEmpty()
            val now = System.currentTimeMillis()

            // ── Cooldown check ──
            if (lastTriggerTime > 0 && now - lastTriggerTime < TRIGGER_COOLDOWN_MS) {
                handState = HandState.TRIGGERED
                return handDetected
            }

            if (handDetected) {
                if (handVisibleSince == 0L) {
                    // Hand just appeared
                    handVisibleSince = now
                    isConfirmed = false
                    triggerFired = false
                    handState = HandState.HAND_DETECTED
                    Log.d(TAG, "Hand appeared — waiting for sustain")
                    onHandAppeared?.invoke()
                } else if (!isConfirmed && now - handVisibleSince >= CONFIRM_SUSTAIN_MS) {
                    // Hand sustained long enough → confirmed
                    isConfirmed = true
                    handState = HandState.CONFIRMED
                    Log.i(TAG, "Hand confirmed ✓ — remove hand to trigger timer")
                    onHandConfirmed?.invoke()
                }
                // else: hand still visible, waiting for sustain or waiting to leave
            } else {
                // No hand in frame
                if (isConfirmed && !triggerFired) {
                    // Hand was confirmed and now left → TRIGGER!
                    triggerFired = true
                    lastTriggerTime = now
                    handState = HandState.TRIGGERED
                    Log.i(TAG, "Hand left frame → TIMER STARTED! (photobooth trigger)")
                    onHandLeft?.invoke()
                } else {
                    // Hand was not confirmed or already triggered — just reset
                    handState = HandState.NONE
                }
                handVisibleSince = 0L
                isConfirmed = false
            }

            return handDetected
        } catch (e: Exception) {
            return false
        }
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
        onHandLeft = null
        onHandAppeared = null
        Log.i(TAG, "Hand trigger detector disposed")
    }

    fun isDetecting(): Boolean = isRunning
}
