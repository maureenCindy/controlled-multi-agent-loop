package com.tenderpulse.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * TP-121 (issue #121): direct coverage for the AC "SubscriptionPlan enum exists with FREE, PRO,
 * MAX (no generic PAID remaining anywhere in the codebase)" and "BillingStatus and AlertStatus
 * enums exist per spec §6.2/§6.3" -- asserts the exact value sets (and order, per
 * `tenderpulse/docs/specs/subscription-lifecycle-paypal.md` §6) rather than relying solely on
 * "it compiles".
 */
class SubscriptionModelEnumsTest {

    @Test
    fun `SubscriptionPlan has exactly FREE, PRO, MAX`() {
        assertEquals(
            listOf(SubscriptionPlan.FREE, SubscriptionPlan.PRO, SubscriptionPlan.MAX),
            SubscriptionPlan.entries
        )
    }

    @Test
    fun `BillingStatus has exactly the spec §6_2 values in order`() {
        assertEquals(
            listOf(
                BillingStatus.NONE,
                BillingStatus.APPROVAL_PENDING,
                BillingStatus.ACTIVE,
                BillingStatus.PAYMENT_FAILED,
                BillingStatus.SUSPENDED,
                BillingStatus.CANCELLATION_PENDING,
                BillingStatus.CANCELLED,
                BillingStatus.EXPIRED
            ),
            BillingStatus.entries
        )
    }

    @Test
    fun `AlertStatus has exactly ACTIVE, PAUSED, UNSUBSCRIBED`() {
        assertEquals(
            listOf(AlertStatus.ACTIVE, AlertStatus.PAUSED, AlertStatus.UNSUBSCRIBED),
            AlertStatus.entries
        )
    }
}
