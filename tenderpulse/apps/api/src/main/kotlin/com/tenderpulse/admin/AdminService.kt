package com.tenderpulse.admin

import com.tenderpulse.domain.NotFoundException
import com.tenderpulse.domain.Subscriber
import com.tenderpulse.domain.SubscriberRepository
import com.tenderpulse.domain.SubscriptionTier
import com.tenderpulse.paypal.PayPalClient
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Business logic for the operator-only admin API (TP-044): listing subscribers, manually
 * overriding a subscriber's tier, and updating PayPal plan pricing. Kept separate from
 * [com.tenderpulse.subscriber.SubscriberService] (rather than extending it) since this is
 * operator-on-someone-else's-account logic, not subscriber self-service — see
 * [com.tenderpulse.api.AdminController], the only caller, which is gated by
 * [com.tenderpulse.auth.AdminKeyAuthFilter] / [com.tenderpulse.auth.SecurityConfig].
 */
@Service
class AdminService(
    private val subscriberRepository: SubscriberRepository,
    private val payPalClient: PayPalClient
) {

    /** `GET /api/v1/admin/subscribers` — paginated, newest first. */
    fun listSubscribers(page: Int, size: Int): Page<Subscriber> =
        subscriberRepository.findAll(PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")))

    /**
     * `PUT /api/v1/admin/subscribers/{id}/tier` — sets the tier directly, deliberately bypassing
     * any PayPal verification (explicit admin override, see issue #44 assumptions). Persisted
     * immediately so it's reflected on the next [listSubscribers] call (AC).
     *
     * @throws NotFoundException if no subscriber exists with that id
     */
    fun updateSubscriberTier(id: UUID, tier: SubscriptionTier): Subscriber {
        val subscriber = subscriberRepository.findById(id).orElseThrow { NotFoundException("Subscriber $id") }
        return subscriberRepository.save(subscriber.copy(tier = tier))
    }

    /**
     * `POST /api/v1/admin/plans/{planId}/pricing` — delegates to
     * [PayPalClient.updatePlanPricing]; that call either succeeds (this returns a confirming
     * [AdminPlanPricingResponse]) or throws (invalid/nonexistent plan ->
     * [com.tenderpulse.domain.PayPalPlanPricingException]; PayPal itself unreachable ->
     * [com.tenderpulse.domain.PayPalApiException]) — no partial state either way, since nothing
     * is persisted locally for a pricing update.
     */
    fun updatePlanPricing(planId: String, request: AdminPlanPricingRequest): AdminPlanPricingResponse {
        payPalClient.updatePlanPricing(
            planId = planId,
            currencyCode = request.currencyCode,
            fixedPrice = request.fixedPrice,
            billingCycleSequence = request.billingCycleSequence
        )
        return AdminPlanPricingResponse(
            planId = planId,
            currencyCode = request.currencyCode,
            fixedPrice = request.fixedPrice,
            billingCycleSequence = request.billingCycleSequence
        )
    }
}
