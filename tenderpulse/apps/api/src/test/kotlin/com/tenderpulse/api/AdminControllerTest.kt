package com.tenderpulse.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.tenderpulse.admin.AdminService
import com.tenderpulse.admin.AdminPlanPricingRequest
import com.tenderpulse.aggregation.AggregationResult
import com.tenderpulse.aggregation.AggregationService
import com.tenderpulse.domain.NotFoundException
import com.tenderpulse.domain.PayPalApiException
import com.tenderpulse.domain.PayPalPlanPricingException
import com.tenderpulse.domain.Subscriber
import com.tenderpulse.domain.SubscriptionTier
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.math.BigDecimal
import java.util.UUID

/**
 * Standalone MockMvc tests for [AdminController] (TP-044): [AdminService] and
 * [AggregationService] are mockk mocks, no Spring context/security filter chain is booted (that
 * is covered separately by [AdminApiSecurityIntegrationTest]) — this class exercises only the
 * controller's own responsibilities (request validation, delegation, HTTP status/DTO mapping),
 * mirroring [SubscriberControllerTest]'s pattern.
 */
class AdminControllerTest {

    private val aggregationService = mockk<AggregationService>()
    private val adminService = mockk<AdminService>()
    private val objectMapper: ObjectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        val controller = AdminController(aggregationService, adminService)
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setMessageConverters(MappingJackson2HttpMessageConverter(objectMapper))
            .build()
    }

    // ---- aggregate (pre-existing, unchanged behavior) ----

    @Test
    fun `aggregate delegates to AggregationService`() {
        every { aggregationService.runAggregationCycle() } returns
            AggregationResult(fetched = 0, stored = 0, notificationsSent = 0)

        mockMvc.perform(post("/api/v1/admin/aggregate")).andExpect(status().isOk)

        verify(exactly = 1) { aggregationService.runAggregationCycle() }
    }

    // ---- listSubscribers (test case 3) ----

    @Test
    fun `listSubscribers returns tier and status for every subscriber`() {
        val paid = Subscriber(email = "paid@example.com", tier = SubscriptionTier.PAID, active = true)
        val free = Subscriber(email = "free@example.com", tier = SubscriptionTier.FREE, active = false)
        every { adminService.listSubscribers(0, 20) } returns
            PageImpl(listOf(paid, free), PageRequest.of(0, 20), 2)

        mockMvc.perform(get("/api/v1/admin/subscribers"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(2))
            .andExpect(jsonPath("$.content[0].email").value("paid@example.com"))
            .andExpect(jsonPath("$.content[0].tier").value("PAID"))
            .andExpect(jsonPath("$.content[0].status").value("ACTIVE"))
            .andExpect(jsonPath("$.content[1].email").value("free@example.com"))
            .andExpect(jsonPath("$.content[1].tier").value("FREE"))
            .andExpect(jsonPath("$.content[1].status").value("INACTIVE"))
            .andExpect(jsonPath("$.totalElements").value(2))
    }

    @Test
    fun `listSubscribers honors page and size query params`() {
        every { adminService.listSubscribers(1, 5) } returns PageImpl(emptyList(), PageRequest.of(1, 5), 0)

        mockMvc.perform(get("/api/v1/admin/subscribers").param("page", "1").param("size", "5"))
            .andExpect(status().isOk)

        verify(exactly = 1) { adminService.listSubscribers(1, 5) }
    }

    // ---- updateSubscriberTier (test case 4) ----

    @Test
    fun `updateSubscriberTier returns the updated subscriber DTO`() {
        val id = UUID.randomUUID()
        every { adminService.updateSubscriberTier(id, SubscriptionTier.PAID) } returns
            Subscriber(id = id, email = "sub@example.com", tier = SubscriptionTier.PAID)

        mockMvc.perform(
            put("/api/v1/admin/subscribers/$id/tier")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"tier":"PAID"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.tier").value("PAID"))
            .andExpect(jsonPath("$.id").value(id.toString()))
    }

    @Test
    fun `updateSubscriberTier for an unknown subscriber returns 404`() {
        val id = UUID.randomUUID()
        every { adminService.updateSubscriberTier(id, SubscriptionTier.PAID) } throws
            NotFoundException("Subscriber $id")

        mockMvc.perform(
            put("/api/v1/admin/subscribers/$id/tier")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"tier":"PAID"}""")
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `updateSubscriberTier with a missing tier returns 400 and never calls the service`() {
        val id = UUID.randomUUID()

        mockMvc.perform(
            put("/api/v1/admin/subscribers/$id/tier")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{}""")
        ).andExpect(status().isBadRequest)

        verify(exactly = 0) { adminService.updateSubscriberTier(any(), any()) }
    }

    // ---- updatePlanPricing (test cases 5 & 6) ----

    @Test
    fun `updatePlanPricing with valid input returns a clear success result`() {
        every {
            adminService.updatePlanPricing("P-VALIDPLAN", AdminPlanPricingRequest(currencyCode = "USD", fixedPrice = BigDecimal("19.99")))
        } returns com.tenderpulse.admin.AdminPlanPricingResponse(
            planId = "P-VALIDPLAN",
            currencyCode = "USD",
            fixedPrice = BigDecimal("19.99"),
            billingCycleSequence = 1
        )

        mockMvc.perform(
            post("/api/v1/admin/plans/P-VALIDPLAN/pricing")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"currencyCode":"USD","fixedPrice":19.99}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.planId").value("P-VALIDPLAN"))
            .andExpect(jsonPath("$.success").value(true))
    }

    @Test
    fun `updatePlanPricing for an invalid or nonexistent plan id returns 400`() {
        every {
            adminService.updatePlanPricing("P-FAKE", any())
        } throws PayPalPlanPricingException("PayPal rejected the pricing update for plan 'P-FAKE'")

        mockMvc.perform(
            post("/api/v1/admin/plans/P-FAKE/pricing")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"currencyCode":"USD","fixedPrice":19.99}""")
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `updatePlanPricing when PayPal itself fails returns 502`() {
        every {
            adminService.updatePlanPricing("P-VALIDPLAN", any())
        } throws PayPalApiException("PayPal timed out")

        mockMvc.perform(
            post("/api/v1/admin/plans/P-VALIDPLAN/pricing")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"currencyCode":"USD","fixedPrice":19.99}""")
        ).andExpect(status().isBadGateway)
    }

    @Test
    fun `updatePlanPricing with a blank currency code returns 400 and never calls the service`() {
        mockMvc.perform(
            post("/api/v1/admin/plans/P-VALIDPLAN/pricing")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"currencyCode":"","fixedPrice":19.99}""")
        ).andExpect(status().isBadRequest)

        verify(exactly = 0) { adminService.updatePlanPricing(any(), any()) }
    }

    @Test
    fun `updatePlanPricing with a non-positive fixed price returns 400 and never calls the service`() {
        mockMvc.perform(
            post("/api/v1/admin/plans/P-VALIDPLAN/pricing")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"currencyCode":"USD","fixedPrice":0}""")
        ).andExpect(status().isBadRequest)

        verify(exactly = 0) { adminService.updatePlanPricing(any(), any()) }
    }

    // ---- planId validation (issue #68: planId flowed unvalidated into the PayPal request URL) ----

    @Test
    fun `updatePlanPricing with a planId containing a slash returns 400 and never calls the service`() {
        mockMvc.perform(
            post("/api/v1/admin/plans/P-VALID%2FEVIL/pricing")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"currencyCode":"USD","fixedPrice":19.99}""")
        ).andExpect(status().isBadRequest)

        verify(exactly = 0) { adminService.updatePlanPricing(any(), any()) }
    }

    @Test
    fun `updatePlanPricing with a planId containing dot-dot returns 400 and never calls the service`() {
        mockMvc.perform(
            post("/api/v1/admin/plans/P-FAKE..EVIL/pricing")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"currencyCode":"USD","fixedPrice":19.99}""")
        ).andExpect(status().isBadRequest)

        verify(exactly = 0) { adminService.updatePlanPricing(any(), any()) }
    }

    @Test
    fun `updatePlanPricing with a planId containing a question mark returns 400 and never calls the service`() {
        mockMvc.perform(
            post("/api/v1/admin/plans/P-FAKE%3FEVIL/pricing")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"currencyCode":"USD","fixedPrice":19.99}""")
        ).andExpect(status().isBadRequest)

        verify(exactly = 0) { adminService.updatePlanPricing(any(), any()) }
    }
}
