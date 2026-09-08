package com.tenderpulse.api

import com.tenderpulse.auth.BearerTokenService
import com.tenderpulse.domain.Subscriber
import com.tenderpulse.domain.SubscriberRepository
import com.tenderpulse.paypal.PayPalClient
import com.tenderpulse.paypal.PayPalSubscriptionResponse
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * Full-context tests for TP-034's CORS wiring ([com.tenderpulse.auth.WebsiteCorsConfig]), extended
 * by TP-134/#134 for the Max checkout flow's 4 additional routes: the marketing site's configured
 * origin (`http://localhost:4321` — see `src/test/resources/application.yml`) must be allowed to
 * call every registered route, an arbitrary other origin must not, and the CORS configuration
 * must not leak onto routes it wasn't meant for (the authenticated profile endpoints, or GET
 * /api/v1/tenders).
 *
 * [JavaMailSender] is mocked purely to keep the Spring context boot fast/side-effect-free, same
 * as [AuthIntegrationTest] — it is not exercised by any of these requests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = ["paypal.plans.pro=P-CORS-CONFIRM-TEST"])
class WebsiteCorsIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var subscriberRepository: SubscriberRepository

    @Autowired
    private lateinit var bearerTokenService: BearerTokenService

    @MockitoBean
    private lateinit var javaMailSender: JavaMailSender

    @MockitoBean
    private lateinit var payPalClient: PayPalClient

    private val allowedOrigin = "http://localhost:4321"
    private val otherOrigin = "http://evil.example"

    @Test
    fun `preflight from the configured site origin is allowed for the Free signup endpoint`() {
        mockMvc.perform(
            options("/api/v1/subscribers")
                .header(HttpHeaders.ORIGIN, allowedOrigin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
        )
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, allowedOrigin))
    }

    @Test
    fun `preflight from the configured site origin is allowed for the Pro signup endpoint`() {
        mockMvc.perform(
            options("/api/v1/subscribers/pro")
                .header(HttpHeaders.ORIGIN, allowedOrigin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
        )
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, allowedOrigin))
    }

    @Test
    fun `an actual POST from the configured site origin carries the CORS allow header`() {
        mockMvc.perform(
            post("/api/v1/subscribers")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ORIGIN, allowedOrigin)
                .content("""{"email":"cors-allowed@example.com"}""")
        )
            .andExpect(status().isCreated)
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, allowedOrigin))
    }

    @Test
    fun `preflight from an arbitrary other origin is rejected`() {
        mockMvc.perform(
            options("/api/v1/subscribers")
                .header(HttpHeaders.ORIGIN, otherOrigin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
        )
            .andExpect(status().isForbidden)
    }

    @Test
    fun `an actual POST from an arbitrary other origin is rejected`() {
        mockMvc.perform(
            post("/api/v1/subscribers")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ORIGIN, otherOrigin)
                .content("""{"email":"cors-blocked@example.com"}""")
        )
            .andExpect(status().isForbidden)
    }

    @Test
    fun `CORS is not configured for routes outside the two signup endpoints`() {
        mockMvc.perform(
            options("/api/v1/tenders")
                .header(HttpHeaders.ORIGIN, allowedOrigin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
        )
            .andExpect(status().isForbidden)
    }

    @Test
    fun `a plain same-origin request with no Origin header is unaffected by CORS`() {
        mockMvc.perform(get("/api/v1/tenders"))
            .andExpect(status().isOk)
    }

    // ---- TP-134 / #134: 4 new Max checkout routes ----

    @Test
    fun `test case 1 - preflight from the site origin is allowed for GET billing public-config`() {
        mockMvc.perform(
            options("/api/v1/billing/public-config")
                .header(HttpHeaders.ORIGIN, allowedOrigin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
        )
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, allowedOrigin))
    }

    @Test
    fun `test case 1 - preflight from the site origin is allowed for GET auth verify`() {
        mockMvc.perform(
            options("/api/v1/auth/verify")
                .header(HttpHeaders.ORIGIN, allowedOrigin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
        )
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, allowedOrigin))
    }

    @Test
    fun `test case 1 - preflight from the site origin is allowed for POST auth magic-link`() {
        mockMvc.perform(
            options("/api/v1/auth/magic-link")
                .header(HttpHeaders.ORIGIN, allowedOrigin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
        )
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, allowedOrigin))
    }

    @Test
    fun `test case 1 - preflight from the site origin is allowed for POST billing confirm, including the Authorization header`() {
        mockMvc.perform(
            options("/api/v1/billing/paypal/subscriptions/confirm")
                .header(HttpHeaders.ORIGIN, allowedOrigin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization")
        )
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, allowedOrigin))
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, org.hamcrest.Matchers.containsStringIgnoringCase("authorization")))
    }

    @Test
    fun `test case 2 - preflight to the existing two signup routes is still allowed, no regression`() {
        mockMvc.perform(
            options("/api/v1/subscribers")
                .header(HttpHeaders.ORIGIN, allowedOrigin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
        ).andExpect(status().isOk)

        mockMvc.perform(
            options("/api/v1/subscribers/pro")
                .header(HttpHeaders.ORIGIN, allowedOrigin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
        ).andExpect(status().isOk)
    }

    @Test
    fun `test case 2 - a GET preflight to the existing two POST-only signup routes is still rejected, config was not widened`() {
        mockMvc.perform(
            options("/api/v1/subscribers")
                .header(HttpHeaders.ORIGIN, allowedOrigin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
        ).andExpect(status().isForbidden)

        mockMvc.perform(
            options("/api/v1/subscribers/pro")
                .header(HttpHeaders.ORIGIN, allowedOrigin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `test case 3 - preflight to an unrelated authenticated per-subscriber route is still rejected, no accidental widening`() {
        mockMvc.perform(
            options("/api/v1/subscribers/${UUID.randomUUID()}/profiles")
                .header(HttpHeaders.ORIGIN, allowedOrigin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `test case 4 - an actual confirm request with Authorization header from the site origin authenticates, header not stripped by CORS`() {
        val subscriber = subscriberRepository.save(Subscriber(email = "cors-confirm@example.com"))
        val token = bearerTokenService.issue(subscriber.id)
        Mockito.`when`(payPalClient.fetchSubscription("I-CORS-CONFIRM")).thenReturn(
            PayPalSubscriptionResponse(id = "I-CORS-CONFIRM", status = "ACTIVE", planId = "P-CORS-CONFIRM-TEST")
        )

        // Without the Authorization header, the same cross-origin request is rejected 401
        // (unauthenticated) -- establishes the baseline this test's assertion is contrasted
        // against.
        mockMvc.perform(
            post("/api/v1/billing/paypal/subscriptions/confirm")
                .header(HttpHeaders.ORIGIN, allowedOrigin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"paypalSubscriptionId":"I-CORS-CONFIRM","requestedPlan":"PRO"}""")
        ).andExpect(status().isUnauthorized)

        // With the Authorization header sent cross-origin, the request authenticates and
        // succeeds -- if CORS had stripped/blocked the header, this would come back 401 same as
        // the request above.
        mockMvc.perform(
            post("/api/v1/billing/paypal/subscriptions/confirm")
                .header(HttpHeaders.ORIGIN, allowedOrigin)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"paypalSubscriptionId":"I-CORS-CONFIRM","requestedPlan":"PRO"}""")
        )
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, allowedOrigin))
    }
}
