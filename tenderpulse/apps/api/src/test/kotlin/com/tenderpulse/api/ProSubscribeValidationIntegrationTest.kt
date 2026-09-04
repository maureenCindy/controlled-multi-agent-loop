package com.tenderpulse.api

import com.tenderpulse.paypal.PayPalClient
import com.tenderpulse.paypal.PayPalSubscriberInfo
import com.tenderpulse.paypal.PayPalSubscriptionResponse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.bind.MethodArgumentNotValidException

/**
 * Full-context tests for issue #81 (`paypalSubscriptionId` shape validation on the public,
 * unauthenticated `POST /api/v1/subscribers/pro` route): boots the real
 * [com.tenderpulse.auth.SecurityConfig] filter chain, unlike [SubscriberControllerTest]
 * (standalone MockMvc, controller only — no security filters run there at all).
 *
 * ## Why this is a materially simpler case than issue #68 / [AdminPlanIdValidationIntegrationTest]
 *
 * #68's `planId` is a `@PathVariable` — part of the request URL — so a malicious character had to
 * survive routing (and, for `/` specifically, [org.springframework.security.web.firewall.StrictHttpFirewall]'s
 * URL-decoding checks) before ever reaching Spring MVC's handler-method dispatch, and *which*
 * layer caught which payload had to be verified empirically per-character (see that class's kdoc).
 *
 * `paypalSubscriptionId` here is a `@RequestBody` JSON field, not part of the URL at all — the
 * request line for every test below is the fixed literal `POST /api/v1/subscribers/pro`; the
 * malicious characters (`/`, `..`, `?`) only ever exist inside the JSON body text, sent via
 * `.content(...)`, never parsed as a URI template. `StrictHttpFirewall` inspects the request
 * *URI*, not the body, so it has nothing to do with any of these three payloads — confirmed below
 * by `resolvedException` being [MethodArgumentNotValidException] (not `null`) in every rejection
 * case, i.e. Spring MVC's dispatch and `@Valid` machinery is what actually ran and rejected the
 * request, not the security filter chain. This also means, unlike #68, there is no
 * `post(String)`-vs-`request(HttpMethod, URI)` double-encoding pitfall to avoid here: none of
 * these payloads are ever part of a URI template argument, only literal JSON body content.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProSubscribeValidationIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var payPalClient: PayPalClient

    @MockitoBean
    private lateinit var javaMailSender: JavaMailSender

    private fun proJson(subscriptionId: String, email: String = "pro@example.com") =
        """{"email":"$email","paypalSubscriptionId":"$subscriptionId"}"""

    @Test
    fun `a subscription id containing a slash is rejected by Bean Validation, never reaching PayPalClient`() {
        val result = mockMvc.perform(
            post("/api/v1/subscribers/pro")
                .contentType(MediaType.APPLICATION_JSON)
                .content(proJson("I-VALID/../admin-only"))
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
    fun `a subscription id containing dot-dot is rejected by Bean Validation, never reaching PayPalClient`() {
        val result = mockMvc.perform(
            post("/api/v1/subscribers/pro")
                .contentType(MediaType.APPLICATION_JSON)
                .content(proJson("I-VALID..EVIL"))
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
    fun `a subscription id containing a question mark is rejected by Bean Validation, never reaching PayPalClient`() {
        val result = mockMvc.perform(
            post("/api/v1/subscribers/pro")
                .contentType(MediaType.APPLICATION_JSON)
                .content(proJson("I-VALID?evil=true"))
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

    /**
     * Test case 1 (AC): a valid `paypalSubscriptionId` reaches [SubscriberController], passes
     * `@Valid`, and verification proceeds as before — [PayPalClient.fetchSubscription] is called
     * with the exact ID submitted, and (with a stubbed ACTIVE/matching-plan/matching-email
     * response) the upgrade succeeds end-to-end through the real security filter chain.
     */
    @Test
    fun `a valid subscription id reaches SubscriberController and PayPalClient, and the upgrade succeeds`() {
        Mockito.`when`(payPalClient.fetchSubscription("I-VALIDSUB123")).thenReturn(
            PayPalSubscriptionResponse(
                id = "I-VALIDSUB123",
                status = "ACTIVE",
                planId = "",
                subscriber = PayPalSubscriberInfo(emailAddress = "pro@example.com")
            )
        )

        mockMvc.perform(
            post("/api/v1/subscribers/pro")
                .contentType(MediaType.APPLICATION_JSON)
                .content(proJson("I-VALIDSUB123"))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.paypalSubscriptionId").value("I-VALIDSUB123"))
            .andExpect(jsonPath("$.tier").value("PAID"))

        Mockito.verify(payPalClient).fetchSubscription("I-VALIDSUB123")
    }
}
