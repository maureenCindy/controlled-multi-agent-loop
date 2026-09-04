package com.tenderpulse.auth

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * Startup guard for `tenderpulse.auth.token-secret` (TP-038 Reviewer follow-up on PR #63).
 *
 * The checked-in fallback in `application.yml` (`TENDERPULSE_AUTH_SECRET:dev-only-insecure-...`)
 * is public — anyone who's read this repo can forge a bearer access token for any subscriber if
 * an instance is ever run with it unchanged. This component:
 *
 * 1. Always logs a loud, impossible-to-miss ERROR the moment that fallback is in effect (i.e.
 *    `TENDERPULSE_AUTH_SECRET` was never actually set), regardless of environment.
 * 2. Refuses to finish starting (throws, failing context refresh) unless the active Spring
 *    profile is explicitly `dev` or `test` — see `spring.profiles.active` in both
 *    `application.yml` (defaults to `dev`, so local `bootRun` keeps working with zero config)
 *    and `src/test/resources/application.yml` (`test`).
 *
 * Checking "was `TENDERPULSE_AUTH_SECRET` provided" directly (rather than string-comparing the
 * resolved secret against a literal copy of the default) avoids the two config files silently
 * drifting out of sync with this class.
 */
@Component
class InsecureDefaultSecretGuard(private val environment: Environment) {

    private val log = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun checkTokenSecretIsOverridden() {
        val explicitlySet = !environment.getProperty(SECRET_ENV_VAR).isNullOrBlank()
        if (explicitlySet) return

        log.error(
            "*** SECURITY WARNING *** {} is not set, so tenderpulse.auth.token-secret is still " +
                "the checked-in dev-only default from application.yml. Anyone who has read this " +
                "repo can forge a valid bearer access token for ANY subscriber against an " +
                "instance running with this secret. Set {} to a real, private value before this " +
                "instance is reachable by anyone other than you.",
            SECRET_ENV_VAR,
            SECRET_ENV_VAR
        )

        val activeProfiles = environment.activeProfiles.toSet()
        if (activeProfiles.none { it in ALLOWED_PROFILES_FOR_DEFAULT_SECRET }) {
            throw IllegalStateException(
                "Refusing to start: $SECRET_ENV_VAR is not set (tenderpulse.auth.token-secret " +
                    "would fall back to the insecure, checked-in default) and none of " +
                    "$ALLOWED_PROFILES_FOR_DEFAULT_SECRET is an active Spring profile " +
                    "(active profiles: $activeProfiles). Set $SECRET_ENV_VAR, or explicitly run " +
                    "with SPRING_PROFILES_ACTIVE=dev if this really is a local/dev instance."
            )
        }
    }

    companion object {
        private const val SECRET_ENV_VAR = "TENDERPULSE_AUTH_SECRET"
        private val ALLOWED_PROFILES_FOR_DEFAULT_SECRET = setOf("dev", "test")
    }
}
