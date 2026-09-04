package com.tenderpulse.auth

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
 * TP-065: full-context regression for [InsecureDefaultSecretGuard], complementing
 * [InsecureDefaultSecretGuardTest] (which only exercises [InsecureDefaultSecretGuard] directly
 * against a mocked [org.springframework.core.env.Environment]).
 *
 * That unit test proves the guard's own logic is correct in isolation, but not that it is
 * actually wired up to run during a real application boot, or that a real Spring context refresh
 * genuinely aborts when it throws (as opposed to, say, the exception being swallowed by some
 * other `@PostConstruct`/bean-lifecycle interaction). This test boots the real
 * [TenderPulseApplication] -- the same class `main()` uses, full auto-configuration, a real
 * (non-mocked) [org.springframework.core.env.Environment] -- with `TENDERPULSE_AUTH_SECRET`
 * explicitly unset and a Spring profile active that is neither `dev` nor `test`, and asserts
 * context refresh fails with the guard's own [IllegalStateException] in the cause chain.
 *
 * Deliberately does *not* use the `@SpringBootTest` class-level annotation: that mechanism starts
 * the context in a JUnit lifecycle callback before the `@Test` method runs, so a startup failure
 * surfaces as a raw JUnit "initialization error" rather than something this test can assert on
 * cleanly. Building and running the [SpringApplicationBuilder] directly inside the test body
 * achieves the same "boot a real Spring context" outcome while still letting the failure be
 * asserted with `assertThrows`, same as [InsecureDefaultSecretGuardTest]'s style.
 *
 * `web(WebApplicationType.NONE)` only skips standing up an embedded servlet container (not
 * relevant to what's being proven here, and avoids a port clash with other tests); the datasource
 * (H2, from `src/test/resources/application.yml`, which -- being present on the test classpath --
 * shadows `src/main/resources/application.yml` here exactly as it does for every other test, see
 * that file's header comment) and every other bean, including [InsecureDefaultSecretGuard]
 * itself, are the real production beans.
 */
class InsecureDefaultSecretGuardContextTest {

    @Test
    fun `real application context refresh fails when the auth secret is unset and no dev-or-test profile is active`() {
        val app = SpringApplicationBuilder(TenderPulseApplication::class.java)
            .web(WebApplicationType.NONE)

        // Command-line-style args take the highest precedence in Spring Boot's property
        // resolution, so these reliably override src/test/resources/application.yml's
        // `spring.profiles.active: test` -- unlike SpringApplication#setDefaultProperties, whose
        // values have the *lowest* precedence and would silently be ignored here.
        val exception = assertThrows(BeanCreationException::class.java) {
            app.run(
                "--spring.profiles.active=staging",
                "--TENDERPULSE_AUTH_SECRET="
            )
        }

        val rootCause = generateSequence<Throwable>(exception) { it.cause }
            .filterIsInstance<IllegalStateException>()
            .firstOrNull()

        assertNotNull(rootCause, "expected an IllegalStateException from InsecureDefaultSecretGuard in the cause chain of $exception")
        assertTrue(
            rootCause!!.message!!.contains("Refusing to start"),
            "unexpected IllegalStateException message: ${rootCause.message}"
        )
        assertEquals("staging", rootCause.message!!.let {
            Regex("active profiles: \\[(.*?)]").find(it)?.groupValues?.get(1)
        })
    }
}
