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
import org.springframework.web.servlet.HandlerMapping
import java.io.PrintWriter
import java.io.StringWriter
import java.util.UUID

/**
 * Unit tests for [SubscriberOwnershipInterceptor] (TP-038 AC: "a request can only act on the
 * subscriber tied to its authenticated token, not any UUID in the path").
 *
 * Deliberately drives the interceptor via the same
 * [HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE] request attribute Spring's own handler
 * mapping populates — not [HttpServletRequest.getRequestURI] — because a prior version compared
 * against the raw URI directly and could be bypassed by percent-encoding a path segment (see
 * [SubscriberOwnershipInterceptor]'s class doc and [com.tenderpulse.api.AuthIntegrationTest]'s
 * `percent-encoded path segment` regression test for the full-stack proof).
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

    private fun stubPathVariables(vararg pairs: Pair<String, String>) {
        every { request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) } returns
            if (pairs.isEmpty()) null else mapOf(*pairs)
    }

    @Test
    fun `allows a request whose token subscriber matches the path subscriber`() {
        val subscriberId = UUID.randomUUID()
        stubPathVariables("id" to subscriberId.toString())
        authenticateAs(subscriberId)

        assertTrue(interceptor.preHandle(request, response, handler))
    }

    @Test
    fun `allows a request against a specific profile id when the subscriber matches`() {
        val subscriberId = UUID.randomUUID()
        val profileId = UUID.randomUUID()
        stubPathVariables("id" to subscriberId.toString(), "profileId" to profileId.toString())
        authenticateAs(subscriberId)

        assertTrue(interceptor.preHandle(request, response, handler))
    }

    @Test
    fun `rejects with 403 when the authenticated subscriber differs from the path subscriber`() {
        val pathSubscriberId = UUID.randomUUID()
        val tokenSubscriberId = UUID.randomUUID()
        stubPathVariables("id" to pathSubscriberId.toString())
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
        stubPathVariables("id" to pathSubscriberId.toString())
        // No authentication set in the security context.

        val writer = StringWriter()
        every { response.writer } returns PrintWriter(writer)

        assertFalse(interceptor.preHandle(request, response, handler))
    }

    @Test
    fun `does not apply when there is no id path variable at all`() {
        stubPathVariables()
        // No authentication needed — this path isn't guarded at all.

        assertTrue(interceptor.preHandle(request, response, handler))
    }

    @Test
    fun `does not apply when the path variables attribute itself is absent`() {
        every { request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) } returns null

        assertTrue(interceptor.preHandle(request, response, handler))
    }

    @Test
    fun `a malformed id path variable is let through rather than guarded`() {
        // Not a valid UUID — Spring's own @PathVariable UUID conversion will 400 this downstream;
        // this interceptor isn't the place to duplicate that validation.
        stubPathVariables("id" to "not-a-uuid")

        assertTrue(interceptor.preHandle(request, response, handler))
    }
}
