package com.tenderpulse.paypal

import com.tenderpulse.TenderPulseApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.BeanCreationException
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder

/**
 * Full-context regression for [PayPalPlanConfigGuard] (TP-127, issue #127), mirroring
 * [com.tenderpulse.auth.InsecureDefaultSecretGuardContextTest] -- proves the guard is actually
 * wired into a real application boot (not just correct in isolation against a mocked
 * [org.springframework.core.env.Environment], per [PayPalPlanConfigGuardTest]), and that a real
 * Spring context refresh genuinely aborts when it throws.
 *
 * `TENDERPULSE_AUTH_SECRET` is explicitly overridden to a non-blank value here so that
 * [com.tenderpulse.auth.InsecureDefaultSecretGuard] does not *also* fail context refresh for its
 * own, unrelated reason under the same non-dev/test profile -- this test needs to isolate
 * [PayPalPlanConfigGuard]'s own failure.
 */
class PayPalPlanConfigGuardContextTest {

    @Test
    fun `real application context refresh fails when a plan id is blank and no dev-or-test profile is active`() {
        val app = SpringApplicationBuilder(TenderPulseApplication::class.java)
            .web(WebApplicationType.NONE)

        val exception = assertThrows(BeanCreationException::class.java) {
            app.run(
                "--spring.profiles.active=staging",
                "--TENDERPULSE_AUTH_SECRET=a-real-non-default-secret-for-this-test-32b",
                "--paypal.plans.pro=",
                "--paypal.plans.max="
            )
        }

        val rootCause = generateSequence<Throwable>(exception) { it.cause }
            .filterIsInstance<IllegalStateException>()
            .firstOrNull()

        assertNotNull(rootCause, "expected an IllegalStateException from PayPalPlanConfigGuard in the cause chain of $exception")
        assertTrue(
            rootCause!!.message!!.contains("Refusing to start"),
            "unexpected IllegalStateException message: ${rootCause.message}"
        )
        assertEquals("staging", rootCause.message!!.let {
            Regex("active profiles: \\[(.*?)]").find(it)?.groupValues?.get(1)
        })
    }
}
