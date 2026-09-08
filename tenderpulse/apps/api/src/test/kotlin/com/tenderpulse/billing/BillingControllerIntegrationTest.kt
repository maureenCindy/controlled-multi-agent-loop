package com.tenderpulse.billing

import com.tenderpulse.auth.BearerTokenService
import com.tenderpulse.domain.Subscriber
import com.tenderpulse.domain.SubscriberRepository
import com.tenderpulse.domain.SubscriptionPlan
import com.tenderpulse.paypal.PayPalClient
import com.tenderpulse.paypal.PayPalSubscriptionResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.bind.MethodArgumentNotValidException

/**
 * Full-context tests for issue #127 (TP-127, unified PayPal Pro/Max confirm endpoint): boots the
 * real [com.tenderpulse.auth.SecurityConfig] filter chain, same pattern as
 * [com.tenderpulse.api.WhatsAppOptInIntegrationTest] and
 * [com.tenderpulse.api.ProSubscribeValidationIntegrationTest] (whose path-injection proof this
 * mirrors for `ConfirmSubscriptionRequest.paypalSubscriptionId`).
 *
 * Fixed, non-blank `paypal.plans.pro` / `paypal.plans.max` (via [TestPropertySource]) so plan-id
 * matching assertions are meaningful -- the default test classpath otherwise leaves both blank
 * (see `src/test/resources/application.yml`, which has no `paypal:` section), which would make a
 * "matches configured plan id" assertion trivially true against another blank string instead of a
 * real value.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = ["paypal.plans.pro=P-PRO-CONFIGURED", "paypal.plans.max=P-MAX-CONFIGURED"])
class BillingControllerIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var subscriberRepository: SubscriberRepository

    @Autowired
    private lateinit var bearerTokenService: BearerTokenService

    @MockitoBean
    private lateinit var payPalClient: PayPalClient

    @MockitoBean
    private lateinit var javaMailSender: JavaMailSender

    private fun createSubscriber(email: String, tier: SubscriptionPlan = SubscriptionPlan.FREE): Subscriber =
        subscriberRepository.save(Subscriber(email = email, tier = tier))

    private fun confirmJson(subscriptionId: String, requestedPlan: String = "PRO") =
        """{"paypalSubscriptionId":"$subscriptionId","requestedPlan":"$requestedPlan"}"""

    // ---- GET /api/v1/billing/public-config ----

    @Test
    fun `public-config returns the documented shape with no secrets, and requires no authentication`() {
        mockMvc.perform(get("/api/v1/billing/public-config"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.provider").value("PAYPAL"))
            .andExpect(jsonPath("$.currency").value("USD"))
            .andExpect(jsonPath("$.plans.PRO.paypalPlanId").value("P-PRO-CONFIGURED"))
            .andExpect(jsonPath("$.plans.PRO.amount").value("5.00"))
            .andExpect(jsonPath("$.plans.MAX.paypalPlanId").value("P-MAX-CONFIGURED"))
            .andExpect(jsonPath("$.plans.MAX.amount").value("30.00"))
            .andExpect(jsonPath("$.clientSecret").doesNotExist())
            .andExpect(jsonPath("$.webhookId").doesNotExist())
    }

    // ---- Test case 1: valid, ACTIVE, matching-plan confirm ----

    @Test
    fun `test case 1 - a valid ACTIVE subscription matching the requested Pro plan upgrades the subscriber to PRO`() {
        val subscriber = createSubscriber("confirm-pro@example.com")
        val token = bearerTokenService.issue(subscriber.id)
        Mockito.`when`(payPalClient.fetchSubscription("I-PRO-VALID")).thenReturn(
            PayPalSubscriptionResponse(id = "I-PRO-VALID", status = "ACTIVE", planId = "P-PRO-CONFIGURED")
        )

        mockMvc.perform(
            post("/api/v1/billing/paypal/subscriptions/confirm")
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(confirmJson("I-PRO-VALID", "PRO"))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.tier").value("PRO"))
            .andExpect(jsonPath("$.paypalSubscriptionId").value("I-PRO-VALID"))

        val reloaded = subscriberRepository.findById(subscriber.id).orElseThrow()
        assertEquals(SubscriptionPlan.PRO, reloaded.tier)
        assertEquals("I-PRO-VALID", reloaded.paypalSubscriptionId)
    }

    @Test
    fun `test case 1 - a valid ACTIVE subscription matching the requested Max plan upgrades the subscriber to MAX`() {
        val subscriber = createSubscriber("confirm-max@example.com")
        val token = bearerTokenService.issue(subscriber.id)
        Mockito.`when`(payPalClient.fetchSubscription("I-MAX-VALID")).thenReturn(
            PayPalSubscriptionResponse(id = "I-MAX-VALID", status = "ACTIVE", planId = "P-MAX-CONFIGURED")
        )

        mockMvc.perform(
            post("/api/v1/billing/paypal/subscriptions/confirm")
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(confirmJson("I-MAX-VALID", "MAX"))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.tier").value("MAX"))

        val reloaded = subscriberRepository.findById(subscriber.id).orElseThrow()
        assertEquals(SubscriptionPlan.MAX, reloaded.tier)
    }

    // ---- Test case 2: plan mismatch ----

    @Test
    fun `test case 2 - a subscription for a different plan than requested is rejected, no upgrade`() {
        val subscriber = createSubscriber("plan-mismatch@example.com")
        val token = bearerTokenService.issue(subscriber.id)
        Mockito.`when`(payPalClient.fetchSubscription("I-WRONG-PLAN")).thenReturn(
            PayPalSubscriptionResponse(id = "I-WRONG-PLAN", status = "ACTIVE", planId = "P-MAX-CONFIGURED")
        )

        mockMvc.perform(
            post("/api/v1/billing/paypal/subscriptions/confirm")
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(confirmJson("I-WRONG-PLAN", "PRO"))
        ).andExpect(status().isBadRequest)

        val reloaded = subscriberRepository.findById(subscriber.id).orElseThrow()
        assertEquals(SubscriptionPlan.FREE, reloaded.tier)
        assertNull(reloaded.paypalSubscriptionId)
    }

    /** requestedPlan outside {PRO, MAX} (e.g. FREE) is rejected before PayPalClient is ever called. */
    @Test
    fun `requestedPlan FREE is rejected by Jackson deserialization, never reaching PayPalClient`() {
        mockMvc.perform(
            post("/api/v1/billing/paypal/subscriptions/confirm")
                .header("Authorization", "Bearer ${bearerTokenService.issue(createSubscriber("free-plan@example.com").id)}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(confirmJson("I-SOME-ID", "FREE"))
        ).andExpect(status().isBadRequest)

        verifyNoInteractions(payPalClient)
    }

    // ---- Test case 3: non-ACTIVE subscription ----

    @Test
    fun `test case 3 - a non-ACTIVE subscription is rejected, no upgrade`() {
        val subscriber = createSubscriber("not-active@example.com")
        val token = bearerTokenService.issue(subscriber.id)
        Mockito.`when`(payPalClient.fetchSubscription("I-SUSPENDED")).thenReturn(
            PayPalSubscriptionResponse(id = "I-SUSPENDED", status = "SUSPENDED", planId = "P-PRO-CONFIGURED")
        )

        mockMvc.perform(
            post("/api/v1/billing/paypal/subscriptions/confirm")
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(confirmJson("I-SUSPENDED", "PRO"))
        ).andExpect(status().isBadRequest)

        val reloaded = subscriberRepository.findById(subscriber.id).orElseThrow()
        assertEquals(SubscriptionPlan.FREE, reloaded.tier)
    }

    // ---- Test case 4: duplicate subscription-id linking ----

    @Test
    fun `test case 4 - a subscription id already linked to another subscriber is rejected, no double-linking`() {
        val original = createSubscriber("billing-confirm-owner@example.com")
        Mockito.`when`(payPalClient.fetchSubscription("I-SHARED")).thenReturn(
            PayPalSubscriptionResponse(id = "I-SHARED", status = "ACTIVE", planId = "P-PRO-CONFIGURED")
        )
        mockMvc.perform(
            post("/api/v1/billing/paypal/subscriptions/confirm")
                .header("Authorization", "Bearer ${bearerTokenService.issue(original.id)}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(confirmJson("I-SHARED", "PRO"))
        ).andExpect(status().isOk)

        val attacker = createSubscriber("attacker-confirm@example.com")
        mockMvc.perform(
            post("/api/v1/billing/paypal/subscriptions/confirm")
                .header("Authorization", "Bearer ${bearerTokenService.issue(attacker.id)}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(confirmJson("I-SHARED", "PRO"))
        ).andExpect(status().isBadRequest)

        val reloadedAttacker = subscriberRepository.findById(attacker.id).orElseThrow()
        assertEquals(SubscriptionPlan.FREE, reloadedAttacker.tier)
        assertNull(reloadedAttacker.paypalSubscriptionId)
        val reloadedOriginal = subscriberRepository.findById(original.id).orElseThrow()
        assertEquals("I-SHARED", reloadedOriginal.paypalSubscriptionId)
    }

    @Test
    fun `a retry by the same already-linked subscriber succeeds idempotently`() {
        val subscriber = createSubscriber("retry-owner@example.com")
        val token = bearerTokenService.issue(subscriber.id)
        Mockito.`when`(payPalClient.fetchSubscription("I-RETRY-SAME")).thenReturn(
            PayPalSubscriptionResponse(id = "I-RETRY-SAME", status = "ACTIVE", planId = "P-PRO-CONFIGURED")
        )

        repeat(2) {
            mockMvc.perform(
                post("/api/v1/billing/paypal/subscriptions/confirm")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(confirmJson("I-RETRY-SAME", "PRO"))
            ).andExpect(status().isOk)
        }

        val reloaded = subscriberRepository.findById(subscriber.id).orElseThrow()
        assertEquals(SubscriptionPlan.PRO, reloaded.tier)
        assertEquals("I-RETRY-SAME", reloaded.paypalSubscriptionId)
    }

    // ---- Test case 5: malicious/malformed subscription id -> rejected before reaching PayPalClient ----

    @Test
    fun `test case 5 - a subscription id containing a slash is rejected by Bean Validation, never reaching PayPalClient`() {
        val subscriber = createSubscriber("malicious-slash@example.com")
        val token = bearerTokenService.issue(subscriber.id)

        val result = mockMvc.perform(
            post("/api/v1/billing/paypal/subscriptions/confirm")
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(confirmJson("I-VALID/../admin-only", "PRO"))
        )
            .andExpect(status().isBadRequest)
            .andReturn()

        assertTrue(
            result.resolvedException is MethodArgumentNotValidException,
            "expected @Valid's MethodArgumentNotValidException to have resolved this request, " +
                "got: ${result.resolvedException}"
        )
        verifyNoInteractions(payPalClient)
    }

    @Test
    fun `test case 5 - a subscription id containing dot-dot is rejected by Bean Validation, never reaching PayPalClient`() {
        val subscriber = createSubscriber("malicious-dotdot@example.com")
        val token = bearerTokenService.issue(subscriber.id)

        mockMvc.perform(
            post("/api/v1/billing/paypal/subscriptions/confirm")
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(confirmJson("I-VALID..EVIL", "PRO"))
        ).andExpect(status().isBadRequest)

        verifyNoInteractions(payPalClient)
    }

    @Test
    fun `test case 5 - a subscription id containing a question mark is rejected by Bean Validation, never reaching PayPalClient`() {
        val subscriber = createSubscriber("malicious-qmark@example.com")
        val token = bearerTokenService.issue(subscriber.id)

        mockMvc.perform(
            post("/api/v1/billing/paypal/subscriptions/confirm")
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(confirmJson("I-VALID?evil=true", "PRO"))
        ).andExpect(status().isBadRequest)

        verifyNoInteractions(payPalClient)
    }

    // ---- Auth requirement ----

    @Test
    fun `an unauthenticated confirm request is rejected 401, never reaching PayPalClient`() {
        mockMvc.perform(
            post("/api/v1/billing/paypal/subscriptions/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content(confirmJson("I-SOME-ID", "PRO"))
        ).andExpect(status().isUnauthorized)

        verifyNoInteractions(payPalClient)
    }

    // ---- Test case 6: existing /subscribers/pro flow is unaffected ----

    @Test
    fun `test case 6 - the existing POST subscribers-pro flow still works unchanged`() {
        // paypal.plans.pro is overridden to "P-PRO-CONFIGURED" for this whole test class (see the
        // class-level @TestPropertySource) -- SubscriberService.registerPro reads that same
        // property, so the stubbed PayPal response must match it here too.
        Mockito.`when`(payPalClient.fetchSubscription("I-LEGACY-PRO")).thenReturn(
            PayPalSubscriptionResponse(
                id = "I-LEGACY-PRO",
                status = "ACTIVE",
                planId = "P-PRO-CONFIGURED",
                subscriber = com.tenderpulse.paypal.PayPalSubscriberInfo(emailAddress = "legacy-pro@example.com")
            )
        )

        mockMvc.perform(
            post("/api/v1/subscribers/pro")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"legacy-pro@example.com","paypalSubscriptionId":"I-LEGACY-PRO"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.tier").value("PRO"))
            .andExpect(jsonPath("$.paypalSubscriptionId").value("I-LEGACY-PRO"))

        assertNotNull(subscriberRepository.findByEmail("legacy-pro@example.com"))
    }
}
