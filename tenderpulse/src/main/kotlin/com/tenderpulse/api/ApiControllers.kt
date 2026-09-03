package com.tenderpulse.api

import com.tenderpulse.aggregation.AggregationService
import com.tenderpulse.domain.*
import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class TenderController(
    private val tenderRepository: TenderRepository
) {
    @GetMapping("/tenders")
    fun list(
        @RequestParam(required = false) sector: Sector?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): List<Tender> {
        // Scaffold: simple findAll; add pagination/spec in production
        return tenderRepository.findAll().let { list ->
            if (sector != null) list.filter { it.sector == sector } else list
        }.take(size)
    }

    @GetMapping("/tenders/{id}")
    fun get(@PathVariable id: UUID): Tender =
        tenderRepository.findById(id).orElseThrow { NotFoundException("Tender $id") }
}

@RestController
@RequestMapping("/api/v1/subscribers")
class SubscriberController(
    private val subscriberRepository: SubscriberRepository,
    private val profileRepository: InterestProfileRepository
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@Valid @RequestBody req: RegisterRequest): Subscriber {
        val existing = subscriberRepository.findByEmail(req.email)
        if (existing != null) throw ConflictException("Email already registered")
        return subscriberRepository.save(
            Subscriber(email = req.email, phone = req.phone, tier = req.tier ?: SubscriptionTier.FREE)
        )
    }

    @PostMapping("/{id}/profiles")
    @ResponseStatus(HttpStatus.CREATED)
    fun createProfile(
        @PathVariable id: UUID,
        @Valid @RequestBody req: ProfileRequest
    ): InterestProfileResponse {
        val subscriber = subscriberRepository.findById(id)
            .orElseThrow { NotFoundException("Subscriber $id") }
        val saved = profileRepository.save(
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
        return InterestProfileResponse.from(saved)
    }

    /** List ALL profiles for a subscriber, including inactive ones (management API). */
    @GetMapping("/{id}/profiles")
    fun listProfiles(@PathVariable id: UUID): List<InterestProfileResponse> {
        subscriberRepository.findById(id).orElseThrow { NotFoundException("Subscriber $id") }
        return profileRepository.findBySubscriberId(id).map { InterestProfileResponse.from(it) }
    }

    /** Full replace of the mutable filter fields on an existing profile. */
    @PutMapping("/{id}/profiles/{profileId}")
    fun updateProfile(
        @PathVariable id: UUID,
        @PathVariable profileId: UUID,
        @Valid @RequestBody req: ProfileRequest
    ): InterestProfileResponse {
        subscriberRepository.findById(id).orElseThrow { NotFoundException("Subscriber $id") }
        val existing = profileRepository.findById(profileId)
            .orElseThrow { NotFoundException("Profile $profileId") }
        if (existing.subscriber.id != id) {
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
        val saved = profileRepository.save(updated)
        return InterestProfileResponse.from(saved)
    }
}

@RestController
@RequestMapping("/api/v1/admin")
class AdminController(
    private val aggregationService: AggregationService
) {
    /** Trigger one aggregation cycle (for ops / scheduled jobs). */
    @PostMapping("/aggregate")
    fun aggregate() = aggregationService.runAggregationCycle()
}

data class RegisterRequest(
    @field:Email @field:NotBlank val email: String,
    val phone: String? = null,
    val tier: SubscriptionTier? = null
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

@ResponseStatus(HttpStatus.NOT_FOUND)
class NotFoundException(message: String) : RuntimeException(message)

@ResponseStatus(HttpStatus.CONFLICT)
class ConflictException(message: String) : RuntimeException(message)
