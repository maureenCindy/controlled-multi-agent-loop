package com.tenderpulse.admin

import com.tenderpulse.domain.NotFoundException
import com.tenderpulse.domain.PayPalApiException
import com.tenderpulse.domain.PayPalPlanPricingException
import com.tenderpulse.domain.Subscriber
import com.tenderpulse.domain.SubscriberRepository
import com.tenderpulse.domain.SubscriptionTier
import com.tenderpulse.paypal.PayPalClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import java.math.BigDecimal
import java.util.Optional
import java.util.UUID

/**
 * Unit tests for [AdminService] (TP-044). Repositories/[PayPalClient] are mockk mocks — no live
 * PayPal calls, same principle as [com.tenderpulse.paypal.PayPalClientTest] and
 * [com.tenderpulse.subscriber.SubscriberServiceTest].
 */
class AdminServiceTest {

    private val subscriberRepository = mockk<SubscriberRepository>()
    private val payPalClient = mockk<PayPalClient>()
    private val service = AdminService(subscriberRepository, payPalClient)

    // ---- listSubscribers (test case 3) ----

    @Test
    fun `listSubscribers returns the full page with tier and status for every subscriber`() {
        val active = Subscriber(email = "active@example.com", tier = SubscriptionTier.PAID, active = true)
        val inactive = Subscriber(email = "inactive@example.com", tier = SubscriptionTier.FREE, active = false)
        val pageableSlot = slot<Pageable>()
        every { subscriberRepository.findAll(capture(pageableSlot)) } returns
            PageImpl(listOf(active, inactive), PageRequest.of(0, 20), 2)

        val result = service.listSubscribers(0, 20)

        assertEquals(2, result.totalElements)
        assertEquals(listOf(active, inactive), result.content)
        assertEquals(0, pageableSlot.captured.pageNumber)
        assertEquals(20, pageableSlot.captured.pageSize)
    }

    @Test
    fun `listSubscribers passes through the requested page and size`() {
        every { subscriberRepository.findAll(any<Pageable>()) } returns
            PageImpl(emptyList(), PageRequest.of(2, 5), 0)

        service.listSubscribers(2, 5)

        verify {
            subscriberRepository.findAll(match<Pageable> { it.pageNumber == 2 && it.pageSize == 5 })
        }
    }

    // ---- updateSubscriberTier (test case 4) ----

    @Test
    fun `updateSubscriberTier persists the new tier and returns the updated subscriber`() {
        val id = UUID.randomUUID()
        val existing = Subscriber(id = id, email = "sub@example.com", tier = SubscriptionTier.FREE)
        every { subscriberRepository.findById(id) } returns Optional.of(existing)
        val saved = slot<Subscriber>()
        every { subscriberRepository.save(capture(saved)) } answers { saved.captured }

        val result = service.updateSubscriberTier(id, SubscriptionTier.PAID)

        assertEquals(SubscriptionTier.PAID, result.tier)
        assertEquals(id, result.id)
        assertEquals(SubscriptionTier.PAID, saved.captured.tier)
    }

    @Test
    fun `updateSubscriberTier for an unknown subscriber throws NotFoundException and saves nothing`() {
        val id = UUID.randomUUID()
        every { subscriberRepository.findById(id) } returns Optional.empty()

        assertThrows(NotFoundException::class.java) {
            service.updateSubscriberTier(id, SubscriptionTier.PAID)
        }
        verify(exactly = 0) { subscriberRepository.save(any()) }
    }

    @Test
    fun `updateSubscriberTier does not call PayPal - it is a deliberate bypass`() {
        val id = UUID.randomUUID()
        val existing = Subscriber(id = id, email = "sub@example.com", tier = SubscriptionTier.FREE)
        every { subscriberRepository.findById(id) } returns Optional.of(existing)
        every { subscriberRepository.save(any()) } answers { firstArg() }

        service.updateSubscriberTier(id, SubscriptionTier.PAID)

        verify(exactly = 0) { payPalClient.fetchSubscription(any()) }
    }

    // ---- updatePlanPricing (test cases 5 & 6) ----

    @Test
    fun `updatePlanPricing with valid input calls PayPalClient and returns a confirming result`() {
        every {
            payPalClient.updatePlanPricing("P-VALIDPLAN", "USD", BigDecimal("19.99"), 1)
        } returns Unit

        val result = service.updatePlanPricing(
            "P-VALIDPLAN",
            AdminPlanPricingRequest(currencyCode = "USD", fixedPrice = BigDecimal("19.99"))
        )

        assertEquals("P-VALIDPLAN", result.planId)
        assertEquals("USD", result.currencyCode)
        assertEquals(BigDecimal("19.99"), result.fixedPrice)
        assertEquals(true, result.success)
    }

    @Test
    fun `updatePlanPricing for an invalid or nonexistent plan id propagates PayPalPlanPricingException`() {
        every {
            payPalClient.updatePlanPricing("P-FAKE", "USD", BigDecimal("10.00"), 1)
        } throws PayPalPlanPricingException("PayPal rejected the pricing update for plan 'P-FAKE'")

        assertThrows(PayPalPlanPricingException::class.java) {
            service.updatePlanPricing("P-FAKE", AdminPlanPricingRequest(currencyCode = "USD", fixedPrice = BigDecimal("10.00")))
        }
    }

    @Test
    fun `updatePlanPricing propagates a PayPal API failure`() {
        every {
            payPalClient.updatePlanPricing("P-VALIDPLAN", "USD", BigDecimal("10.00"), 1)
        } throws PayPalApiException("PayPal timed out")

        assertThrows(PayPalApiException::class.java) {
            service.updatePlanPricing("P-VALIDPLAN", AdminPlanPricingRequest(currencyCode = "USD", fixedPrice = BigDecimal("10.00")))
        }
    }
}
