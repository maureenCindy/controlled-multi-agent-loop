package com.tenderpulse.billing

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern

/**
 * The paid plans a subscriber may request via `POST /api/v1/billing/paypal/subscriptions/confirm`
 * (TP-127, issue #127). Deliberately excludes `FREE` -- this endpoint only ever grants a *paid*
 * entitlement (downgrading to Free has its own, separate, out-of-scope flow — spec §10.6).
 *
 * Using a Kotlin enum here (rather than a `String` + regex, the way
 * [com.tenderpulse.subscriber.ProSubscribeRequest] validates `paypalSubscriptionId`) means Jackson
 * itself rejects any value other than `PRO`/`MAX` (e.g. `"FREE"`, `"free"`, `"ADMIN"`) while
 * binding the request body, before [BillingController]/[BillingService] ever see it — surfaced as
 * Spring's standard 400 for an unreadable request body
 * ([org.springframework.http.converter.HttpMessageNotReadableException]), the same class of
 * automatic rejection `@Valid` gives every other malformed field on this DTO. See
 * `BillingControllerValidationTest` for the empirical proof
 * this actually happens (a request with `"requestedPlan":"FREE"` never reaches
 * [PayPalClient][com.tenderpulse.paypal.PayPalClient]), not just the assumption that Jackson
 * "should" behave this way.
 */
enum class RequestedBillingPlan {
    PRO,
    MAX
}

/**
 * Request body for `POST /api/v1/billing/paypal/subscriptions/confirm` (spec §10.2).
 *
 * `paypalSubscriptionId` uses the exact same shape validation as
 * [com.tenderpulse.subscriber.ProSubscribeRequest.paypalSubscriptionId] (issue #81's fix, carried
 * over here) -- a `/`, `..`, or `?` in a client-supplied subscription id is rejected by Bean
 * Validation (`@Valid` on [BillingController.confirmSubscription]) before
 * [BillingService]/[com.tenderpulse.paypal.PayPalClient.fetchSubscription] are ever invoked, which
 * itself also builds the outbound PayPal URL via `UriComponentsBuilder.pathSegment` as
 * defense-in-depth (see that method's kdoc).
 */
data class ConfirmSubscriptionRequest(
    @field:NotBlank
    @field:Pattern(
        regexp = "^[A-Za-z0-9-]+$",
        message = "paypalSubscriptionId must be alphanumeric with hyphens only (PayPal subscription ID format), e.g. 'I-BW452GLLEP1G'"
    )
    val paypalSubscriptionId: String,
    val requestedPlan: RequestedBillingPlan
)

/** One plan's entry in [PublicBillingConfigResponse.plans] -- display/bootstrap only, see that class's kdoc. */
data class PlanConfig(
    val paypalPlanId: String,
    val amount: String
)

/**
 * Response shape for `GET /api/v1/billing/public-config` (spec §10.1).
 *
 * Display/bootstrap configuration for the checkout UI only -- never trusted server-side for an
 * actual entitlement grant. [BillingService.confirmSubscription] independently re-fetches and
 * re-validates the subscription's plan id against server-side configuration on every confirm call,
 * regardless of what this endpoint returned to the browser earlier. Never includes
 * `client-secret`/`webhook-id` or any other PayPal credential.
 */
data class PublicBillingConfigResponse(
    val provider: String = "PAYPAL",
    val clientId: String,
    val currency: String,
    val plans: Map<String, PlanConfig>
)
