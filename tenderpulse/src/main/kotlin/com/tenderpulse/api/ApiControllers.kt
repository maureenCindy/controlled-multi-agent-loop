package com.tenderpulse.api

import com.tenderpulse.aggregation.AggregationService
import com.tenderpulse.domain.*
import jakarta.validation.Valid
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
    ): InterestProfile {
        val subscriber = subscriberRepository.findById(id)
            .orElseThrow { NotFoundException("Subscriber $id") }
        return profileRepository.save(
            InterestProfile(
                subscriber = subscriber,
                sectors = req.sectors.toMutableSet(),
                valueMin = req.valueMin,
                valueMax = req.valueMax,
                issuingAuthorityContains = req.issuingAuthorityContains,
                region = req.region,
                keywords = req.keywords.toMutableSet(),
                preferredChannels = req.preferredChannels.ifEmpty { setOf(NotificationChannel.EMAIL) }.toMutableSet()
            )
        )
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
    val preferredChannels: Set<NotificationChannel> = setOf(NotificationChannel.EMAIL)
)

@ResponseStatus(HttpStatus.NOT_FOUND)
class NotFoundException(message: String) : RuntimeException(message)

@ResponseStatus(HttpStatus.CONFLICT)
class ConflictException(message: String) : RuntimeException(message)
