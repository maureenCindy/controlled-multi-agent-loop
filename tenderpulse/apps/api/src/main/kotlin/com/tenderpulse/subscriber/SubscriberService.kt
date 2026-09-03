package com.tenderpulse.subscriber

import com.tenderpulse.domain.ConflictException
import com.tenderpulse.domain.InterestProfile
import com.tenderpulse.domain.InterestProfileRepository
import com.tenderpulse.domain.NotFoundException
import com.tenderpulse.domain.NotificationChannel
import com.tenderpulse.domain.Subscriber
import com.tenderpulse.domain.SubscriberRepository
import com.tenderpulse.domain.SubscriptionTier
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Business logic for subscriber registration and interest-profile management (TP-037).
 *
 * Owns every repository call this domain needs; [com.tenderpulse.api.SubscriberController]
 * only validates input, delegates here, and maps the returned entity to a response DTO.
 */
@Service
class SubscriberService(
    private val subscriberRepository: SubscriberRepository,
    private val profileRepository: InterestProfileRepository
) {

    fun register(req: RegisterRequest): Subscriber {
        val existing = subscriberRepository.findByEmail(req.email)
        if (existing != null) throw ConflictException("Email already registered")
        return subscriberRepository.save(
            Subscriber(email = req.email, phone = req.phone, tier = req.tier ?: SubscriptionTier.FREE)
        )
    }

    fun createProfile(subscriberId: UUID, req: ProfileRequest): InterestProfile {
        val subscriber = findSubscriberOrThrow(subscriberId)
        return profileRepository.save(
            InterestProfile(
                subscriber = subscriber,
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
