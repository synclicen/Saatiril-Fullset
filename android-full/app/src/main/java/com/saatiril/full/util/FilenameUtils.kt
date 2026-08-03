package com.saatiril.full.util

/**
 * Filename building utilities for Saatiril photo captures.
 * Matches the Windows/Electron naming convention:
 *
 * Standard mode (single/dual):
 *   v1 → NIM_Nama_1_Toga.jpg + NIM_Nama_2_Ijazah.jpg
 *   v2 → NIM_Nama_1_Toga_v2.jpg + NIM_Nama_2_Ijazah_v2.jpg
 *
 * Photoshoot mode:
 *   v1 → NIM_Nama.jpg (channel 1) or NIM_Nama_Ch2.jpg (channel 2+)
 *   v2 → NIM_Nama_v2.jpg or NIM_Nama_Ch2_v2.jpg
 */
object FilenameUtils {

    /**
     * Sanitize nama (name) for use in filenames.
     * Replaces spaces with underscores, removes non-alphanumeric characters.
     */
    private fun sanitizeNama(nama: String?): String {
        return (nama ?: "").trim()
            .replace("\\s+".toRegex(), "_")
            .replace("[^a-zA-Z0-9_]".toRegex(), "")
    }

    /**
     * Sanitize NIM (student ID) for use in filenames.
     * Keeps alphanumeric, underscore, and hyphen characters.
     */
    private fun sanitizeNim(nim: String?): String {
        return (nim ?: "").toString().trim()
            .replace("[^a-zA-Z0-9_-]".toRegex(), "")
    }

    /**
     * Build a versioned filename for standard mode (Toga + Ijazah).
     *
     * @param nim Student NIM
     * @param nama Student name
     * @param suffix Photo number (1 = Toga, 2 = Ijazah)
     * @param type Photo type label ("Toga" or "Ijazah")
     * @param version Capture version (1 = first, 2+ = retake after MC reset)
     * @return Filename like "NIM_Nama_1_Toga.jpg" or "NIM_Nama_1_Toga_v2.jpg"
     */
    fun buildStandardFilename(
        nim: String?,
        nama: String?,
        suffix: Int,
        type: String,
        version: Int = 1
    ): String {
        val base = "${sanitizeNim(nim)}_${sanitizeNama(nama)}_${suffix}_${type}"
        return if (version > 1) "${base}_v${version}.jpg" else "${base}.jpg"
    }

    /**
     * Build a versioned filename for photoshoot mode.
     *
     * @param nim Student NIM
     * @param nama Student name
     * @param channel Camera channel (1 = no suffix, 2+ = Ch2, Ch3, etc.)
     * @param version Capture version (1 = first, 2+ = retake after MC reset)
     * @return Filename like "NIM_Nama.jpg" or "NIM_Nama_Ch2_v2.jpg"
     */
    fun buildPhotoshootFilename(
        nim: String?,
        nama: String?,
        channel: Int,
        version: Int = 1
    ): String {
        val base = "${sanitizeNim(nim)}_${sanitizeNama(nama)}"
        val withCh = if (channel > 1) "${base}_Ch${channel}" else base
        return if (version > 1) "${withCh}_v${version}.jpg" else "${withCh}.jpg"
    }
}
