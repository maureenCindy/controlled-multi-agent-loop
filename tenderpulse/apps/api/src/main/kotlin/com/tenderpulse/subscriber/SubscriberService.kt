package com.tenderpulse.subscriber

import com.tenderpulse.domain.ConflictException
import com.tenderpulse.domain.InterestProfile
import com.tenderpulse.domain.InterestProfileRepository
import com.tenderpulse.domain.NotFoundException
import com.tenderpulse.domain.NotificationChannel
import com.tenderpulse.domain.Subscriber
import com.tenderpulse.domain.SubscriberRepository
import com.tenderpulse.domain.SubscriptionTier
import com.tenderpulse.domain.SubscriptionVerificationException
import com.tenderpulse.paypal.PayPalClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Business logic for subscriber registration and interest-profile management (TP-037), including
 * PayPal-verified Pro (PAID tier) signup (TP-042).
 *
 * Owns every repository call this domain needs; [com.tenderpulse.api.SubscriberController]
 * only validates input, delegates here, and maps the returned entity to a response DTO.
 */
@Service
class SubscriberService(
    private val subscriberRepository: SubscriberRepository,
    private val profileRepository: InterestProfileRepository,
    private val payPalClient: PayPalClient,
    @Value("\${paypal.plan-id:}")
    private val expectedPlanId: String
) {

    fun register(req: RegisterRequest): Subscriber {
        val existing = subscriberRepository.findByEmail(req.email)
        if (existing != null) throw ConflictException("Email already registered")
        return subscriberRepository.save(
            Subscriber(email = req.email, phone = req.phone, tier = req.tier ?: SubscriptionTier.FREE)
        )
    }

    /**
     * Verifies a PayPal subscription server-side (TP-042) and, only on success, creates or
     * upgrades the matching [Subscriber] to `tier = PAID`, storing the PayPal subscription ID.
     *
     * The client's claim that checkout succeeded is never trusted directly: this fetches the
     * subscription from PayPal by ID and confirms all of the following before touching any
     * subscriber record:
     * - its `status` is `ACTIVE`
     * - its `plan_id` matches the configured expected plan — a fabricated or unrelated
     *   subscription ID (e.g. for a different PayPal product) is rejected the same way a
     *   non-existent one is
     * - its own payer email (`subscriber.email_address`, from PayPal — not the request body)
     *   matches the requested `email`, case-insensitively — otherwise one genuinely-ACTIVE
     *   subscription could be replayed against arbitrary emails to mint unlimited free upgrades
     * - it isn't already linked to a *different* subscriber — otherwise the same subscription ID
     *   could be reused across multiple emails one at a time, bypassing the check above by
     *   changing which email is "current" on each call
     *
     * An existing FREE subscriber with this email is upgraded in place (their [Subscriber.id] is
     * preserved); a first-time Pro signup creates a new subscriber.
     *
     * @throws SubscriptionVerificationException if the subscription doesn't exist, is for the
     *   wrong plan, isn't ACTIVE, doesn't belong to the requested email, or is already linked to a
     *   different subscriber — no subscriber is created or changed in any of those cases.
     * @throws com.tenderpulse.domain.PayPalApiException if the call to PayPal itself fails.
     */
    fun registerPro(req: ProSubscribeRequest): Subscriber {
        val subscription = payPalClient.fetchSubscription(req.paypalSubscriptionId)
            ?: throw SubscriptionVerificationException(
                "PayPal subscription '${req.paypalSubscriptionId}' was not found"
            )

        if (subscription.planId != expectedPlanId) {
            throw SubscriptionVerificationException(
                "PayPal subscription '${req.paypalSubscriptionId}' is not for the expected plan"
            )
        }
        if (subscription.status != "ACTIVE") {
            throw SubscriptionVerificationException(
                "PayPal subscription '${req.paypalSubscriptionId}' is not active (status: ${subscription.status})"
            )
        }
        val payerEmail = subscription.subscriber?.emailAddress
        if (payerEmail == null || !payerEmail.equals(req.email, ignoreCase = true)) {
            throw SubscriptionVerificationException(
                "PayPal subscription '${req.paypalSubscriptionId}' does not belong to '${req.email}'"
            )
        }

        val existing = subscriberRepository.findByEmail(req.email)
        val linkedElsewhere = subscriberRepository.findByPaypalSubscriptionId(req.paypalSubscriptionId)
        if (linkedElsewhere != null && linkedElsewhere.id != existing?.id) {
            throw SubscriptionVerificationException(
                "PayPal subscription '${req.paypalSubscriptionId}' is already linked to another subscriber"
            )
        }

        val toSave = existing?.copy(
            tier = SubscriptionTier.PAID,
            paypalSubscriptionId = req.paypalSubscriptionId
        ) ?: Subscriber(
            email = req.email,
            tier = SubscriptionTier.PAID,
            paypalSubscriptionId = req.paypalSubscriptionId
        )
        return subscriberRepository.save(toSave)
    }

    fun createProfile(subscriberId: UUID, req: ProfileRequest): InterestProfile {
        val subscriber = findSubscriberOrThrow(subscriberId)
        return profileRepository.save(
            InterestProfile(
                subscriber = subscriber,
                name = req.name,
                sectors = req.sectors.toMutableSet(),
                valueMin = req.valueMin,
                valueMax = req.valueMax,
                issuingAuthorityContains = req.issuingAuthorityContains,
                region = req.region,
                keywords = req.keywords.toMutableSet(),
                preferredChannels = req.preferredChannels.ifEmpty { setOf(NotificationChannel.EMAIL) }.toMutableSet(),
                active = req.active
            )
        )
    }

    /** List ALL profiles for a subscriber, including inactive ones (management API). */
    fun listProfiles(subscriberId: UUID): List<InterestProfile> {
        findSubscriberOrThrow(subscriberId)
        return profileRepository.findBySubscriberId(subscriberId)
    }

    /** Full replace of the mutable filter fields on an existing profile. */
    fun updateProfile(subscriberId: UUID, profileId: UUID, req: ProfileRequest): InterestProfile {
        findSubscriberOrThrow(subscriberId)
        val existing = profileRepository.findById(profileId)
            .orElseThrow { NotFoundException("Profile $profileId") }
        if (existing.subscriber.id != subscriberId) {
            throw NotFoundException("Profile $profileId")
        }
        val updated = existing.copy(
            name = req.name,
            sectors = req.sectors.toMutableSet(),
            valueMin = req.valueMin,
            valueMax = req.valueMax,
            issuingAuthorityContains = req.issuingAuthorityContains,
            region = req.region,
            keywords = req.keywords.toMutableSet(),
            preferredChannels = req.preferredChannels.ifEmpty { setOf(NotificationChannel.EMAIL) }.toMutableSet(),
            active = req.active
        )
        return profileRepository.save(updated)
    }

    private fun findSubscriberOrThrow(subscriberId: UUID): Subscriber =
        subscriberRepository.findById(subscriberId).orElseThrow { NotFoundException("Subscriber $subscriberId") }
}
