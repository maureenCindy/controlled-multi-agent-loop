package com.tenderpulse.auth

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.core.env.Environment

/**
 * Unit tests for [InsecureDefaultSecretGuard] (TP-038 Reviewer follow-up on PR #63): the
 * checked-in `tenderpulse.auth.token-secret` fallback must not be usable outside a `dev`/`test`
 * Spring profile.
 */
class InsecureDefaultSecretGuardTest {

    private val environment = mockk<Environment>()
    private val guard = InsecureDefaultSecretGuard(environment)

    @Test
    fun `does not throw when TENDERPULSE_AUTH_SECRET is explicitly set, regardless of profile`() {
        every { environment.getProperty("TENDERPULSE_AUTH_SECRET") } returns "a-real-private-secret"
        every { environment.activeProfiles } returns arrayOf("prod")

        assertDoesNotThrow { guard.checkTokenSecretIsOverridden() }
    }

    @Test
    fun `does not throw when the secret is unset but the active profile is dev`() {
        every { environment.getProperty("TENDERPULSE_AUTH_SECRET") } returns null
        every { environment.activeProfiles } returns arrayOf("dev")

        assertDoesNotThrow { guard.checkTokenSecretIsOverridden() }
    }

    @Test
    fun `does not throw when the secret is unset but the active profile is test`() {
        every { environment.getProperty("TENDERPULSE_AUTH_SECRET") } returns null
        every { environment.activeProfiles } returns arrayOf("test")

        assertDoesNotThrow { guard.checkTokenSecretIsOverridden() }
    }

    @Test
    fun `throws when the secret is unset and no dev-or-test profile is active`() {
        every { environment.getProperty("TENDERPULSE_AUTH_SECRET") } returns null
        every { environment.activeProfiles } returns arrayOf("prod")

        assertThrows(IllegalStateException::class.java) { guard.checkTokenSecretIsOverridden() }
    }

    @Test
    fun `throws when the secret is unset and there are no active profiles at all`() {
        every { environment.getProperty("TENDERPULSE_AUTH_SECRET") } returns null
        every { environment.activeProfiles } returns emptyArray()

        assertThrows(IllegalStateException::class.java) { guard.checkTokenSecretIsOverridden() }
    }

    @Test
    fun `a blank (whitespace-only) secret env var counts as not set`() {
        every { environment.getProperty("TENDERPULSE_AUTH_SECRET") } returns "   "
        every { environment.activeProfiles } returns arrayOf("prod")

        assertThrows(IllegalStateException::class.java) { guard.checkTokenSecretIsOverridden() }
    }
}
