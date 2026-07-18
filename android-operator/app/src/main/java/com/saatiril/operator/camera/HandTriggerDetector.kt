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
 * Hand Trigger Detector — "Trigger Waving"
 *
 * Uses MediaPipe Hand Landmarker to detect a WAVING hand gesture
 * (hand moving back and forth horizontally) and trigger the camera shutter.
 *
 * PHOTOBOOTH BEHAVIOR:
 * - Waving hand detected → starts timer (or direct capture)
 * - Timer ALWAYS completes — removing hand does NOT cancel it
 * - The person being photographed can stop waving and pose during countdown
 *
 * WAVING DETECTION ALGORITHM:
 * - Track wrist (landmark 0) X position across frames
 * - Detect direction changes (left→right or right→left)
 * - Minimum amplitude per direction: 6% of frame width
 * - 2+ direction changes within 2 seconds = waving confirmed
 * - After confirmation, 5-second cooldown prevents re-triggering
 */
object HandTriggerDetector {
    private const val TAG = "HandTrigger"

    // ─── Waving Detection Parameters ─────────────────────────────────
    // Minimum horizontal displacement to count as a direction change (6% of frame)
    private const val MIN_WAVE_AMPLITUDE = 0.06f

    // Number of direction changes needed to confirm a wave (2 = one full back-and-forth)
    private const val MIN_DIRECTION_CHANGES = 2

    // Time window for direction changes to occur (ms)
    private const val WAVE_WINDOW_MS = 2000L

    // Cooldown after a confirmation before another can fire (ms)
    private const val CONFIRM_COOLDOWN_MS = 5000L

    // How often to sample frames for wave tracking (ms between samples)
    private const val SAMPLE_INTERVAL_MS = 80L

    // Minimum detection confidence
    private const val MIN_CONFIDENCE = 0.4f

    // Model file in assets folder
    private const val MODEL_PATH = "hand_landmarker.task"

    // Maximum position history entries
    private const val MAX_POSITION_HISTORY = 30

    private var handLandmarker: HandLandmarker? = null
    private var isInitialized = false
    private var isRunning = false

    // ─── Wave Tracking State ─────────────────────────────────────────
    private data class PositionSample(
        val x: Float,
        val timestamp: Long
    )

    // Ring buffer of recent wrist positions
    private val positionHistory = mutableListOf<PositionSample>()

    // Last confirmed direction: +1 = moving right, -1 = moving left, 0 = unknown
    private var lastDirection: Int = 0

    // Number of direction changes detected in current wave attempt
    private var directionChangeCount: Int = 0

    // Timestamp of last direction change
    private var lastDirectionChangeTime: Long = 0

    // Last sampled wrist X position
    private var lastWristX: Float = -1f

    // Timestamp of last position sample
    private var lastSampleTime: Long = 0

    // Whether we've already fired onHandConfirmed for this wave
    private var isConfirmed: Boolean = false

    // Timestamp of last confirmation (for cooldown)
    private var lastConfirmTime: Long = 0

    // Callbacks
    var onHandConfirmed: (() -> Unit)? = null
    var onHandReleased: (() -> Unit)? = null

    // UI state
    var handState: HandState = HandState.NONE
        private set
    var fingersExtended: Int = 0
        private set

    enum class HandState {
        NONE,           // No hand detected
        HAND_VISIBLE,   // Hand visible, not yet waving
        WAVING,         // Waving detected (direction changes happening)
        CONFIRMED       // Wave confirmed → trigger shutter
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
            Log.i(TAG, "MediaPipe Hand Landmarker initialized (waving detection mode)")
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
        resetWaveState()
        handState = HandState.NONE
        fingersExtended = 0
        Log.i(TAG, "Waving hand trigger detection started")
    }

    /**
     * Stop hand detection.
     */
    fun stop() {
        isRunning = false
        resetWaveState()
        handState = HandState.NONE
        fingersExtended = 0
        Log.i(TAG, "Waving hand trigger detection stopped")
    }

    private fun resetWaveState() {
        positionHistory.clear()
        lastDirection = 0
        directionChangeCount = 0
        lastDirectionChangeTime = 0
        lastWristX = -1f
        lastSampleTime = 0
        isConfirmed = false
        lastConfirmTime = 0
    }

    /**
     * Process a camera preview bitmap for waving detection.
     *
     * PHOTOBOOTH BEHAVIOR:
     * Once waving is confirmed and triggers capture, the timer/capture
     * ALWAYS runs to completion. Removing hand does NOT cancel it.
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

            val landmarks = result.landmarks()
            val handDetected = landmarks.isNotEmpty()

            // Count extended fingers for UI indicator
            fingersExtended = if (handDetected) {
                countExtendedFingers(landmarks[0])
            } else {
                0
            }

            val now = System.currentTimeMillis()

            // ── Cooldown check ──
            if (lastConfirmTime > 0 && now - lastConfirmTime < CONFIRM_COOLDOWN_MS) {
                handState = HandState.NONE
                return handDetected
            }

            if (handDetected) {
                val wrist = landmarks[0][0] // First hand, wrist landmark
                val wristX = wrist.x()

                // ── Sample at controlled interval ──
                if (now - lastSampleTime >= SAMPLE_INTERVAL_MS) {
                    lastSampleTime = now

                    if (lastWristX >= 0f) {
                        // Calculate horizontal displacement
                        val deltaX = wristX - lastWristX

                        // Determine current movement direction
                        val currentDirection = when {
                            deltaX > MIN_WAVE_AMPLITUDE -> 1   // Moving right
                            deltaX < -MIN_WAVE_AMPLITUDE -> -1 // Moving left
                            else -> lastDirection               // No significant movement
                        }

                        // Check for direction change
                        if (currentDirection != 0 && lastDirection != 0 && currentDirection != lastDirection) {
                            directionChangeCount++
                            lastDirectionChangeTime = now

                            // Clean old samples outside window
                            positionHistory.removeAll { now - it.timestamp > WAVE_WINDOW_MS }

                            Log.d(TAG, "Wave: direction change #$directionChangeCount (deltaX=${String.format("%.3f", deltaX)}, dir=$currentDirection)")
                        }

                        if (currentDirection != 0) {
                            lastDirection = currentDirection
                        }
                    }

                    // Store position sample
                    positionHistory.add(PositionSample(wristX, now))
                    if (positionHistory.size > MAX_POSITION_HISTORY) {
                        positionHistory.removeAt(0)
                    }

                    lastWristX = wristX

                    // ── Check if waving is confirmed ──
                    if (!isConfirmed && directionChangeCount >= MIN_DIRECTION_CHANGES) {
                        // Verify at least one direction change is recent (within window)
                        val recentChanges = directionChangeCount
                        if (recentChanges >= MIN_DIRECTION_CHANGES && lastDirectionChangeTime > 0 && now - lastDirectionChangeTime < WAVE_WINDOW_MS) {
                            isConfirmed = true
                            lastConfirmTime = now
                            handState = HandState.CONFIRMED
                            Log.i(TAG, "Waving confirmed! ($directionChangeCount direction changes) — triggering shutter")
                            onHandConfirmed?.invoke()
                        }
                    }

                    // Update UI state
                    if (!isConfirmed) {
                        handState = if (directionChangeCount > 0) {
                            HandState.WAVING
                        } else {
                            HandState.HAND_VISIBLE
                        }
                    }
                }
            } else {
                // No hand in frame — reset wave tracking (but don't cancel timer!)
                if (isConfirmed) {
                    Log.i(TAG, "Hand left frame after wave confirm — timer continues (photobooth mode)")
                    onHandReleased?.invoke()
                }
                // Reset wave detection state for next attempt
                positionHistory.clear()
                lastDirection = 0
                directionChangeCount = 0
                lastDirectionChangeTime = 0
                lastWristX = -1f
                isConfirmed = false
                handState = HandState.NONE
            }

            return handDetected
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Count extended fingers from hand landmarks (for UI indicator only).
     * Returns 0–5. This is NOT used for triggering — waving triggers.
     */
    private fun countExtendedFingers(handLandmarks: List<NormalizedLandmark>): Int {
        if (handLandmarks.size < 21) return 0

        val tipIds = listOf(4, 8, 12, 16, 20)
        val pipIds = listOf(3, 6, 10, 14, 18)

        var fingersUp = 0

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

    fun isDetecting(): Boolean = isRunning
}
