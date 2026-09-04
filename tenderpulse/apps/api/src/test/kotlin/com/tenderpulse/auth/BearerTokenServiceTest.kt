package com.tenderpulse.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Unit tests for [BearerTokenService] (TP-038): the stateless bearer access token issued by
 * `GET /api/v1/auth/verify`. No Spring context — the secret/ttl are just constructor args.
 */
class BearerTokenServiceTest {

    private val service = BearerTokenService(secret = "unit-test-secret-at-least-32-bytes-long!!", accessTokenTtlMs = 60_000)

    @Test
    fun `issued token parses back to the same subscriber id`() {
        val subscriberId = UUID.randomUUID()
        val token = service.issue(subscriberId)

        assertEquals(subscriberId, service.parse(token))
    }

    @Test
    fun `token is rejected once past its expiry`() {
        val subscriberId = UUID.randomUUID()
        val issuedAt = Instant.parse("2026-01-01T00:00:00Z")
        val token = service.issue(subscriberId, now = issuedAt)

        val justBeforeExpiry = issuedAt.plusMillis(59_000)
        val justAfterExpiry = issuedAt.plusMillis(61_000)

        assertEquals(subscriberId, service.parse(token, now = justBeforeExpiry))
        assertNull(service.parse(token, now = justAfterExpiry))
    }

    @Test
    fun `tampered payload is rejected`() {
        val token = service.issue(UUID.randomUUID())
        val (payload, signature) = token.split(".")
        val otherSubscriberId = UUID.randomUUID()
        val forgedPayloadB64 = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("$otherSubscriberId:9999999999".toByteArray())
        val forged = "$forgedPayloadB64.$signature"

        assertNull(service.parse(forged))
        // sanity: original still valid
        assertEquals(payload, token.split(".")[0])
    }

    @Test
    fun `tampered signature is rejected`() {
        val token = service.issue(UUID.randomUUID())
        val (payload, _) = token.split(".")
        val forged = "$payload.not-the-real-signature"

        assertNull(service.parse(forged))
    }

    @Test
    fun `malformed token is rejected`() {
        assertNull(service.parse("not-a-valid-token"))
        assertNull(service.parse(""))
        assertNull(service.parse("a.b.c"))
    }

    @Test
    fun `a token signed with a different secret is rejected`() {
        val otherService = BearerTokenService(secret = "a-completely-different-secret-value!!!!", accessTokenTtlMs = 60_000)
        val token = otherService.issue(UUID.randomUUID())

        assertNull(service.parse(token))
    }
}
