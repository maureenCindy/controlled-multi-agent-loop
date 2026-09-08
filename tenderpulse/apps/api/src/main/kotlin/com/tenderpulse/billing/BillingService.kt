package com.tenderpulse.billing

import com.tenderpulse.domain.ConflictException
import com.tenderpulse.domain.NotFoundException
import com.tenderpulse.domain.Subscriber
import com.tenderpulse.domain.SubscriberRepository
import com.tenderpulse.domain.SubscriptionPlan
import com.tenderpulse.domain.SubscriptionVerificationException
import com.tenderpulse.paypal.PayPalClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Business logic for the unified PayPal billing endpoints (TP-127, issue #127) -- generalizes
 * [com.tenderpulse.subscriber.SubscriberService.registerPro] (TP-042) to cover both Pro and Max,
 * distinguished by [com.tenderpulse.billing.RequestedBillingPlan]. The Pro-only
 * `POST /api/v1/subscribers/pro` flow is left in place unchanged (see that method's kdoc) -- this
 * is a new, additional path, not a replacement of it yet.
 *
 * Owns the `paypal.*` configuration this task added (`plans.pro`/`plans.max`,
 * `client-id`/`currency`/`amounts.*` for the public bootstrap config) so [BillingController] stays
 * thin, same convention as every other controller/service pair in this codebase.
 */
@Service
class BillingService(
    private val subscriberRepository: SubscriberRepository,
    private val payPalClient: PayPalClient,
    @Value("\${paypal.client-id:}") private val publicClientId: String,
    @Value("\${paypal.currency:USD}") private val currency: String,
    @Value("\${paypal.plans.pro:}") private val proPlanId: String,
    @Value("\${paypal.plans.max:}") private val maxPlanId: String,
    @Value("\${paypal.amounts.pro:5.00}") private val proAmount: String,
    @Value("\${paypal.amounts.max:30.00}") private val maxAmount: String
) {

    /**
     * `GET /api/v1/billing/public-config` (spec §10.1). Display/bootstrap only -- see
     * [PublicBillingConfigResponse]'s kdoc for why this is never trusted for an entitlement
     * decision. Deliberately returns only `client-id` (meant to reach the browser, per spec §8.1)
     * and never `client-secret`.
     */
    fun publicConfig(): PublicBillingConfigResponse = PublicBillingConfigResponse(
        clientId = publicClientId,
        currency = currency,
        plans = mapOf(
            "PRO" to PlanConfig(paypalPlanId = proPlanId, amount = proAmount),
            "MAX" to PlanConfig(paypalPlanId = maxPlanId, amount = maxAmount)
        )
    )

    /**
     * Verifies a PayPal subscription server-side (spec §10.2) and, only on success, upgrades
     * [subscriberId]'s own [Subscriber] record to the requested paid tier.
     *
     * [subscriberId] comes from the caller's own bearer token (resolved by
     * [BillingController.confirmSubscription] from the authenticated
     * [org.springframework.security.core.context.SecurityContext], never from the request body) --
     * this is deliberately how subscriber ownership is established here, per spec §10.2 ("verify
     * subscriber ownership without relying solely on matching email addresses"). Unlike
     * [com.tenderpulse.subscriber.SubscriberService.registerPro] (a public, unauthenticated
     * endpoint predating subscriber auth, TP-038), this endpoint requires the caller to already be
     * signed in as a specific subscriber, so there is no email to match in the first place -- the
     * token itself is the ownership proof.
     *
     * Mirrors [com.tenderpulse.subscriber.SubscriberService.registerPro]'s verification order and
     * reasoning:
     * - fetches the subscription from PayPal by ID (never trusts the caller's claim that checkout
     *   succeeded);
     * - requires its `plan_id` to match the *configured* plan id for [ConfirmSubscriptionRequest.requestedPlan]
     *   (never a client-supplied plan id or price);
     * - requires its `status` to be `ACTIVE`;
     * - requires it isn't already linked to a *different* subscriber (the same
     *   [SubscriberRepository.findByPaypalSubscriptionId] uniqueness check `registerPro` uses) --
     *   this is also what makes a retry with the same subscription id idempotent: if it's already
     *   linked to *this* [subscriberId] (e.g. a duplicate `onApprove` callback, or a client retry
     *   after a dropped response), the save below simply re-persists the same
     *   plan/subscription-id pair rather than being rejected as a conflict.
     *
     * Guards against a silent plan-change double-billing/orphan-subscription outcome (issue #130,
     * raised by the #129 Reviewer): if [subscriberId] already has a non-FREE [Subscriber.tier] tied
     * to an existing [Subscriber.paypalSubscriptionId], a confirm for a *different* PayPal
     * subscription id is rejected outright rather than silently overwriting that field -- doing so
     * would upgrade/change the subscriber's tier while leaving their old, still-`ACTIVE`,
     * still-billing PayPal subscription with no local trace and no cancellation call. This is a
     * minimal safety guard, not the real PayPal subscription *revision* flow (spec §14.1/§14.2,
     * which needs Phase 3 webhooks/reconciliation) -- a blocked subscriber is expected to contact
     * support for a manual plan change until that lands. A retry with the *same* subscription id
     * (e.g. a duplicate `onApprove` callback) is explicitly exempted so idempotency is preserved.
     *
     * @throws NotFoundException if [subscriberId] doesn't correspond to a real subscriber (should
     *   not happen for a validly issued bearer token, but the record could have been removed since).
     * @throws com.tenderpulse.domain.ConflictException if [subscriberId] already has an active,
     *   different PayPal subscription linked (a plan change) -- no subscriber is changed.
     * @throws SubscriptionVerificationException if the subscription doesn't exist, is for the
     *   wrong plan, isn't ACTIVE, or is already linked to a different subscriber -- no subscriber
     *   is changed in any of those cases.
     * @throws com.tenderpulse.domain.PayPalApiException if the call to PayPal itself fails.
     */
    fun confirmSubscription(subscriberId: UUID, req: ConfirmSubscriptionRequest): Subscriber {
        val subscriber = subscriberRepository.findById(subscriberId)
            .orElseThrow { NotFoundException("Subscriber $subscriberId") }

        val currentSubscriptionId = subscriber.paypalSubscriptionId
        if (subscriber.tier != SubscriptionPlan.FREE &&
            currentSubscriptionId != null &&
            currentSubscriptionId != req.paypalSubscriptionId
        ) {
            throw ConflictException(
                "Subscriber $subscriberId already has an active ${subscriber.tier} subscription " +
                    "(PayPal subscription '$currentSubscriptionId'); plan changes aren't supported " +
                    "yet -- contact support."
            )
        }

        val expectedPlanId = expectedPlanIdFor(req.requestedPlan)

        val subscription = payPalClient.fetchSubscription(req.paypalSubscriptionId)
            ?: throw SubscriptionVerificationException(
                "PayPal subscription '${req.paypalSubscriptionId}' was not found"
            )

        if (subscription.planId != expectedPlanId) {
            throw SubscriptionVerificationException(
                "PayPal subscription '${req.paypalSubscriptionId}' is not for the requested plan (${req.requestedPlan})"
            )
        }
        if (subscription.status != "ACTIVE") {
            throw SubscriptionVerificationException(
                "PayPal subscription '${req.paypalSubscriptionId}' is not active (status: ${subscription.status})"
            )
        }

        val linkedElsewhere = subscriberRepository.findByPaypalSubscriptionId(req.paypalSubscriptionId)
        if (linkedElsewhere != null && linkedElsewhere.id != subscriber.id) {
            throw SubscriptionVerificationException(
                "PayPal subscription '${req.paypalSubscriptionId}' is already linked to another subscriber"
            )
        }

        val targetTier = when (req.requestedPlan) {
            RequestedBillingPlan.PRO -> SubscriptionPlan.PRO
            RequestedBillingPlan.MAX -> SubscriptionPlan.MAX
        }

        return subscriberRepository.save(
            subscriber.copy(tier = targetTier, paypalSubscriptionId = req.paypalSubscriptionId)
        )
    }

    private fun expectedPlanIdFor(plan: RequestedBillingPlan): String = when (plan) {
        RequestedBillingPlan.PRO -> proPlanId
        RequestedBillingPlan.MAX -> maxPlanId
    }
}
