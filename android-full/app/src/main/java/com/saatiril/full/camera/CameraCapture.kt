package com.saatiril.full.camera

import android.graphics.*
import android.util.Log
import com.saatiril.full.data.CameraModes
import com.saatiril.full.data.ProjectConfig
import java.io.ByteArrayOutputStream

/**
 * Handles photo capture processing:
 * - Center-crop to aspect ratio
 * - Apply filter presets (ColorMatrix)
 * - Overlay frame
 * - Convert to base64
 */
object CameraCapture {
    
    private const val TAG = "CameraCapture"
    private const val TARGET_WIDTH = 1920
    
    /**
     * Process a camera frame into a final photo:
     * 1. Center-crop to the project's aspect ratio
     * 2. Apply the configured filter preset
     * 3. Overlay the frame (if any)
     */
    fun processFrame(
        sourceBitmap: Bitmap,
        config: ProjectConfig,
        frameBitmap: Bitmap? = null
    ): Bitmap {
        val aspectRatio = config.parseAspectRatio()
        val targetWidth = TARGET_WIDTH
        val targetHeight = (targetWidth / aspectRatio).toInt()
        
        // Create output bitmap
        val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        
        // Fill with black
        canvas.drawColor(Color.BLACK)
        
        // Center-crop the source
        val srcWidth = sourceBitmap.width
        val srcHeight = sourceBitmap.height
        val srcRatio = srcWidth.toFloat() / srcHeight.toFloat()
        
        val srcRect = if (srcRatio > aspectRatio) {
            // Source is wider — crop sides
            val cropWidth = (srcHeight * aspectRatio).toInt()
            val left = (srcWidth - cropWidth) / 2
            Rect(left, 0, left + cropWidth, srcHeight)
        } else {
            // Source is taller — crop top/bottom
            val cropHeight = (srcWidth / aspectRatio).toInt()
            val top = (srcHeight - cropHeight) / 2
            Rect(0, top, srcWidth, top + cropHeight)
        }
        
        val dstRect = Rect(0, 0, targetWidth, targetHeight)
        
        // Apply filter preset
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        applyFilterPreset(paint, config.preset)
        
        // Draw camera frame
        canvas.drawBitmap(sourceBitmap, srcRect, dstRect, paint)
        
        // Draw frame overlay
        if (frameBitmap != null) {
            canvas.drawBitmap(frameBitmap, null, dstRect, null)
        }
        
        return output
    }
    
    /**
     * Convert bitmap to base64 JPEG data URI
     */
    fun bitmapToBase64(bitmap: Bitmap, quality: Int = 95): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        val bytes = outputStream.toByteArray()
        val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        return "data:image/jpeg;base64,$base64"
    }
    
    /**
     * Apply CSS filter equivalent via ColorMatrix
     */
    private fun applyFilterPreset(paint: Paint, preset: String) {
        val colorMatrix = when (preset) {
            "studio" -> ColorMatrix(floatArrayOf(
                1.1f, 0f, 0f, 0f, 0f,      // R: brightness 1.1
                0f, 1.1f, 0f, 0f, 0f,       // G: brightness 1.1
                0f, 0f, 1.1f, 0f, 0f,       // B: brightness 1.1
                0f, 0f, 0f, 1.05f, 0f,      // A: contrast 1.05
                0f, 0f, 0f, 0f, 1f          // offset
            )).also { 
                // Saturation 1.1 via setSaturation
                it.setSaturation(1.1f)
            }
            
            "cinematic" -> ColorMatrix().apply {
                setSaturation(1.3f)
                val scale = floatArrayOf(
                    0.95f, 0f, 0f, 0f, 0f,    // brightness 0.95
                    0f, 0.95f, 0f, 0f, 0f,
                    0f, 0f, 0.95f, 0f, 0f,
                    0f, 0f, 0f, 1.1f, 0f      // contrast 1.1
                )
                val scaleMatrix = ColorMatrix(scale)
                preConcat(scaleMatrix)
                // Sepia overlay
                val sepia = ColorMatrix(floatArrayOf(
                    0.393f, 0.769f, 0.189f, 0f, 0f,
                    0.349f, 0.686f, 0.168f, 0f, 0f,
                    0.272f, 0.534f, 0.131f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                ))
                // Blend 15% sepia
                val blend = ColorMatrix()
                blend.set(floatArrayOf(
                    1f - 0.15f * (1f - 0.393f), 0.15f * 0.769f, 0.15f * 0.189f, 0f, 0f,
                    0.15f * 0.349f, 1f - 0.15f * (1f - 0.686f), 0.15f * 0.168f, 0f, 0f,
                    0.15f * 0.272f, 0.15f * 0.534f, 1f - 0.15f * (1f - 0.131f), 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                ))
                preConcat(blend)
            }
            
            "pro" -> ColorMatrix(floatArrayOf(
                1.05f, 0f, 0f, 0f, 0f,      // brightness 1.05
                0f, 1.05f, 0f, 0f, 0f,
                0f, 0f, 1.05f, 0f, 0f,
                0f, 0f, 0f, 1.25f, 0f       // contrast 1.25
            )).also { it.setSaturation(1.15f) }
            
            "vivid" -> ColorMatrix(floatArrayOf(
                1.08f, 0f, 0f, 0f, 0f,      // brightness 1.08
                0f, 1.08f, 0f, 0f, 0f,
                0f, 0f, 1.08f, 0f, 0f,
                0f, 0f, 0f, 1.12f, 0f       // contrast 1.12
            )).also { it.setSaturation(1.45f) }
            
            "softPortrait" -> ColorMatrix(floatArrayOf(
                1.12f, 0f, 0f, 0f, 0f,      // brightness 1.12
                0f, 1.12f, 0f, 0f, 0f,
                0f, 0f, 1.12f, 0f, 0f,
                0f, 0f, 0f, 0.92f, 0f       // contrast 0.92
            )).also { it.setSaturation(1.08f) }
            
            "classicFilm" -> ColorMatrix(floatArrayOf(
                1.02f, 0f, 0f, 0f, 0f,
                0f, 1.02f, 0f, 0f, 0f,
                0f, 0f, 1.02f, 0f, 0f,
                0f, 0f, 0f, 1.15f, 0f
            )).also { it.setSaturation(0.85f) }
            
            "dramaticBW" -> ColorMatrix().apply {
                setSaturation(0f) // Grayscale
                val contrast = ColorMatrix(floatArrayOf(
                    1.05f, 0f, 0f, 0f, 0f,
                    0f, 1.05f, 0f, 0f, 0f,
                    0f, 0f, 1.05f, 0f, 0f,
                    0f, 0f, 0f, 1.35f, 0f
                ))
                postConcat(contrast)
            }
            
            "warmSunset" -> ColorMatrix(floatArrayOf(
                1.06f, 0f, 0f, 0f, 0f,
                0f, 1.06f, 0f, 0f, 0f,
                0f, 0f, 1.06f, 0f, 0f,
                0f, 0f, 0f, 1.08f, 0f
            )).also { it.setSaturation(1.3f) }
            
            else -> null // "original" — no filter
        }
        
        if (colorMatrix != null) {
            paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        }
    }
}
