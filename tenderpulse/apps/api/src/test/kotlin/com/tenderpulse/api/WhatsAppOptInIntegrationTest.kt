package com.tenderpulse.api

import com.tenderpulse.auth.BearerTokenService
import com.tenderpulse.domain.Subscriber
import com.tenderpulse.domain.SubscriberRepository
import com.tenderpulse.domain.SubscriptionTier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Full-context tests for issue #104 (TP-093, WhatsApp subscriber opt-in): boots the real
 * [com.tenderpulse.auth.SecurityConfig] filter chain and
 * [com.tenderpulse.auth.SubscriberOwnershipInterceptor] against
 * `PATCH /api/v1/subscribers/{id}/whatsapp`, same pattern as [AuthIntegrationTest] for the
 * pre-existing profile endpoints -- this is the route added to
 * [com.tenderpulse.auth.SubscriberOwnershipPaths.PROTECTED_PATH_PATTERNS] by this task, so this
 * suite is what actually proves it's wired correctly rather than just reasoning about the pattern
 * list.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WhatsAppOptInIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var subscriberRepository: SubscriberRepository

    @Autowired
    private lateinit var bearerTokenService: BearerTokenService

    private fun createSubscriber(email: String, tier: SubscriptionTier = SubscriptionTier.PAID): Subscriber =
        subscriberRepository.save(Subscriber(email = email, tier = tier))

    private fun whatsappUrl(id: java.util.UUID) = "/api/v1/subscribers/$id/whatsapp"

    // ---- AC / test case 1: valid number + explicit consent ----

    @Test
    fun `paid subscriber submitting a valid E164 number with consentGiven true stores both fields`() {
        val subscriber = createSubscriber("paid-optin@example.com")
        val token = bearerTokenService.issue(subscriber.id)

        mockMvc.perform(
            patch(whatsappUrl(subscriber.id))
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"number":"+263771234567","consentGiven":true}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.whatsappNumber").value("+263771234567"))
            .andExpect(jsonPath("$.whatsappOptIn").value(true))

        val reloaded = subscriberRepository.findById(subscriber.id).orElseThrow()
        assertEquals("+263771234567", reloaded.whatsappNumber)
        assertTrue(reloaded.whatsappOptIn)
    }

    // ---- AC / test case 2: consent omitted or false never silently sets true ----

    @Test
    fun `paid subscriber submitting a valid number with consentGiven omitted stores the number but leaves optIn false`() {
        val subscriber = createSubscriber("paid-no-consent@example.com")
        val token = bearerTokenService.issue(subscriber.id)

        mockMvc.perform(
            patch(whatsappUrl(subscriber.id))
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"number":"+263771234567"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.whatsappOptIn").value(false))

        val reloaded = subscriberRepository.findById(subscriber.id).orElseThrow()
        assertEquals("+263771234567", reloaded.whatsappNumber)
        assertFalse(reloaded.whatsappOptIn)
    }

    @Test
    fun `paid subscriber submitting a valid number with consentGiven explicitly false stores the number but leaves optIn false`() {
        val subscriber = createSubscriber("paid-explicit-false@example.com")
        val token = bearerTokenService.issue(subscriber.id)

        mockMvc.perform(
            patch(whatsappUrl(subscriber.id))
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"number":"+263771234567","consentGiven":false}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.whatsappOptIn").value(false))

        val reloaded = subscriberRepository.findById(subscriber.id).orElseThrow()
        assertFalse(reloaded.whatsappOptIn)
    }

    /**
     * A subscriber who previously opted in and now resubmits with `consentGiven: false` is
     * genuinely revoking consent, not merely leaving a prior value unchanged -- guards against a
     * "coalesce with existing value" implementation that would silently keep opt-in true.
     */
    @Test
    fun `resubmitting with consentGiven false after a prior true genuinely revokes opt-in`() {
        val subscriber = createSubscriber("paid-revoke@example.com")
        val token = bearerTokenService.issue(subscriber.id)

        mockMvc.perform(
            patch(whatsappUrl(subscriber.id))
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"number":"+263771234567","consentGiven":true}""")
        ).andExpect(status().isOk).andExpect(jsonPath("$.whatsappOptIn").value(true))

        mockMvc.perform(
            patch(whatsappUrl(subscriber.id))
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"number":"+263771234567","consentGiven":false}""")
        ).andExpect(status().isOk).andExpect(jsonPath("$.whatsappOptIn").value(false))

        val reloaded = subscriberRepository.findById(subscriber.id).orElseThrow()
        assertFalse(reloaded.whatsappOptIn)
    }

    // ---- AC / test case 3: malformed number rejected, no partial state persisted ----

    @Test
    fun `a malformed phone number returns 400 and persists no partial state`() {
        val subscriber = createSubscriber("paid-malformed@example.com")
        val token = bearerTokenService.issue(subscriber.id)

        mockMvc.perform(
            patch(whatsappUrl(subscriber.id))
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"number":"0771234567","consentGiven":true}""")
        ).andExpect(status().isBadRequest)

        val reloaded = subscriberRepository.findById(subscriber.id).orElseThrow()
        assertNull(reloaded.whatsappNumber)
        assertFalse(reloaded.whatsappOptIn)
    }

    @Test
    fun `a number missing the leading plus returns 400`() {
        val subscriber = createSubscriber("paid-noplus@example.com")
        val token = bearerTokenService.issue(subscriber.id)

        mockMvc.perform(
            patch(whatsappUrl(subscriber.id))
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"number":"263771234567","consentGiven":true}""")
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `a blank number returns 400`() {
        val subscriber = createSubscriber("paid-blank@example.com")
        val token = bearerTokenService.issue(subscriber.id)

        mockMvc.perform(
            patch(whatsappUrl(subscriber.id))
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"number":"","consentGiven":true}""")
        ).andExpect(status().isBadRequest)
    }

    // ---- AC / test case 4: Free-tier rejected ----

    @Test
    fun `a free-tier subscriber attempting to set a WhatsApp number is rejected`() {
        val subscriber = createSubscriber("free-tier@example.com", tier = SubscriptionTier.FREE)
        val token = bearerTokenService.issue(subscriber.id)

        mockMvc.perform(
            patch(whatsappUrl(subscriber.id))
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"number":"+263771234567","consentGiven":true}""")
        ).andExpect(status().isForbidden)

        val reloaded = subscriberRepository.findById(subscriber.id).orElseThrow()
        assertNull(reloaded.whatsappNumber)
        assertFalse(reloaded.whatsappOptIn)
    }

    // ---- AC / test case 5: ownership enforced ----

    @Test
    fun `an unauthenticated request is rejected 401`() {
        val subscriber = createSubscriber("unauth@example.com")

        mockMvc.perform(
            patch(whatsappUrl(subscriber.id))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"number":"+263771234567","consentGiven":true}""")
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `subscriber A attempting to set subscriber B's WhatsApp number is rejected 403 and B is untouched`() {
        val victim = createSubscriber("victim@example.com")
        val attacker = createSubscriber("attacker@example.com")
        val attackerToken = bearerTokenService.issue(attacker.id)

        mockMvc.perform(
            patch(whatsappUrl(victim.id))
                .header("Authorization", "Bearer $attackerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"number":"+263771234567","consentGiven":true}""")
        ).andExpect(status().isForbidden)

        val reloaded = subscriberRepository.findById(victim.id).orElseThrow()
        assertNull(reloaded.whatsappNumber)
        assertFalse(reloaded.whatsappOptIn)
    }

    @Test
    fun `the owner's own token succeeds against their own record`() {
        val subscriber = createSubscriber("self-owner@example.com")
        val token = bearerTokenService.issue(subscriber.id)

        mockMvc.perform(
            patch(whatsappUrl(subscriber.id))
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"number":"+263771234567","consentGiven":true}""")
        ).andExpect(status().isOk)
    }
}
