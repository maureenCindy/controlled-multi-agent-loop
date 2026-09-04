package com.tenderpulse.auth

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Issues and validates the bearer access token returned by `GET /api/v1/auth/verify` (TP-038).
 *
 * Deliberately stateless (no DB lookup to validate a request): the token is
 * `base64url(subscriberId:expiryEpochSeconds) + "." + base64url(HMAC-SHA256(payload))`, so
 * [BearerTokenAuthFilter] can authenticate every request from the `Authorization` header alone.
 * This is unlike the magic-link token itself, which *is* stored (see [MagicLinkToken]) because
 * it must be revocable/single-use; the access token that comes out the other end of `verify`
 * does not need that — it just needs to expire (no refresh flow; out of scope for TP-038).
 */
@Component
class BearerTokenService(
    @Value("\${tenderpulse.auth.token-secret}") private val secret: String,
    @Value("\${tenderpulse.auth.access-token-ttl-ms:86400000}") private val accessTokenTtlMs: Long
) {
    private val algorithm = "HmacSHA256"

    fun issue(subscriberId: UUID, now: Instant = Instant.now()): String {
        val expiresAt = now.plusMillis(accessTokenTtlMs).epochSecond
        val payload = "$subscriberId:$expiresAt"
        val payloadB64 = encode(payload.toByteArray(Charsets.UTF_8))
        return "$payloadB64.${sign(payloadB64)}"
    }

    /** Returns the authenticated subscriber id, or null if the token is malformed, tampered, or expired. */
    fun parse(token: String, now: Instant = Instant.now()): UUID? {
        val parts = token.split(".")
        if (parts.size != 2) return null
        val (payloadB64, signature) = parts

        val expectedSignature = sign(payloadB64)
        if (!MessageDigest.isEqual(expectedSignature.toByteArray(Charsets.UTF_8), signature.toByteArray(Charsets.UTF_8))) {
            return null
        }

        val payload = runCatching { String(decode(payloadB64), Charsets.UTF_8) }.getOrNull() ?: return null
        val separatorIndex = payload.lastIndexOf(':')
        if (separatorIndex < 0) return null

        val subscriberId = runCatching { UUID.fromString(payload.substring(0, separatorIndex)) }.getOrNull() ?: return null
        val expiresAtEpochSeconds = payload.substring(separatorIndex + 1).toLongOrNull() ?: return null
        if (Instant.ofEpochSecond(expiresAtEpochSeconds).isBefore(now)) return null

        return subscriberId
    }

    private fun sign(payloadB64: String): String {
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), algorithm))
        return encode(mac.doFinal(payloadB64.toByteArray(Charsets.UTF_8)))
    }

    private fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
}
