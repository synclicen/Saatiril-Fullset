package com.saatiril.operator.util

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Utility for saving captured photos to Android storage.
 *
 * Handles:
 * - Android 10+ scoped storage (API 29+) using app-specific external directory
 * - Legacy external storage for older Android versions
 * - Project name as subfolder: Pictures/Saatiril/{projectName}/
 * - Fallback paths if primary path is not writable
 * - Extensive logging for debugging
 */
object PhotoSaver {
    private const val TAG = "PhotoSaver"

    /**
     * Save a base64-encoded photo to Android storage.
     *
     * Strategy (matches Windows behavior but adapted for Android):
     * 1. Try app-specific external Pictures directory: {externalFilesDir/Pictures}/Saatiril/{projectName}/
     *    - Works on Android 10+ with scoped storage
     *    - No WRITE_EXTERNAL_STORAGE needed
     * 2. Try legacy public directory: Pictures/Saatiril/{projectName}/
     *    - Works on Android 9 and below with WRITE_EXTERNAL_STORAGE
     * 3. Try internal app storage as last resort
     *
     * @param context Application context for file access
     * @param base64Data Full data URI: "data:image/jpeg;base64,..."
     * @param filename Target filename: "NIM_Nama_1_Toga.jpg"
     * @param projectName Project name for subfolder
     * @return Saved file absolute path on success, null on failure
     */
    fun savePhoto(
        context: Context,
        base64Data: String,
        filename: String,
        projectName: String
    ): String? {
        Log.d(TAG, "savePhoto() called: filename=$filename, projectName=$projectName")

        // Decode base64 data once
        val pureBase64 = if (base64Data.contains(",")) base64Data.substringAfter(",") else base64Data
        val bytes: ByteArray = try {
            Base64.decode(pureBase64, Base64.DEFAULT)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode base64 data: ${e.message}")
            return null
        }

        if (bytes.isEmpty()) {
            Log.e(TAG, "Decoded bytes are empty for filename=$filename")
            return null
        }

        Log.d(TAG, "Decoded ${bytes.size} bytes for $filename")

        // Sanitize project name for use as folder name
        val safeProjectName = sanitizeFolderName(projectName)

        // Strategy 1: App-specific external storage (works on Android 10+)
        val path1 = saveToAppSpecificExternal(context, bytes, filename, safeProjectName)
        if (path1 != null) {
            Log.i(TAG, "Photo saved (app-specific external): $path1")
            return path1
        }

        // Strategy 2: Legacy public external storage (Android 9 and below)
        val path2 = saveToLegacyExternal(bytes, filename, safeProjectName)
        if (path2 != null) {
            Log.i(TAG, "Photo saved (legacy external): $path2")
            return path2
        }

        // Strategy 3: Internal app storage (always works, but not user-visible)
        val path3 = saveToInternalStorage(context, bytes, filename, safeProjectName)
        if (path3 != null) {
            Log.i(TAG, "Photo saved (internal storage): $path3")
            return path3
        }

        Log.e(TAG, "ALL save strategies failed for $filename!")
        return null
    }

    /**
     * Strategy 1: Save to app-specific external files directory.
     * On Android 10+ (API 29+), this directory doesn't require WRITE_EXTERNAL_STORAGE.
     * Path: /storage/emulated/0/Android/data/com.saatiril.operator/files/Pictures/Saatiril/{projectName}/
     *
     * Note: On Android 11+ (API 30+), this directory is accessible via Files app
     * under "Android/data/com.saatiril.operator/files/Pictures/"
     */
    private fun saveToAppSpecificExternal(
        context: Context,
        bytes: ByteArray,
        filename: String,
        safeProjectName: String
    ): String? {
        return try {
            val picturesDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            if (picturesDir == null) {
                Log.w(TAG, "getExternalFilesDir(PICTURES) returned null — external storage unavailable")
                return null
            }

            val targetDir = File(picturesDir, "Saatiril/$safeProjectName")
            Log.d(TAG, "Attempting app-specific external save to: ${targetDir.absolutePath}")

            if (!targetDir.exists() && !targetDir.mkdirs()) {
                Log.w(TAG, "Failed to create directory: ${targetDir.absolutePath}")
                return null
            }

            if (!targetDir.canWrite()) {
                Log.w(TAG, "Directory not writable: ${targetDir.absolutePath}")
                return null
            }

            val file = File(targetDir, filename)
            FileOutputStream(file).use { it.write(bytes) }

            // Verify write
            if (file.exists() && file.length() == bytes.size.toLong()) {
                Log.i(TAG, "Verified photo saved: ${file.absolutePath} (${bytes.size} bytes)")
                file.absolutePath
            } else {
                Log.w(TAG, "File verification failed: exists=${file.exists()}, expected=${bytes.size}, actual=${file.length()}")
                null
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException on app-specific external: ${e.message}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed app-specific external save: ${e.message}")
            null
        }
    }

    /**
     * Strategy 2: Save to legacy public external storage.
     * Only works on Android 9 (API 28) and below with WRITE_EXTERNAL_STORAGE,
     * or on Android 10 with requestLegacyExternalStorage.
     * Path: /storage/emulated/0/Pictures/Saatiril/{projectName}/
     */
    private fun saveToLegacyExternal(
        bytes: ByteArray,
        filename: String,
        safeProjectName: String
    ): String? {
        return try {
            // Only attempt on Android 9 and below, or if we have storage access
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Log.d(TAG, "Skipping legacy external storage — Android 10+ uses scoped storage")
                return null
            }

            val picturesDir = Environment.getExternalStorageDirectory()
            val targetDir = File(picturesDir, "Pictures/Saatiril/$safeProjectName")
            Log.d(TAG, "Attempting legacy external save to: ${targetDir.absolutePath}")

            if (!targetDir.exists() && !targetDir.mkdirs()) {
                Log.w(TAG, "Failed to create legacy directory: ${targetDir.absolutePath}")
                return null
            }

            if (!targetDir.canWrite()) {
                Log.w(TAG, "Legacy directory not writable: ${targetDir.absolutePath}")
                return null
            }

            val file = File(targetDir, filename)
            FileOutputStream(file).use { it.write(bytes) }

            if (file.exists() && file.length() == bytes.size.toLong()) {
                Log.i(TAG, "Verified photo saved (legacy): ${file.absolutePath} (${bytes.size} bytes)")
                file.absolutePath
            } else {
                Log.w(TAG, "Legacy file verification failed")
                null
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException on legacy external: ${e.message}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed legacy external save: ${e.message}")
            null
        }
    }

    /**
     * Strategy 3: Save to internal app storage.
     * This always works but files are not visible to users through the file manager.
     * Path: /data/data/com.saatiril.operator/files/Saatiril/{projectName}/
     */
    private fun saveToInternalStorage(
        context: Context,
        bytes: ByteArray,
        filename: String,
        safeProjectName: String
    ): String? {
        return try {
            val targetDir = File(context.filesDir, "Saatiril/$safeProjectName")
            Log.d(TAG, "Attempting internal storage save to: ${targetDir.absolutePath}")

            if (!targetDir.exists() && !targetDir.mkdirs()) {
                Log.w(TAG, "Failed to create internal directory: ${targetDir.absolutePath}")
                return null
            }

            val file = File(targetDir, filename)
            FileOutputStream(file).use { it.write(bytes) }

            if (file.exists() && file.length() == bytes.size.toLong()) {
                Log.i(TAG, "Verified photo saved (internal): ${file.absolutePath} (${bytes.size} bytes)")
                file.absolutePath
            } else {
                Log.w(TAG, "Internal file verification failed")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed internal storage save (last resort): ${e.message}")
            null
        }
    }

    /**
     * Get the primary save directory path for display purposes.
     * Returns the path where photos will be saved (may not exist yet).
     */
    fun getSaveDirectoryPath(context: Context, projectName: String): String {
        val safeProjectName = sanitizeFolderName(projectName)

        // Prefer app-specific external
        val picturesDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        if (picturesDir != null) {
            return File(picturesDir, "Saatiril/$safeProjectName").absolutePath
        }

        // Fallback to internal
        return File(context.filesDir, "Saatiril/$safeProjectName").absolutePath
    }

    /**
     * Sanitize a project name for use as a folder name.
     * Replaces spaces with underscores, removes special characters.
     */
    private fun sanitizeFolderName(name: String): String {
        if (name.isBlank()) return "Default"
        return name.trim()
            .replace("\\s+".toRegex(), "_")
            .replace("[^a-zA-Z0-9_\\-]".toRegex(), "")
            .take(50) // Limit length
    }
}
