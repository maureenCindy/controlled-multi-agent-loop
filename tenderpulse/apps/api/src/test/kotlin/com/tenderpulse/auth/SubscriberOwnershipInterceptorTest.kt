package com.tenderpulse.auth

import io.mockk.every
import io.mockk.mockk
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.io.PrintWriter
import java.io.StringWriter
import java.util.UUID

/**
 * Unit tests for [SubscriberOwnershipInterceptor] (TP-038 AC: "a request can only act on the
 * subscriber tied to its authenticated token, not any UUID in the path").
 */
class SubscriberOwnershipInterceptorTest {

    private val interceptor = SubscriberOwnershipInterceptor()
    private val request = mockk<HttpServletRequest>()
    private val response = mockk<HttpServletResponse>(relaxed = true)
    private val handler = Any()

    @AfterEach
    fun clearContext() {
        SecurityContextHolder.clearContext()
    }

    private fun authenticateAs(subscriberId: UUID) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(subscriberId, null, emptyList())
    }

    @Test
    fun `allows a request whose token subscriber matches the path subscriber`() {
        val subscriberId = UUID.randomUUID()
        every { request.requestURI } returns "/api/v1/subscribers/$subscriberId/profiles"
        authenticateAs(subscriberId)

        assertTrue(interceptor.preHandle(request, response, handler))
    }

    @Test
    fun `allows a request against a specific profile id when the subscriber matches`() {
        val subscriberId = UUID.randomUUID()
        val profileId = UUID.randomUUID()
        every { request.requestURI } returns "/api/v1/subscribers/$subscriberId/profiles/$profileId"
        authenticateAs(subscriberId)

        assertTrue(interceptor.preHandle(request, response, handler))
    }

    @Test
    fun `rejects with 403 when the authenticated subscriber differs from the path subscriber`() {
        val pathSubscriberId = UUID.randomUUID()
        val tokenSubscriberId = UUID.randomUUID()
        every { request.requestURI } returns "/api/v1/subscribers/$pathSubscriberId/profiles"
        authenticateAs(tokenSubscriberId)

        val writer = StringWriter()
        every { response.writer } returns PrintWriter(writer)

        assertFalse(interceptor.preHandle(request, response, handler))
        io.mockk.verify { response.status = HttpServletResponse.SC_FORBIDDEN }
        assertTrue(writer.toString().contains("forbidden"))
    }

    @Test
    fun `rejects with 403 when there is no authenticated principal at all`() {
        val pathSubscriberId = UUID.randomUUID()
        every { request.requestURI } returns "/api/v1/subscribers/$pathSubscriberId/profiles"
        // No authentication set in the security context.

        val writer = StringWriter()
        every { response.writer } returns PrintWriter(writer)

        assertFalse(interceptor.preHandle(request, response, handler))
    }

    @Test
    fun `does not apply to paths outside the subscriber profiles namespace`() {
        every { request.requestURI } returns "/api/v1/tenders"
        // No authentication needed — this path isn't guarded at all.

        assertTrue(interceptor.preHandle(request, response, handler))
    }

    @Test
    fun `does not apply to the bare subscriber signup path`() {
        every { request.requestURI } returns "/api/v1/subscribers"

        assertTrue(interceptor.preHandle(request, response, handler))
    }
}
