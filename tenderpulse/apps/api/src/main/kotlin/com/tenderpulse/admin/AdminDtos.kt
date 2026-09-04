package com.tenderpulse.admin

import com.tenderpulse.domain.Subscriber
import com.tenderpulse.domain.SubscriptionTier
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.data.domain.Page
import java.math.BigDecimal
import java.util.UUID

/**
 * Admin-facing view of a [Subscriber] (TP-044) — deliberately its own DTO rather than reusing
 * [com.tenderpulse.subscriber.SubscriberResponse]: this is an *operator* looking at someone
 * else's account (unlike the subscriber-facing response, which is always the caller's own data),
 * and the issue's AC calls for an explicit `status` field alongside `tier` and
 * `paypalSubscriptionId` rather than the raw `active` boolean.
 */
data class AdminSubscriberResponse(
    val id: UUID,
    val email: String,
    val tier: SubscriptionTier,
    val status: String,
    val paypalSubscriptionId: String?
) {
    companion object {
        fun from(subscriber: Subscriber): AdminSubscriberResponse = AdminSubscriberResponse(
            id = subscriber.id,
            email = subscriber.email,
            tier = subscriber.tier,
            status = if (subscriber.active) "ACTIVE" else "INACTIVE",
            paypalSubscriptionId = subscriber.paypalSubscriptionId
        )
    }
}

/** Paginated `GET /api/v1/admin/subscribers` response (TP-044 AC: "paginated"). */
data class AdminSubscriberListResponse(
    val content: List<AdminSubscriberResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int
) {
    companion object {
        fun from(page: Page<Subscriber>): AdminSubscriberListResponse = AdminSubscriberListResponse(
            content = page.content.map { AdminSubscriberResponse.from(it) },
            page = page.number,
            size = page.size,
            totalElements = page.totalElements,
            totalPages = page.totalPages
        )
    }
}

/**
 * Request body for `PUT /api/v1/admin/subscribers/{id}/tier` — an explicit admin override that
 * intentionally bypasses PayPal verification (see issue #44 assumptions: "for cases where PayPal
 * state and TenderPulse state need to be forced back in sync manually").
 */
data class AdminTierUpdateRequest(
    @field:NotNull val tier: SubscriptionTier
)

/**
 * Request body for `POST /api/v1/admin/plans/{planId}/pricing`. Deliberately a single
 * fixed-price/currency pair (the MVP's plans are single-tier, single-cycle) rather than exposing
 * PayPal's full multi-cycle pricing-scheme shape — [com.tenderpulse.paypal.PayPalClient.updatePlanPricing]
 * maps this onto PayPal's `update-pricing-schemes` request body.
 */
data class AdminPlanPricingRequest(
    @field:NotBlank val currencyCode: String,
    @field:NotNull @field:DecimalMin(value = "0.0", inclusive = false) val fixedPrice: BigDecimal,
    val billingCycleSequence: Int = 1
)

/** Confirms the PayPal call succeeded (TP-044 AC: "returns a clear result"). */
data class AdminPlanPricingResponse(
    val planId: String,
    val currencyCode: String,
    val fixedPrice: BigDecimal,
    val billingCycleSequence: Int,
    val success: Boolean = true
)
