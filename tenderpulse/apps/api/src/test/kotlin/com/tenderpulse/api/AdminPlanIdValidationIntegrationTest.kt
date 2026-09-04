package com.tenderpulse.api

import com.tenderpulse.auth.AdminKeyAuthFilter
import com.tenderpulse.domain.InvalidPlanIdException
import com.tenderpulse.paypal.PayPalClient
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.net.URI

/**
 * Full-context tests for issue #68 (`planId` shape validation on the admin plan-pricing route):
 * boots the real [com.tenderpulse.auth.SecurityConfig] filter chain, unlike [AdminControllerTest]
 * (standalone MockMvc, controller only — no security filters, including no `HttpFirewall`, run
 * at all there).
 *
 * ## Why raw `URI` construction, not `post(String)`
 *
 * These tests build the malicious requests via `request(HttpMethod.POST, URI.create(...))`
 * rather than `MockMvcRequestBuilders.post("...")`. That's not stylistic: `post(String
 * uriTemplate)` parses its argument as a *template* through `UriComponentsBuilder`, which
 * re-encodes any literal `%` in the string — so `post("/plans/P-FAKE%3FEVIL/pricing")` actually
 * sends `P-FAKE%253FEVIL` (double-encoded) on the wire, not the single-encoded `%3F` the test name
 * claims. A previous revision of this test made exactly that mistake, and the double-encoded
 * payload happened to also get rejected (by the firewall's *separate* `allowUrlEncodedPercent`
 * check, not by whatever actually handles a real `%3F`), producing a passing assertion for the
 * wrong reason. `URI.create(str)` parses `str` as an already-escaped URI per RFC 3986 and doesn't
 * re-escape it, so passing that `URI` object directly sends the exact single-encoded bytes.
 *
 * ## What was actually verified here (do not restate without re-running this test)
 *
 * [org.springframework.security.web.firewall.StrictHttpFirewall]'s default blocklist (Spring
 * Security 6.4.2, this project's pinned version) rejects `%2F`/`%2f` (encoded slash) and `%25`
 * (encoded percent, which is why the double-encoding bug above accidentally "worked") but has
 * **no** default rule for `%3F`/`%3f` (encoded question mark) or for `.` — so:
 *
 * - a genuinely single-encoded `%2F` (raw `/`) **is** rejected by the firewall, before
 *   [AdminController.updatePlanPricing] ever runs — confirmed below by `resolvedException` being
 *   `null` (Spring MVC's exception-resolution machinery was never reached at all).
 * - a genuinely single-encoded `%3F` (raw `?`) reaches [AdminController.updatePlanPricing] like
 *   any other value and is rejected by *this PR's own* [InvalidPlanIdException] check — confirmed
 *   below by `resolvedException` actually being an [InvalidPlanIdException] instance.
 * - `P-FAKE..EVIL` (a `..` substring within one path segment) likewise reaches the controller and
 *   is rejected by the same check.
 *
 * In every case [PayPalClient] is never invoked — there is no live vulnerability regardless of
 * which layer catches a given payload — but only the `/` case is actually protected by platform
 * behavior outside this PR's control; `..` and `?` depend entirely on this PR's validation.
 *
 * The firewall's exact blocklist is an **unpinned, implicit dependency**: nothing in this repo
 * pins or tests `StrictHttpFirewall`'s configuration directly, so if a future change (a custom
 * `HttpFirewall` bean, a Spring Security upgrade that changes the default blocklist, etc.) altered
 * or removed that behavior, a raw `/` in `planId` would no longer be stopped before reaching
 * [AdminController.updatePlanPricing] — and unlike `..`/`?`, this PR's own
 * [InvalidPlanIdException] check *would* still catch it (the regex rejects `/` too), so the actual
 * risk of that drift is low, but it's worth knowing this test doesn't pin the firewall's behavior,
 * only observes it as of Spring Security 6.4.2.
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
    fun `an embedded dot-dot reaches AdminController and is rejected by this PR's own validation`() {
        val result = mockMvc.perform(
            post("/api/v1/admin/plans/P-FAKE..EVIL/pricing")
                .header(adminKeyHeader.first, adminKeyHeader.second)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody)
        )
            .andExpect(status().isBadRequest)
            .andReturn()

        // resolvedException non-null (and specifically ours) proves Spring MVC's dispatch and
        // exception-resolution machinery actually ran for this request -- i.e. it reached
        // AdminController, not just "some 400 came back from somewhere".
        assertTrue(
            result.resolvedException is InvalidPlanIdException,
            "expected AdminController's own InvalidPlanIdException to have resolved this request, " +
                "got: ${result.resolvedException}"
        )
        verifyNoInteractions(payPalClient)
    }

    @Test
    fun `a genuinely single-encoded slash is rejected before AdminController - by the firewall, not this PR`() {
        val result = mockMvc.perform(
            request(HttpMethod.POST, URI.create("/api/v1/admin/plans/P-VALID%2FEVIL/pricing"))
                .header(adminKeyHeader.first, adminKeyHeader.second)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody)
        )
            .andExpect(status().is4xxClientError)
            .andReturn()

        // resolvedException == null proves Spring MVC's dispatch never happened at all for this
        // request -- it was rejected by the security filter chain's HttpFirewall before
        // DispatcherServlet resolved a handler, not by AdminController's validation.
        assertNull(
            result.resolvedException,
            "expected no MVC-resolved exception (firewall should reject this before dispatch), " +
                "got: ${result.resolvedException}"
        )
        verifyNoInteractions(payPalClient)
    }

    @Test
    fun `a genuinely single-encoded question mark DOES reach AdminController - rejected by this PR's own validation`() {
        val result = mockMvc.perform(
            request(HttpMethod.POST, URI.create("/api/v1/admin/plans/P-FAKE%3FEVIL/pricing"))
                .header(adminKeyHeader.first, adminKeyHeader.second)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody)
        )
            .andExpect(status().isBadRequest)
            .andReturn()

        // Unlike the slash case above: %3F has no default StrictHttpFirewall rule (verified
        // against spring-security-web 6.4.2's actual blocklist), so this request reaches
        // AdminController like any other, and it's this PR's own InvalidPlanIdException check --
        // not the firewall -- that rejects it. resolvedException being our own exception type
        // proves MVC dispatch (and therefore this method) actually ran.
        assertTrue(
            result.resolvedException is InvalidPlanIdException,
            "expected AdminController's own InvalidPlanIdException to have resolved this request, " +
                "got: ${result.resolvedException}"
        )
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
