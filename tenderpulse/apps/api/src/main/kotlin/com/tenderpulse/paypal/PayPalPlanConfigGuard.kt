package com.tenderpulse.paypal

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * Startup guard for `paypal.plans.pro` / `paypal.plans.max` (TP-127, issue #127), mirroring
 * [com.tenderpulse.auth.InsecureDefaultSecretGuard]'s pattern for
 * `tenderpulse.auth.token-secret`.
 *
 * `application.yml`'s empty defaults (`${PAYPAL_PRO_PLAN_ID:}` / `${PAYPAL_MAX_PLAN_ID:}`) let the
 * app boot without them so local dev/test never needs real PayPal credentials just to start up —
 * but a real (non-dev/test) deployment that forgets to set one is silently unable to ever grant
 * the corresponding paid entitlement:
 * [com.tenderpulse.billing.BillingService.confirmSubscription] compares PayPal's own `plan_id`
 * against this blank string, so it can never match, and every confirm attempt for that plan is
 * rejected with a generic [com.tenderpulse.domain.SubscriptionVerificationException] that looks
 * identical to a genuinely wrong PayPal plan id — a support/operability trap, not a security hole,
 * but one worth failing loudly and early over rather than discovering via a stream of confused
 * customer confirm failures.
 *
 * Same two-part behavior as [com.tenderpulse.auth.InsecureDefaultSecretGuard]:
 * 1. Always logs a loud ERROR the moment either plan id is blank, regardless of environment.
 * 2. Refuses to finish starting (throws, failing context refresh) unless the active Spring
 *    profile is explicitly `dev` or `test`.
 */
@Component
class PayPalPlanConfigGuard(
    @Value("\${paypal.plans.pro:}") private val proPlanId: String,
    @Value("\${paypal.plans.max:}") private val maxPlanId: String,
    private val environment: Environment
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun checkPlanIdsAreConfigured() {
        val missing = buildList {
            if (proPlanId.isBlank()) add("PAYPAL_PRO_PLAN_ID (paypal.plans.pro)")
            if (maxPlanId.isBlank()) add("PAYPAL_MAX_PLAN_ID (paypal.plans.max)")
        }
        if (missing.isEmpty()) return

        log.error(
            "*** CONFIG WARNING *** {} not set -- POST /api/v1/billing/paypal/subscriptions/confirm " +
                "(and, for Pro, POST /api/v1/subscribers/pro) will reject every confirmation for the " +
                "affected plan(s) until this instance's PayPal plan id configuration is corrected.",
            missing.joinToString(", ")
        )

        val activeProfiles = environment.activeProfiles.toSet()
        if (activeProfiles.none { it in ALLOWED_PROFILES_FOR_BLANK_PLAN_IDS }) {
            throw IllegalStateException(
                "Refusing to start: ${missing.joinToString(", ")} is not set and none of " +
                    "$ALLOWED_PROFILES_FOR_BLANK_PLAN_IDS is an active Spring profile " +
                    "(active profiles: $activeProfiles). Set PAYPAL_PRO_PLAN_ID and " +
                    "PAYPAL_MAX_PLAN_ID, or explicitly run with SPRING_PROFILES_ACTIVE=dev if this " +
                    "really is a local/dev instance."
            )
        }
    }

    companion object {
        private val ALLOWED_PROFILES_FOR_BLANK_PLAN_IDS = setOf("dev", "test")
    }
}
