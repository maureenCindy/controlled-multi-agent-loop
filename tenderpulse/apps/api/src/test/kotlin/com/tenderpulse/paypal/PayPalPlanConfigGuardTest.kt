package com.tenderpulse.paypal

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.core.env.Environment

/**
 * Unit tests for [PayPalPlanConfigGuard] (TP-127, issue #127): fails closed in non-dev/test
 * profiles when either `paypal.plans.pro` or `paypal.plans.max` is blank, mirroring
 * [com.tenderpulse.auth.InsecureDefaultSecretGuardTest]'s coverage shape for its equivalent guard.
 */
class PayPalPlanConfigGuardTest {

    private val environment = mockk<Environment>()

    private fun guard(proPlanId: String, maxPlanId: String) =
        PayPalPlanConfigGuard(proPlanId = proPlanId, maxPlanId = maxPlanId, environment = environment)

    @Test
    fun `does not throw when both plan ids are set, regardless of profile`() {
        every { environment.activeProfiles } returns arrayOf("prod")

        assertDoesNotThrow { guard(proPlanId = "P-PRO-REAL", maxPlanId = "P-MAX-REAL").checkPlanIdsAreConfigured() }
    }

    @Test
    fun `does not throw when both plan ids are blank but the active profile is dev`() {
        every { environment.activeProfiles } returns arrayOf("dev")

        assertDoesNotThrow { guard(proPlanId = "", maxPlanId = "").checkPlanIdsAreConfigured() }
    }

    @Test
    fun `does not throw when both plan ids are blank but the active profile is test`() {
        every { environment.activeProfiles } returns arrayOf("test")

        assertDoesNotThrow { guard(proPlanId = "", maxPlanId = "").checkPlanIdsAreConfigured() }
    }

    @Test
    fun `throws when the pro plan id is blank and no dev-or-test profile is active`() {
        every { environment.activeProfiles } returns arrayOf("prod")

        assertThrows(IllegalStateException::class.java) {
            guard(proPlanId = "", maxPlanId = "P-MAX-REAL").checkPlanIdsAreConfigured()
        }
    }

    @Test
    fun `throws when the max plan id is blank and no dev-or-test profile is active`() {
        every { environment.activeProfiles } returns arrayOf("prod")

        assertThrows(IllegalStateException::class.java) {
            guard(proPlanId = "P-PRO-REAL", maxPlanId = "").checkPlanIdsAreConfigured()
        }
    }

    @Test
    fun `throws when both plan ids are blank and no dev-or-test profile is active`() {
        every { environment.activeProfiles } returns arrayOf("prod")

        assertThrows(IllegalStateException::class.java) {
            guard(proPlanId = "", maxPlanId = "").checkPlanIdsAreConfigured()
        }
    }

    @Test
    fun `throws when a plan id is blank and there are no active profiles at all`() {
        every { environment.activeProfiles } returns emptyArray()

        assertThrows(IllegalStateException::class.java) {
            guard(proPlanId = "", maxPlanId = "").checkPlanIdsAreConfigured()
        }
    }

    @Test
    fun `a whitespace-only plan id counts as blank`() {
        every { environment.activeProfiles } returns arrayOf("prod")

        assertThrows(IllegalStateException::class.java) {
            guard(proPlanId = "   ", maxPlanId = "P-MAX-REAL").checkPlanIdsAreConfigured()
        }
    }
}
