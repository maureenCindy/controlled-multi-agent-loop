package com.tenderpulse.subscriber

import com.tenderpulse.domain.InterestProfile
import com.tenderpulse.domain.NotificationChannel
import com.tenderpulse.domain.Sector
import com.tenderpulse.domain.Subscriber
import com.tenderpulse.domain.SubscriptionTier
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class RegisterRequest(
    @field:Email @field:NotBlank val email: String,
    val phone: String? = null,
    val tier: SubscriptionTier? = null
)

/**
 * Request body for `POST /api/v1/subscribers/pro` (TP-042) — the PayPal subscription ID is the
 * one returned to the frontend by PayPal's `onApprove` callback after checkout. It is never
 * trusted directly: [com.tenderpulse.subscriber.SubscriberService.registerPro] verifies it
 * server-side against PayPal's API before any tier upgrade happens.
 *
 * `paypalSubscriptionId` is also checked against PayPal's own subscription ID shape (issue #81:
 * it previously flowed unvalidated into
 * [com.tenderpulse.paypal.PayPalClient.fetchSubscription]'s string-interpolated request URL — the
 * same class of bug issue #68 fixed for the admin plan-pricing path, but on this **public,
 * unauthenticated** endpoint, so higher exposure). Unlike #68's `planId` path variable, this is a
 * `@RequestBody` field, so standard Bean Validation (`@Valid` on the controller parameter) is
 * sufficient to guarantee rejection before [com.tenderpulse.subscriber.SubscriberService] or
 * [com.tenderpulse.paypal.PayPalClient] are ever invoked — no method-level proxy/AOP is needed the
 * way it would be for a `@PathVariable`. A mismatch surfaces as the framework's standard 400
 * (`MethodArgumentNotValidException`), same as every other `@Valid` failure on this DTO (e.g. a
 * malformed `email`).
 */
data class ProSubscribeRequest(
    @field:Email @field:NotBlank val email: String,
    @field:NotBlank
    @field:Pattern(
        regexp = "^[A-Za-z0-9-]+$",
        message = "paypalSubscriptionId must be alphanumeric with hyphens only (PayPal subscription ID format), e.g. 'I-BW452GLLEP1G'"
    )
    val paypalSubscriptionId: String
)

data class ProfileRequest(
    val sectors: Set<Sector> = emptySet(),
    val valueMin: BigDecimal? = null,
    val valueMax: BigDecimal? = null,
    val issuingAuthorityContains: String? = null,
    val region: String? = null,
    val keywords: Set<String> = emptySet(),
    val preferredChannels: Set<NotificationChannel> = setOf(NotificationChannel.EMAIL),
    val active: Boolean = true
) {
    /**
     * valueMin <= valueMax when both are set; either one alone (or neither) is unrestricted.
     * Expressed as a bean-validation constraint so @Valid turns a violation into a 400 via
     * MethodArgumentNotValidException, on both create and update.
     */
    @get:AssertTrue(message = "valueMin must be <= valueMax")
    val valueRangeValid: Boolean
        get() {
            val min = valueMin
            val max = valueMax
            return min == null || max == null || min <= max
        }
}

/**
 * Response shape for `POST /api/v1/subscribers` (TP-037). Deliberately mirrors the subscriber's
 * own submission (email/phone/tier) plus server-assigned fields (id/active/createdAt) — this is
 * the caller's own data being echoed back, not another subscriber's, so it carries no extra PII
 * exposure beyond what they just sent us.
 */
data class SubscriberResponse(
    val id: UUID,
    val email: String,
    val phone: String?,
    val tier: SubscriptionTier,
    val active: Boolean,
    val createdAt: Instant,
    val paypalSubscriptionId: String? = null
) {
    companion object {
        fun from(subscriber: Subscriber): SubscriberResponse = SubscriberResponse(
            id = subscriber.id,
            email = subscriber.email,
            phone = subscriber.phone,
            tier = subscriber.tier,
            active = subscriber.active,
            createdAt = subscriber.createdAt,
            paypalSubscriptionId = subscriber.paypalSubscriptionId
        )
    }
}

/**
 * Response shape for interest-profile endpoints. Deliberately excludes the `subscriber`
 * relation (and therefore `Subscriber.email`) so profile responses never leak PII.
 * See issue #23 — this closes the response-shape leak only; it does not add
 * authentication/authorization (tracked separately in issue #25).
 */
data class InterestProfileResponse(
    val id: UUID,
    val sectors: Set<Sector>,
    val valueMin: BigDecimal?,
    val valueMax: BigDecimal?,
    val issuingAuthorityContains: String?,
    val region: String?,
    val keywords: Set<String>,
    val preferredChannels: Set<NotificationChannel>,
    val active: Boolean
) {
    companion object {
        fun from(profile: InterestProfile): InterestProfileResponse = InterestProfileResponse(
            id = profile.id,
            sectors = profile.sectors,
            valueMin = profile.valueMin,
            valueMax = profile.valueMax,
            issuingAuthorityContains = profile.issuingAuthorityContains,
            region = profile.region,
            keywords = profile.keywords,
            preferredChannels = profile.preferredChannels,
            active = profile.active
        )
    }
}
