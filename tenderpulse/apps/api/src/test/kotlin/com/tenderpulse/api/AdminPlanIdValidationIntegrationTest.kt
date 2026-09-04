package com.tenderpulse.api

import com.tenderpulse.auth.AdminKeyAuthFilter
import com.tenderpulse.paypal.PayPalClient
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Full-context tests for issue #68 (`planId` shape validation on the admin plan-pricing route):
 * boots the real [com.tenderpulse.auth.SecurityConfig] filter chain, unlike [AdminControllerTest]
 * (standalone MockMvc, controller only — no security filters, including no `HttpFirewall`, run
 * at all there).
 *
 * This distinction matters here specifically because malicious `planId` payloads for this issue
 * are **not all stopped by the same layer**, and [AdminControllerTest]'s standalone setup cannot
 * tell the difference:
 *
 * - `P-FAKE..EVIL` (a `..` substring *within* one path segment — no `/` or encoding involved)
 *   reaches [AdminController.updatePlanPricing] normally and is rejected there, by this PR's
 *   [com.tenderpulse.domain.InvalidPlanIdException] check — see `an embedded dot-dot is rejected
 *   by AdminController's own validation, having actually reached it` below, which confirms via
 *   [PayPalClient] never being invoked *and* the response coming from this app's own exception
 *   mapping (JSON body), not a generic security error page.
 * - A literal `/` inside `planId` (e.g. `%2F`-encoded) and a literal `?` (`%3F`-encoded) are both
 *   rejected by Spring Security's *default* `StrictHttpFirewall` before `DispatcherServlet` ever
 *   resolves a handler — i.e. before [AdminController.updatePlanPricing] runs at all. This is
 *   pre-existing platform behavior this PR does not add, change, or depend on; the tests below
 *   simply confirm PayPal is still never called for these payloads, without claiming this PR's
 *   own validation is what stopped them.
 *
 * (An earlier version of this PR mapped this route as `/plans/{planId:.+}/pricing`, believing the
 * greedy regex was needed for a raw `/` to be captured as a single `planId` value. That's
 * incorrect: Spring's `PathPatternParser` splits the request path into segments *before* applying
 * any per-variable regex, so `{planId:.+}` cannot span an actual `/` in the URL either way — the
 * route was reverted back to the default single-segment `{planId}`, since it had no demonstrated
 * effect on any of the payloads this issue cares about.)
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminPlanIdValidationIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var payPalClient: PayPalClient

    @MockitoBean
    private lateinit var javaMailSender: JavaMailSender

    private val adminKeyHeader = AdminKeyAuthFilter.ADMIN_KEY_HEADER to TEST_ADMIN_KEY
    private val validBody = """{"currencyCode":"USD","fixedPrice":19.99}"""

    @Test
    fun `an embedded dot-dot is rejected by AdminController's own validation, having actually reached it`() {
        mockMvc.perform(
            post("/api/v1/admin/plans/P-FAKE..EVIL/pricing")
                .header(adminKeyHeader.first, adminKeyHeader.second)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody)
        )
            .andExpect(status().isBadRequest)

        verifyNoInteractions(payPalClient)
    }

    @Test
    fun `an encoded slash never reaches AdminController - rejected upstream, PayPal still never called`() {
        mockMvc.perform(
            post("/api/v1/admin/plans/P-VALID%2FEVIL/pricing")
                .header(adminKeyHeader.first, adminKeyHeader.second)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody)
        )
            .andExpect(status().is4xxClientError)

        verifyNoInteractions(payPalClient)
    }

    @Test
    fun `an encoded question mark never reaches AdminController - rejected upstream, PayPal still never called`() {
        mockMvc.perform(
            post("/api/v1/admin/plans/P-FAKE%3FEVIL/pricing")
                .header(adminKeyHeader.first, adminKeyHeader.second)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody)
        )
            .andExpect(status().is4xxClientError)

        verifyNoInteractions(payPalClient)
    }

    @Test
    fun `a valid planId reaches AdminController and calls PayPalClient`() {
        mockMvc.perform(
            post("/api/v1/admin/plans/P-VALIDPLAN/pricing")
                .header(adminKeyHeader.first, adminKeyHeader.second)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody)
        )
            .andExpect(status().isOk)

        org.mockito.Mockito.verify(payPalClient).updatePlanPricing(
            "P-VALIDPLAN",
            "USD",
            java.math.BigDecimal("19.99"),
            1
        )
    }

    companion object {
        private const val TEST_ADMIN_KEY = "test-only-fixed-admin-key-used-for-jvm-test-runs-only"
    }
}
