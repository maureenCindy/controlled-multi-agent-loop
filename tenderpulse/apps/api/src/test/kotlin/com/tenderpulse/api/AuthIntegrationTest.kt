package com.tenderpulse.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.tenderpulse.auth.BearerTokenService
import com.tenderpulse.domain.InterestProfileRepository
import com.tenderpulse.domain.Subscriber
import com.tenderpulse.domain.SubscriberRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.net.URI

/**
 * Full-context tests for TP-038's security wiring: boots the real [com.tenderpulse.auth.SecurityConfig]
 * filter chain and [com.tenderpulse.auth.SubscriberOwnershipInterceptor], unlike
 * [AuthControllerTest] (standalone MockMvc, controller only) and
 * [com.tenderpulse.api.SubscriberControllerTest] (also standalone — security isn't in play
 * there at all). This is the only place that actually proves the two are wired together
 * correctly against the real `/api/v1/subscribers/{id}/profiles...` routes.
 *
 * [JavaMailSender] is mocked (no live network — same principle as the PRAZ adapter's fixture
 * tests) so `POST /api/v1/auth/magic-link` can run against the real [MagicLinkMailSender] bean
 * without attempting to reach an SMTP server.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var subscriberRepository: SubscriberRepository

    @Autowired
    private lateinit var profileRepository: InterestProfileRepository

    @Autowired
    private lateinit var bearerTokenService: BearerTokenService

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockitoBean
    private lateinit var javaMailSender: JavaMailSender

    private fun createSubscriber(email: String): Subscriber = subscriberRepository.save(Subscriber(email = email))

    @Test
    fun `unauthenticated request to a profile endpoint is rejected 401`() {
        val subscriber = createSubscriber("int-401@example.com")

        mockMvc.perform(get("/api/v1/subscribers/${subscriber.id}/profiles"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `authenticated request for a different subscriber's profile is rejected 403`() {
        val owner = createSubscriber("owner@example.com")
        val intruder = createSubscriber("intruder@example.com")
        val intruderToken = bearerTokenService.issue(intruder.id)

        mockMvc.perform(
            get("/api/v1/subscribers/${owner.id}/profiles")
                .header("Authorization", "Bearer $intruderToken")
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `authenticated request for one's own subscriber succeeds`() {
        val subscriber = createSubscriber("self@example.com")
        val token = bearerTokenService.issue(subscriber.id)

        mockMvc.perform(
            get("/api/v1/subscribers/${subscriber.id}/profiles")
                .header("Authorization", "Bearer $token")
        )
            .andExpect(status().isOk)
            .andExpect(content().json("[]"))
    }

    @Test
    fun `POST subscribers signup remains open and unauthenticated`() {
        mockMvc.perform(
            post("/api/v1/subscribers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"new-signup@example.com"}""")
        ).andExpect(status().isCreated)
    }

    @Test
    fun `full round trip - request magic link, verify the emailed token, use the bearer token, token is then single-use`() {
        val subscriber = createSubscriber("roundtrip@example.com")

        mockMvc.perform(
            post("/api/v1/auth/magic-link")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"roundtrip@example.com"}""")
        ).andExpect(status().isOk)

        val messageCaptor = ArgumentCaptor.forClass(SimpleMailMessage::class.java)
        verify(javaMailSender).send(messageCaptor.capture())
        val rawToken = Regex("token=(\\S+)").find(messageCaptor.value.text!!)!!.groupValues[1]

        val verifyResult = mockMvc.perform(get("/api/v1/auth/verify").param("token", rawToken))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accessToken").exists())
            .andReturn()
        val accessToken = objectMapper.readTree(verifyResult.response.contentAsString).get("accessToken").asText()

        mockMvc.perform(
            get("/api/v1/subscribers/${subscriber.id}/profiles")
                .header("Authorization", "Bearer $accessToken")
        ).andExpect(status().isOk)

        // Single-use: verifying the same raw token again must fail even though it hasn't expired.
        mockMvc.perform(get("/api/v1/auth/verify").param("token", rawToken))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("already_used"))
    }

    @Test
    fun `requesting a magic link for a non-existent email sends no mail and still returns 200`() {
        mockMvc.perform(
            post("/api/v1/auth/magic-link")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"does-not-exist@example.com"}""")
        ).andExpect(status().isOk)

        verify(javaMailSender, org.mockito.Mockito.never()).send(org.mockito.ArgumentMatchers.any(SimpleMailMessage::class.java))
    }

    // ---- Regression: ownership check must not be bypassable via URI encoding ----
    //
    // A prior version of SubscriberOwnershipInterceptor compared the ownership check against
    // the raw (possibly percent-encoded) request URI. Percent-encoding a single character of
    // "profiles" (e.g. "prof%69les", which decodes to "profiles") made that regex miss even
    // though Spring's own decoded-path request mapping still routed the request to
    // SubscriberController — a full 403 bypass. These tests hit that exact encoded path and
    // assert the request is still rejected for a token that isn't the path subscriber's own.

    @Test
    fun `a read via a percent-encoded 'profiles' segment is still rejected for a non-owning token`() {
        val owner = createSubscriber("encoded-read-owner@example.com")
        val intruder = createSubscriber("encoded-read-intruder@example.com")
        val intruderToken = bearerTokenService.issue(intruder.id)

        // "prof%69les" percent-decodes to "profiles" — must resolve to the same guarded route.
        val encodedUri = URI("/api/v1/subscribers/${owner.id}/prof%69les")

        mockMvc.perform(get(encodedUri).header("Authorization", "Bearer $intruderToken"))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `a write via a percent-encoded 'profiles' segment is still rejected and plants nothing`() {
        val owner = createSubscriber("encoded-write-owner@example.com")
        val intruder = createSubscriber("encoded-write-intruder@example.com")
        val intruderToken = bearerTokenService.issue(intruder.id)
        val profilesBefore = profileRepository.findBySubscriberId(owner.id).size

        val encodedUri = URI("/api/v1/subscribers/${owner.id}/prof%69les")

        mockMvc.perform(
            post(encodedUri)
                .header("Authorization", "Bearer $intruderToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"sectors":["IT"]}""")
        ).andExpect(status().isForbidden)

        assertEquals(profilesBefore, profileRepository.findBySubscriberId(owner.id).size)
    }

    @Test
    fun `the owner's own token still works against the percent-encoded path`() {
        val owner = createSubscriber("encoded-self@example.com")
        val ownerToken = bearerTokenService.issue(owner.id)

        val encodedUri = URI("/api/v1/subscribers/${owner.id}/prof%69les")

        mockMvc.perform(get(encodedUri).header("Authorization", "Bearer $ownerToken"))
            .andExpect(status().isOk)
    }
}
