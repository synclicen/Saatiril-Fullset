/**
 * SAATIRIL AI — Pose Detection Module
 * 
 * This module provides AI-powered moment detection for graduation ceremonies.
 * It detects "toga" (wearing graduation cap) and "ijazah" (holding diploma) moments
 * using TensorFlow.js + MoveNet pose detection model.
 * 
 * Usage: Loaded by use-ai-detection.ts hook when AI shutter mode is activated.
 * 
 * Requirements (must be loaded BEFORE this script):
 *   - /ai/tf.min.js       (TensorFlow.js core)
 *   - /ai/pose-detection.min.js (TensorFlow pose detection API)
 */

(function () {
  'use strict';

  // ─── Config ─────────────────────────────────────────────────────────────
  const DEFAULT_CONFIG = {
    sensitivity: 0.5,           // Detection confidence threshold (0-1)
    detectionInterval: 300,     // ms between detection frames
    detectionCooldown: 5000,    // ms cooldown between moment triggers
    sustainDuration: 1500,      // ms a moment must be sustained before triggering
  };

  let config = { ...DEFAULT_CONFIG };

  // ─── State ──────────────────────────────────────────────────────────────
  let isRunning = false;
  let isModelLoaded = false;
  let detector = null;
  let videoElement = null;
  let animFrameId = null;
  let lastDetectTime = 0;
  let lastTriggerTime = 0;

  // Sustain tracking
  let togaSustainStart = 0;
  let ijazahSustainStart = 0;
  let momentState = 'idle'; // 'idle' | 'toga_possible' | 'toga_sustained' | 'ijazah_possible' | 'ijazah_sustained'

  // Callbacks
  let onMomentDetected = null;
  let onStatusChange = null;

  // ─── Status reporting ───────────────────────────────────────────────────
  function reportStatus(extra = {}) {
    if (typeof onStatusChange === 'function') {
      onStatusChange({
        status: isRunning ? 'detecting' : (isModelLoaded ? 'model_ready' : 'unloaded'),
        isRunning,
        isModelLoaded,
        momentState,
        posesDetected: 0,
        detail: null,
        ...extra,
      });
    }
    // Dispatch custom event for React hook
    if (typeof window !== 'undefined') {
      window.dispatchEvent(new CustomEvent('saatiril-ai-status', {
        detail: {
          status: isRunning ? 'detecting' : (isModelLoaded ? 'model_ready' : 'unloaded'),
          isRunning,
          isModelLoaded,
          momentState,
          posesDetected: 0,
          detail: null,
          ...extra,
        },
      }));
    }
  }

  // ─── Moment detection logic ─────────────────────────────────────────────
  // Detect toga moment: person with raised arms (graduation cap toss pose)
  // Detect ijazah moment: person holding something in front (diploma pose)
  
  function detectMoment(poses) {
    const now = Date.now();

    if (!poses || poses.length === 0) {
      // No poses detected — reset sustain timers
      togaSustainStart = 0;
      ijazahSustainStart = 0;
      if (momentState !== 'idle') {
        momentState = 'idle';
        reportStatus();
      }
      return;
    }

    // Check cooldown
    if (now - lastTriggerTime < config.detectionCooldown) return;

    let togaDetected = false;
    let ijazahDetected = false;

    for (const pose of poses) {
      if (!pose.keypoints) continue;

      const keypoints = pose.keypoints;
      const confidence = pose.score || 0;

      if (confidence < config.sensitivity) continue;

      // Find key body parts
      const nose = keypoints.find(k => k.name === 'nose');
      const leftShoulder = keypoints.find(k => k.name === 'left_shoulder');
      const rightShoulder = keypoints.find(k => k.name === 'right_shoulder');
      const leftWrist = keypoints.find(k => k.name === 'left_wrist');
      const rightWrist = keypoints.find(k => k.name === 'right_wrist');
      const leftElbow = keypoints.find(k => k.name === 'left_elbow');
      const rightElbow = keypoints.find(k => k.name === 'right_elbow');
      const leftHip = keypoints.find(k => k.name === 'left_hip');
      const rightHip = keypoints.find(k => k.name === 'right_hip');

      if (!nose || !leftShoulder || !rightShoulder) continue;

      const minConfidence = config.sensitivity;

      // ── Toga detection: both wrists above shoulders (cap toss pose) ──
      const leftWristUp = leftWrist && leftWrist.score > minConfidence && leftWrist.y < leftShoulder.y - 30;
      const rightWristUp = rightWrist && rightWrist.score > minConfidence && rightWrist.y < rightShoulder.y - 30;
      const bothWristsUp = leftWristUp && rightWristUp;

      // ── Ijazah detection: both wrists in front of torso at chest level ──
      const shoulderMidX = (leftShoulder.x + rightShoulder.x) / 2;
      const shoulderMidY = (leftShoulder.y + rightShoulder.y) / 2;
      
      const leftWristFront = leftWrist && leftWrist.score > minConfidence &&
        leftWrist.y > shoulderMidY - 50 && leftWrist.y < shoulderMidY + 100 &&
        Math.abs(leftWrist.x - shoulderMidX) < 150;
      const rightWristFront = rightWrist && rightWrist.score > minConfidence &&
        rightWrist.y > shoulderMidY - 50 && rightWrist.y < shoulderMidY + 100 &&
        Math.abs(rightWrist.x - shoulderMidX) < 150;
      const bothWristsFront = leftWristFront && rightWristFront;

      if (bothWristsUp) togaDetected = true;
      if (bothWristsFront) ijazahDetected = true;
    }

    // ── Sustain logic ──────────────────────────────────────────────────────
    if (togaDetected) {
      if (togaSustainStart === 0) {
        togaSustainStart = now;
        momentState = 'toga_possible';
        reportStatus();
      } else if (now - togaSustainStart >= config.sustainDuration) {
        // Toga moment sustained!
        momentState = 'toga_sustained';
        reportStatus();
        triggerMoment('toga', poses.length);
        togaSustainStart = 0;
        ijazahSustainStart = 0;
        lastTriggerTime = now;
        setTimeout(() => { momentState = 'idle'; reportStatus(); }, 500);
        return;
      }
    } else {
      togaSustainStart = 0;
    }

    if (ijazahDetected) {
      if (ijazahSustainStart === 0) {
        ijazahSustainStart = now;
        momentState = 'ijazah_possible';
        reportStatus();
      } else if (now - ijazahSustainStart >= config.sustainDuration) {
        // Ijazah moment sustained!
        momentState = 'ijazah_sustained';
        reportStatus();
        triggerMoment('ijazah', poses.length);
        togaSustainStart = 0;
        ijazahSustainStart = 0;
        lastTriggerTime = now;
        setTimeout(() => { momentState = 'idle'; reportStatus(); }, 500);
        return;
      }
    } else {
      ijazahSustainStart = 0;
    }

    if (!togaDetected && !ijazahDetected && momentState !== 'idle') {
      momentState = 'idle';
      reportStatus();
    }
  }

  function triggerMoment(type, poses) {
    if (typeof onMomentDetected === 'function') {
      onMomentDetected(type, {
        timestamp: Date.now(),
        confidence: config.sensitivity,
        poses: poses,
      });
    }
  }

  // ─── Detection loop ─────────────────────────────────────────────────────
  async function detectFrame() {
    if (!isRunning || !detector || !videoElement) return;

    const now = Date.now();
    if (now - lastDetectTime < config.detectionInterval) {
      animFrameId = requestAnimationFrame(detectFrame);
      return;
    }
    lastDetectTime = now;

    try {
      if (videoElement.readyState >= 2) {
        const poses = await detector.estimatePoses(videoElement);
        detectMoment(poses);
      }
    } catch (err) {
      // Silently skip failed frames
    }

    if (isRunning) {
      animFrameId = requestAnimationFrame(detectFrame);
    }
  }

  // ─── Public API ─────────────────────────────────────────────────────────
  const SaatirilAI = {
    /**
     * Start pose detection on a video element.
     * @param {HTMLVideoElement} video - The video element to detect poses from
     * @param {Object} callbacks - { onMomentDetected, onStatusChange }
     */
    async start(video, callbacks = {}) {
      if (isRunning) return;

      videoElement = video;
      onMomentDetected = callbacks.onMomentDetected || null;
      onStatusChange = callbacks.onStatusChange || null;

      // Check dependencies
      if (typeof tf === 'undefined') {
        reportStatus({ status: 'error', detail: 'TensorFlow.js not loaded' });
        return;
      }
      if (typeof poseDetection === 'undefined') {
        reportStatus({ status: 'error', detail: 'Pose Detection API not loaded' });
        return;
      }

      try {
        // Load model if not already loaded
        if (!detector) {
          reportStatus({ status: 'loading_model', isRunning: false });
          
          // Use MoveNet SinglePose Lightning (fastest model)
          // modelUrl points to local files for offline support
          const model = poseDetection.SupportedModels.MoveNet;
          detector = await poseDetection.createDetector(model, {
            modelType: poseDetection.movenet.modelType.SINGLEPOSE_LIGHTNING,
            modelUrl: '/ai/tfjs/movenet/model.json',
          });
          isModelLoaded = true;
        }

        isRunning = true;
        momentState = 'idle';
        togaSustainStart = 0;
        ijazahSustainStart = 0;
        lastTriggerTime = 0;

        reportStatus();
        detectFrame();
      } catch (err) {
        console.error('[SaatirilAI] Failed to start detection:', err);
        reportStatus({ status: 'error', detail: err.message || 'Failed to start detection' });
      }
    },

    /** Stop pose detection */
    stop() {
      isRunning = false;
      if (animFrameId) {
        cancelAnimationFrame(animFrameId);
        animFrameId = null;
      }
      momentState = 'idle';
      togaSustainStart = 0;
      ijazahSustainStart = 0;
      reportStatus();
    },

    /** Dispose of the model and free memory */
    dispose() {
      SaatirilAI.stop();
      if (detector) {
        detector.dispose();
        detector = null;
      }
      isModelLoaded = false;
    },

    /** Get current config */
    getConfig() {
      return { ...config };
    },

    /** Update config */
    updateConfig(newConfig) {
      Object.assign(config, newConfig);
    },
  };

  // Expose to global scope
  if (typeof window !== 'undefined') {
    window.SaatirilAI = SaatirilAI;
  }
})();
