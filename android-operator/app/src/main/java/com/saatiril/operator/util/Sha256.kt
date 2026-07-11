package com.saatiril.operator.util

import java.security.MessageDigest

/**
 * Cryptographic utilities for Saatiril.
 * Provides SHA-256 hashing for session password verification.
 */
object CryptoUtils {
    fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
