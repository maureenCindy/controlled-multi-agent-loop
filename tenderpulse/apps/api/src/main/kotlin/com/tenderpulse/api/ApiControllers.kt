package com.tenderpulse.api

import com.tenderpulse.admin.AdminPlanPricingRequest
import com.tenderpulse.admin.AdminPlanPricingResponse
import com.tenderpulse.admin.AdminService
import com.tenderpulse.admin.AdminSubscriberListResponse
import com.tenderpulse.admin.AdminSubscriberResponse
import com.tenderpulse.admin.AdminTierUpdateRequest
import com.tenderpulse.aggregation.AggregationService
import com.tenderpulse.auth.AuthService
import com.tenderpulse.auth.InvalidMagicLinkTokenException
import com.tenderpulse.auth.MagicLinkRequest
import com.tenderpulse.auth.MagicLinkResponse
import com.tenderpulse.auth.VerifyResponse
import com.tenderpulse.domain.Sector
import com.tenderpulse.subscriber.InterestProfileResponse
import com.tenderpulse.subscriber.ProSubscribeRequest
import com.tenderpulse.subscriber.ProfileRequest
import com.tenderpulse.subscriber.RegisterRequest
import com.tenderpulse.subscriber.SubscriberResponse
import com.tenderpulse.subscriber.SubscriberService
import com.tenderpulse.tender.TenderResponse
import com.tenderpulse.tender.TenderService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * Thin controller (TP-052): validates input, delegates all persistence/business logic to
 * [TenderService], and maps the returned entity to a response DTO. No repository is injected
 * here, mirroring [SubscriberController] (TP-037).
 */
@RestController
@RequestMapping("/api/v1")
class TenderController(
    private val tenderService: TenderService
) {
    @GetMapping("/tenders")
    fun list(
        @RequestParam(required = false) sector: Sector?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): List<TenderResponse> = tenderService.list(sector, page, size).map { TenderResponse.from(it) }

    @GetMapping("/tenders/{id}")
    fun get(@PathVariable id: UUID): TenderResponse = TenderResponse.from(tenderService.get(id))
}

/**
 * Thin controller (TP-037): validates input, delegates all persistence/business logic to
 * [SubscriberService], and maps the returned entity to a response DTO. No repository is
 * injected here.
 */
@RestController
@RequestMapping("/api/v1/subscribers")
class SubscriberController(
    private val subscriberService: SubscriberService
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@Valid @RequestBody req: RegisterRequest): SubscriberResponse =
        SubscriberResponse.from(subscriberService.register(req))

    /**
     * PayPal-verified Pro (PAID tier) signup (TP-042). Creates or upgrades the subscriber to
     * PAID only after [SubscriberService.registerPro] has independently confirmed the given
     * PayPal subscription ID with PayPal's API (active, matching plan) — a 200 rather than 201
     * because this may upgrade an existing FREE subscriber in place rather than create a new one.
     * Verification failures (not found / wrong plan / not active) surface as 400 via
     * [com.tenderpulse.domain.SubscriptionVerificationException]; a failed call to PayPal itself
     * surfaces as 502 via [com.tenderpulse.domain.PayPalApiException] — both mapped by their
     * `@ResponseStatus` annotation, same as every other domain exception in this API.
     */
    @PostMapping("/pro")
    fun registerPro(@Valid @RequestBody req: ProSubscribeRequest): SubscriberResponse =
        SubscriberResponse.from(subscriberService.registerPro(req))

    @PostMapping("/{id}/profiles")
    @ResponseStatus(HttpStatus.CREATED)
    fun createProfile(
        @PathVariable id: UUID,
        @Valid @RequestBody req: ProfileRequest
    ): InterestProfileResponse = InterestProfileResponse.from(subscriberService.createProfile(id, req))

    /** List ALL profiles for a subscriber, including inactive ones (management API). */
    @GetMapping("/{id}/profiles")
    fun listProfiles(@PathVariable id: UUID): List<InterestProfileResponse> =
        subscriberService.listProfiles(id).map { InterestProfileResponse.from(it) }

    /** Full replace of the mutable filter fields on an existing profile. */
    @PutMapping("/{id}/profiles/{profileId}")
    fun updateProfile(
        @PathVariable id: UUID,
        @PathVariable profileId: UUID,
        @Valid @RequestBody req: ProfileRequest
    ): InterestProfileResponse = InterestProfileResponse.from(subscriberService.updateProfile(id, profileId, req))
}

/**
 * Operator-only admin API (TP-044). Every route under `/api/v1/admin` — including the
 * pre-existing `/aggregate` trigger below — requires a valid `X-Admin-Key` header; see
 * [com.tenderpulse.auth.AdminKeyAuthFilter] and [com.tenderpulse.auth.SecurityConfig], which
 * enforce that at the Spring Security filter-chain level (a request that fails that check never
 * reaches this controller at all), not here. This controller stays thin — all business logic
 * lives in [AdminService] (or, for `/aggregate`, the pre-existing [AggregationService]).
 */
@RestController
@RequestMapping("/api/v1/admin")
class AdminController(
    private val aggregationService: AggregationService,
    private val adminService: AdminService
) {
    /** Trigger one aggregation cycle (for ops / scheduled jobs). */
    @PostMapping("/aggregate")
    fun aggregate() = aggregationService.runAggregationCycle()

    /** List all subscribers with email/tier/status/paypalSubscriptionId, paginated. */
    @GetMapping("/subscribers")
    fun listSubscribers(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): AdminSubscriberListResponse = AdminSubscriberListResponse.from(adminService.listSubscribers(page, size))

    /**
     * Manually override a subscriber's tier (support cases, refunds, syncing PayPal state back
     * in manually) — deliberately bypasses PayPal verification; see [AdminService.updateSubscriberTier].
     */
    @PutMapping("/subscribers/{id}/tier")
    fun updateSubscriberTier(
        @PathVariable id: UUID,
        @Valid @RequestBody req: AdminTierUpdateRequest
    ): AdminSubscriberResponse = AdminSubscriberResponse.from(adminService.updateSubscriberTier(id, req.tier))

    /** Update a PayPal Plan's pricing scheme via PayPal's `update-pricing-schemes` endpoint. */
    @PostMapping("/plans/{planId}/pricing")
    fun updatePlanPricing(
        @PathVariable planId: String,
        @Valid @RequestBody req: AdminPlanPricingRequest
    ): AdminPlanPricingResponse = adminService.updatePlanPricing(planId, req)
}

/**
 * Magic-link authentication (TP-038): passwordless sign-in for subscribers, closing #25's
 * guessable-UUID gap. Both endpoints are permitAll in [com.tenderpulse.auth.SecurityConfig] —
 * you can't hold a bearer token before you've verified a magic link, and you can't request one
 * while already authenticated as anyone in particular.
 */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authService: AuthService
) {
    /**
     * Always returns the same [MagicLinkResponse] whether or not [req.email] matches a
     * subscriber (see [AuthService.requestMagicLink]) — no account-enumeration leak.
     */
    @PostMapping("/magic-link")
    fun requestMagicLink(@Valid @RequestBody req: MagicLinkRequest): MagicLinkResponse {
        authService.requestMagicLink(req.email)
        return MagicLinkResponse()
    }

    @GetMapping("/verify")
    fun verify(@RequestParam token: String): VerifyResponse =
        VerifyResponse(accessToken = authService.verify(token))

    /** Maps every single-use/expiry failure to 401 with the reason spelled out (AC: "clear expired/reused error path"). */
    @ExceptionHandler(InvalidMagicLinkTokenException::class)
    fun handleInvalidToken(ex: InvalidMagicLinkTokenException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(mapOf("error" to ex.reason, "message" to (ex.message ?: "Invalid token")))
}
