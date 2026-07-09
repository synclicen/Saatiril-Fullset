package com.saatiril.operator.util

import java.security.MessageDigest

object CryptoUtils {
    fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}

object FilenameUtils {
    fun sanitizeNama(nama: String?): String {
        if (nama.isNullOrBlank()) return "Unknown"
        return nama.trim()
            .replace("\\s+".toRegex(), "_")
            .replace("[^a-zA-Z0-9_]".toRegex(), "")
    }
    
    fun sanitizeNim(nim: String?): String {
        if (nim.isNullOrBlank()) return "00000000"
        return nim.trim()
            .replace("[^a-zA-Z0-9\\-_]".toRegex(), "")
    }
    
    /**
     * Build filename for standard mode (2 photos: Toga + Ijazah)
     * e.g. "12345678_Ahmad_Fauzi_1_Toga.jpg"
     */
    fun buildStandardFilename(nim: String, nama: String, suffix: Int, type: String, version: Int): String {
        val base = "${sanitizeNim(nim)}_${sanitizeNama(nama)}_${suffix}_${type}"
        return if (version > 1) "${base}_v${version}.jpg" else "${base}.jpg"
    }
    
    /**
     * Build filename for photoshoot mode (1 photo)
     * e.g. "12345678_Ahmad_Fauzi.jpg"
     */
    fun buildPhotoshootFilename(nim: String, nama: String, channel: Int, version: Int): String {
        val base = "${sanitizeNim(nim)}_${sanitizeNama(nama)}"
        val withCh = if (channel > 1) "${base}_Ch${channel}" else base
        return if (version > 1) "${withCh}_v${version}.jpg" else "${withCh}.jpg"
    }
}
