package com.saatiril.full.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Utility for saving captured photos to Android storage.
 *
 * Handles:
 * - Android 10+ (API 29+): MediaStore API — saves to Pictures/Saatiril/{folderName}/
 *   and photos appear in Gallery app immediately
 * - Legacy external storage for Android 9 and below
 * - Folder name extracted from admin's targetFolder (Windows path like "D:\Wisuda 2024")
 * - Fallback to project name if targetFolder is empty
 * - Extensive logging for debugging
 */
object PhotoSaver {
    private const val TAG = "PhotoSaver"

    /**
     * Save a base64-encoded photo to Android storage.
     *
     * Strategy:
     * 1. Android 10+ (API 29+): Use MediaStore API to save to public Pictures/Saatiril/{folderName}/
     *    - Photos are immediately visible in Gallery app
     *    - No WRITE_EXTERNAL_STORAGE permission needed
     *    - Folder name comes from admin's targetFolder (last segment of Windows path)
     * 2. Legacy public directory for Android 9 and below
     * 3. Internal app storage as last resort (not user-visible)
     *
     * @param context Application context for file access
     * @param base64Data Full data URI: "data:image/jpeg;base64,..."
     * @param filename Target filename: "NIM_Nama_1_Toga.jpg"
     * @param projectName Project name for subfolder (fallback)
     * @param targetFolder Admin-designated folder path from Windows (e.g., "D:\Wisuda 2024")
     * @return Saved file display name on success, null on failure
     */
    fun savePhoto(
        context: Context,
        base64Data: String,
        filename: String,
        projectName: String,
        targetFolder: String = ""
    ): String? {
        Log.d(TAG, "savePhoto() called: filename=$filename, projectName=$projectName, targetFolder=$targetFolder")

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

        // Determine folder name from targetFolder or projectName
        val folderName = extractFolderName(targetFolder, projectName)

        // Strategy 1: MediaStore API (Android 10+ / API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val result = saveToMediaStore(context, bytes, filename, folderName)
            if (result != null) {
                Log.i(TAG, "Photo saved (MediaStore): $result in folder Saatiril/$folderName")
                return result
            }
        }

        // Strategy 2: Legacy public external storage (Android 9 and below)
        val path2 = saveToLegacyExternal(bytes, filename, folderName)
        if (path2 != null) {
            Log.i(TAG, "Photo saved (legacy external): $path2")
            return path2
        }

        // Strategy 3: Internal app storage (always works, but not user-visible)
        val path3 = saveToInternalStorage(context, bytes, filename, folderName)
        if (path3 != null) {
            Log.i(TAG, "Photo saved (internal storage): $path3")
            return path3
        }

        Log.e(TAG, "ALL save strategies failed for $filename!")
        return null
    }

    /**
     * Extract folder name from the admin's targetFolder path.
     * Windows paths like "D:\Wisuda 2024" → "Wisuda 2024"
     * Falls back to project name if targetFolder is empty or unparsable.
     */
    private fun extractFolderName(targetFolder: String, projectName: String): String {
        if (targetFolder.isBlank()) return sanitizeFolderName(projectName.ifBlank { "Saatiril" })
        // Extract last segment of Windows path: "D:\Wisuda 2024" → "Wisuda 2024"
        val folderName = targetFolder
            .replace('\\', '/')
            .trimEnd('/')
            .substringAfterLast('/')
            .trim()
        return if (folderName.isNotBlank()) sanitizeFolderName(folderName) else sanitizeFolderName(projectName.ifBlank { "Saatiril" })
    }

    /**
     * Strategy 1: Save via MediaStore API (Android 10+ / API 29+).
     * Saves to Pictures/Saatiril/{folderName}/ which is publicly visible
     * and appears in the Gallery app.
     * No WRITE_EXTERNAL_STORAGE permission needed.
     */
    private fun saveToMediaStore(
        context: Context,
        bytes: ByteArray,
        filename: String,
        folderName: String
    ): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null

        return try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Saatiril/$folderName")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: run {
                    Log.w(TAG, "MediaStore: Failed to create content URI for $filename")
                    return null
                }

            try {
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: run {
                        Log.w(TAG, "MediaStore: Failed to open output stream for $uri")
                        resolver.delete(uri, null, null)
                        return null
                    }

                // Mark as not pending — makes the file visible to Gallery
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)

                Log.i(TAG, "MediaStore: Saved $filename to Pictures/Saatiril/$folderName/ (${bytes.size} bytes)")
                filename // Return filename as success indicator
            } catch (e: Exception) {
                Log.w(TAG, "MediaStore: Failed to write $filename — ${e.message}")
                try { resolver.delete(uri, null, null) } catch (_: Exception) {}
                null
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "MediaStore: SecurityException — ${e.message}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore: Unexpected error — ${e.message}")
            null
        }
    }

    /**
     * Strategy 2: Save to legacy public external storage.
     * Only works on Android 9 (API 28) and below with WRITE_EXTERNAL_STORAGE,
     * or on Android 10 with requestLegacyExternalStorage.
     * Path: /storage/emulated/0/Pictures/Saatiril/{folderName}/
     */
    private fun saveToLegacyExternal(
        bytes: ByteArray,
        filename: String,
        folderName: String
    ): String? {
        return try {
            // Only attempt on Android 9 and below
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Log.d(TAG, "Skipping legacy external storage — Android 10+ uses MediaStore")
                return null
            }

            val picturesDir = Environment.getExternalStorageDirectory()
            val targetDir = File(picturesDir, "Pictures/Saatiril/$folderName")
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
     * Path: /data/data/com.saatiril.full/files/Saatiril/{folderName}/
     */
    private fun saveToInternalStorage(
        context: Context,
        bytes: ByteArray,
        filename: String,
        folderName: String
    ): String? {
        return try {
            val targetDir = File(context.filesDir, "Saatiril/$folderName")
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
    fun getSaveDirectoryPath(context: Context, projectName: String, targetFolder: String = ""): String {
        val folderName = extractFolderName(targetFolder, projectName)

        // Android 10+: Public Pictures directory via MediaStore
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return "Pictures/Saatiril/$folderName"
        }

        // Legacy: Full path
        val picturesDir = Environment.getExternalStorageDirectory()
        return File(picturesDir, "Pictures/Saatiril/$folderName").absolutePath
    }

    /**
     * Sanitize a folder name for use as a directory name.
     * Preserves spaces (valid on Android/MediaStore), removes only truly invalid characters.
     */
    private fun sanitizeFolderName(name: String): String {
        if (name.isBlank()) return "Default"
        return name.trim()
            .replace("[<>:\"|?*]".toRegex(), "") // Remove chars invalid on most filesystems
            .replace("\\s+".toRegex(), " ")       // Collapse multiple spaces to one
            .trim()
            .take(100) // Allow longer names for folder names like "Wisuda 2024"
    }
}
