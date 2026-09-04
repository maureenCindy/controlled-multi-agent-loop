package com.tenderpulse.auth

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

/**
 * Unit tests for [BearerTokenAuthFilter] (TP-038). [BearerTokenService] is mocked so this only
 * exercises the filter's own responsibility: reading the header and populating (or not
 * populating) the [org.springframework.security.core.context.SecurityContext]. Uses Spring's
 * `Mock*` servlet fakes (not a hand-rolled mockk stub of the Servlet API) so
 * [org.springframework.web.filter.OncePerRequestFilter]'s own bookkeeping (already-filtered
 * request attribute, dispatcher type, etc.) behaves exactly as it would in a real request.
 */
class BearerTokenAuthFilterTest {

    private val bearerTokenService = mockk<BearerTokenService>()
    private val filter = BearerTokenAuthFilter(bearerTokenService)

    @AfterEach
    fun clearContext() {
        SecurityContextHolder.clearContext()
    }

    private fun runFilter(authorizationHeader: String?): MockFilterChain {
        val request = MockHttpServletRequest()
        if (authorizationHeader != null) {
            request.addHeader("Authorization", authorizationHeader)
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)
        return chain
    }

    @Test
    fun `a valid bearer token sets the subscriber id as the authenticated principal`() {
        val subscriberId = UUID.randomUUID()
        // parse has a defaulted `now` param evaluated fresh at call time — match with any().
        every { bearerTokenService.parse("valid-token", any()) } returns subscriberId

        val chain = runFilter("Bearer valid-token")

        assertEquals(subscriberId, SecurityContextHolder.getContext().authentication?.principal)
        assertTrue(chain.request != null) // the chain was invoked (request continued)
    }

    @Test
    fun `an invalid bearer token leaves the security context empty`() {
        every { bearerTokenService.parse("garbage", any()) } returns null

        val chain = runFilter("Bearer garbage")

        assertNull(SecurityContextHolder.getContext().authentication)
        assertTrue(chain.request != null)
    }

    @Test
    fun `a missing Authorization header leaves the security context empty and still continues the chain`() {
        val chain = runFilter(null)

        assertNull(SecurityContextHolder.getContext().authentication)
        assertTrue(chain.request != null)
    }

    @Test
    fun `a non-Bearer Authorization header is ignored`() {
        val chain = runFilter("Basic dXNlcjpwYXNz")

        assertNull(SecurityContextHolder.getContext().authentication)
        assertTrue(chain.request != null)
    }
}
