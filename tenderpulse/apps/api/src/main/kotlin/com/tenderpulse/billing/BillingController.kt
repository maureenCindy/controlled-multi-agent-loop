package com.tenderpulse.billing

import com.tenderpulse.subscriber.SubscriberResponse
import jakarta.validation.Valid
import org.springframework.security.authentication.InsufficientAuthenticationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Thin controller (TP-127, issue #127): validates input, delegates all persistence/business logic
 * to [BillingService], and maps the returned entity to a response DTO -- same convention as
 * [com.tenderpulse.api.SubscriberController].
 */
@RestController
@RequestMapping("/api/v1/billing")
class BillingController(
    private val billingService: BillingService
) {

    /** `GET /api/v1/billing/public-config` (spec §10.1) -- permitAll, see [com.tenderpulse.auth.SecurityConfig]. */
    @GetMapping("/public-config")
    fun publicConfig(): PublicBillingConfigResponse = billingService.publicConfig()

    /**
     * `POST /api/v1/billing/paypal/subscriptions/confirm` (spec §10.2) -- requires an
     * authenticated subscriber bearer token (TP-038); see
     * [com.tenderpulse.auth.SecurityConfig]'s dedicated matcher for this exact path.
     *
     * The target subscriber is the *authenticated caller*, resolved from
     * [org.springframework.security.core.context.SecurityContext] via [currentSubscriberId] --
     * never a subscriber id or email taken from the request body -- this is what lets
     * [BillingService.confirmSubscription] establish ownership without relying on matching email
     * addresses (spec §10.2).
     */
    @PostMapping("/paypal/subscriptions/confirm")
    fun confirmSubscription(@Valid @RequestBody req: ConfirmSubscriptionRequest): SubscriberResponse =
        SubscriberResponse.from(billingService.confirmSubscription(currentSubscriberId(), req))

    /**
     * Reads the subscriber id [com.tenderpulse.auth.BearerTokenAuthFilter] placed in the security
     * context as the authentication principal -- same source of truth
     * [com.tenderpulse.auth.SubscriberOwnershipInterceptor] reads for the path-variable-scoped
     * profile endpoints, just compared against the *authenticated caller itself* here rather than
     * a `{id}` path variable, since this route has none.
     *
     * Should never actually be null/non-UUID at this point in practice --
     * [com.tenderpulse.auth.SecurityConfig]'s `authorizeHttpRequests` rule for this exact path
     * already rejects an unauthenticated request with 401 before Spring MVC dispatches to this
     * method at all -- but throwing [InsufficientAuthenticationException] (rather than asserting
     * with `!!`) means a future change that ever removed that guard would still fail safely: this
     * is an [org.springframework.security.core.AuthenticationException], which
     * [org.springframework.security.web.access.ExceptionTranslationFilter] catches even when
     * thrown from within request handling (not just filter code) and translates into the same 401
     * JSON response [com.tenderpulse.auth.SecurityConfig]'s `authenticationEntryPoint` already
     * produces for every other unauthenticated request.
     */
    private fun currentSubscriberId(): UUID =
        SecurityContextHolder.getContext().authentication?.principal as? UUID
            ?: throw InsufficientAuthenticationException("No authenticated subscriber")
}
