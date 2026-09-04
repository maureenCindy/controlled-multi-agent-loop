package com.tenderpulse.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * SHA-256 hashing for magic-link tokens: only the hash is ever persisted (see [MagicLinkToken]),
 * so a database read can't be replayed as a valid login token.
 */
object TokenHasher {
    fun hash(rawToken: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(rawToken.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/** Generates the random, URL-safe raw token that goes out in the magic-link email. */
object RawTokenGenerator {
    private val secureRandom = SecureRandom()

    fun generate(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
