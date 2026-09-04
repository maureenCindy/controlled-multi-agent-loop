package com.tenderpulse.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.tenderpulse.auth.AuthService
import com.tenderpulse.auth.MagicLinkTokenAlreadyUsedException
import com.tenderpulse.auth.MagicLinkTokenExpiredException
import com.tenderpulse.auth.MagicLinkTokenNotFoundException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * Standalone MockMvc tests for [AuthController] (TP-038). [AuthService] is a mockk mock, so
 * these exercise only the controller's own responsibilities: request validation, delegation,
 * and mapping [com.tenderpulse.auth.InvalidMagicLinkTokenException] subtypes to 401. The
 * enumeration-safety and single-use/expiry business rules themselves are covered by
 * [com.tenderpulse.auth.AuthServiceTest].
 */
class AuthControllerTest {

    private val authService = mockk<AuthService>(relaxed = true)
    private val objectMapper: ObjectMapper = ObjectMapper().registerKotlinModule()
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        val controller = AuthController(authService)
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setMessageConverters(MappingJackson2HttpMessageConverter(objectMapper))
            .build()
    }

    // ---- POST /magic-link ----

    @Test
    fun `magic-link request for an existing email returns the generic success response`() {
        mockMvc.perform(
            post("/api/v1/auth/magic-link")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"sub@example.com"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.message").exists())

        verify(exactly = 1) { authService.requestMagicLink("sub@example.com") }
    }

    @Test
    fun `magic-link request for a non-existent email returns the identical success response`() {
        mockMvc.perform(
            post("/api/v1/auth/magic-link")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"nobody@example.com"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.message").exists())

        verify(exactly = 1) { authService.requestMagicLink("nobody@example.com") }
    }

    @Test
    fun `magic-link request with an invalid email returns 400`() {
        mockMvc.perform(
            post("/api/v1/auth/magic-link")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"not-an-email"}""")
        ).andExpect(status().isBadRequest)

        verify(exactly = 0) { authService.requestMagicLink(any()) }
    }

    // ---- GET /verify ----

    @Test
    fun `verify with a valid token returns a bearer access token`() {
        every { authService.verify("good-token") } returns "issued-bearer-token"

        mockMvc.perform(get("/api/v1/auth/verify").param("token", "good-token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accessToken").value("issued-bearer-token"))
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
    }

    @Test
    fun `verify with an expired token returns 401 with a clear reason`() {
        every { authService.verify("expired-token") } throws MagicLinkTokenExpiredException()

        mockMvc.perform(get("/api/v1/auth/verify").param("token", "expired-token"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("expired"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("expired")))
    }

    @Test
    fun `verify with an already-used token returns 401`() {
        every { authService.verify("used-token") } throws MagicLinkTokenAlreadyUsedException()

        mockMvc.perform(get("/api/v1/auth/verify").param("token", "used-token"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("already_used"))
    }

    @Test
    fun `verify with an unknown token returns 401`() {
        every { authService.verify("unknown-token") } throws MagicLinkTokenNotFoundException()

        mockMvc.perform(get("/api/v1/auth/verify").param("token", "unknown-token"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("invalid_token"))
    }
}
