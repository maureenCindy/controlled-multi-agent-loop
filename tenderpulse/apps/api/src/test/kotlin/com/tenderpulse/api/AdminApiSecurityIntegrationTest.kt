package com.tenderpulse.api

import com.tenderpulse.auth.AdminKeyAuthFilter
import com.tenderpulse.auth.BearerTokenService
import com.tenderpulse.domain.Subscriber
import com.tenderpulse.domain.SubscriberRepository
import com.tenderpulse.domain.SubscriptionTier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Full-context tests for TP-044's admin-key auth wiring: boots the real
 * [com.tenderpulse.auth.SecurityConfig] filter chain and [AdminKeyAuthFilter], unlike
 * [AdminControllerTest] (standalone MockMvc — security isn't in play there at all). This is the
 * only place that proves the admin-key filter and [com.tenderpulse.auth.SecurityConfig]'s
 * `hasAuthority` rule are actually wired together against the real admin routes,
 * mirroring [AuthIntegrationTest]'s role for TP-038's subscriber auth.
 *
 * The test admin key ("test-only-fixed-admin-key-used-for-jvm-test-runs-only") is fixed in
 * `src/test/resources/application.yml` — never a real secret.
 *
 * [JavaMailSender] is mocked purely so the context boots cleanly (same as [AuthIntegrationTest]);
 * this class never exercises the mail-sending path itself.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminApiSecurityIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var subscriberRepository: SubscriberRepository

    @Autowired
    private lateinit var bearerTokenService: BearerTokenService

    @MockitoBean
    private lateinit var javaMailSender: JavaMailSender

    private val validAdminKeyHeader = AdminKeyAuthFilter.ADMIN_KEY_HEADER to TEST_ADMIN_KEY

    // ---- Test case 1: no key -> 401/403, no data ----

    @Test
    fun `GET admin subscribers without an admin key is rejected and returns no data`() {
        subscriberRepository.save(Subscriber(email = "hidden@example.com"))

        val result = mockMvc.perform(get("/api/v1/admin/subscribers"))
            .andExpect(status().is4xxClientError)
            .andReturn()

        assert(!result.response.contentAsString.contains("hidden@example.com"))
    }

    @Test
    fun `POST admin aggregate without an admin key is rejected - retroactive protection`() {
        mockMvc.perform(post("/api/v1/admin/aggregate"))
            .andExpect(status().is4xxClientError)
    }

    @Test
    fun `PUT admin subscriber tier without an admin key is rejected and does not change the tier`() {
        val subscriber = subscriberRepository.save(Subscriber(email = "untouched@example.com", tier = SubscriptionTier.FREE))

        mockMvc.perform(
            put("/api/v1/admin/subscribers/${subscriber.id}/tier")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"tier":"PAID"}""")
        ).andExpect(status().is4xxClientError)

        val reloaded = subscriberRepository.findById(subscriber.id).orElseThrow()
        assertEquals(SubscriptionTier.FREE, reloaded.tier)
    }

    @Test
    fun `POST admin plan pricing without an admin key is rejected`() {
        mockMvc.perform(
            post("/api/v1/admin/plans/P-ANY/pricing")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"currencyCode":"USD","fixedPrice":9.99}""")
        ).andExpect(status().is4xxClientError)
    }

    @Test
    fun `a wrong admin key is rejected the same as a missing one`() {
        mockMvc.perform(get("/api/v1/admin/subscribers").header(AdminKeyAuthFilter.ADMIN_KEY_HEADER, "not-the-real-key"))
            .andExpect(status().is4xxClientError)
    }

    @Test
    fun `a subscriber's own bearer token does not grant access to admin routes`() {
        val subscriber = subscriberRepository.save(Subscriber(email = "not-an-admin@example.com"))
        val token = bearerTokenService.issue(subscriber.id)

        mockMvc.perform(get("/api/v1/admin/subscribers").header("Authorization", "Bearer $token"))
            .andExpect(status().is4xxClientError)
    }

    // ---- Test case 2: correct key -> succeeds ----

    @Test
    fun `GET admin subscribers with the correct admin key succeeds`() {
        mockMvc.perform(get("/api/v1/admin/subscribers").header(validAdminKeyHeader.first, validAdminKeyHeader.second))
            .andExpect(status().isOk)
    }

    @Test
    fun `POST admin aggregate with the correct admin key succeeds`() {
        mockMvc.perform(post("/api/v1/admin/aggregate").header(validAdminKeyHeader.first, validAdminKeyHeader.second))
            .andExpect(status().isOk)
    }

    // ---- Test case 3 & 4: list + override tier, end to end with the real DB ----

    @Test
    fun `listing subscribers with the admin key returns full tier and status, and a tier override is visible on the next list call`() {
        val subscriber = subscriberRepository.save(
            Subscriber(email = "override-me@example.com", tier = SubscriptionTier.FREE, active = true)
        )

        val listBefore = mockMvc.perform(
            get("/api/v1/admin/subscribers").header(validAdminKeyHeader.first, validAdminKeyHeader.second)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        assert(listBefore.contains("\"email\":\"override-me@example.com\""))
        assert(listBefore.contains("\"tier\":\"FREE\""))

        mockMvc.perform(
            put("/api/v1/admin/subscribers/${subscriber.id}/tier")
                .header(validAdminKeyHeader.first, validAdminKeyHeader.second)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"tier":"PAID"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.tier").value("PAID"))

        val listAfter = mockMvc.perform(
            get("/api/v1/admin/subscribers").header(validAdminKeyHeader.first, validAdminKeyHeader.second)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        assert(listAfter.contains("\"email\":\"override-me@example.com\""))
        assert(listAfter.contains("\"tier\":\"PAID\""))
    }

    companion object {
        private const val TEST_ADMIN_KEY = "test-only-fixed-admin-key-used-for-jvm-test-runs-only"
    }
}
