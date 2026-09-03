package com.tenderpulse.api

import com.tenderpulse.aggregation.AggregationService
import com.tenderpulse.domain.NotFoundException
import com.tenderpulse.domain.Sector
import com.tenderpulse.domain.Tender
import com.tenderpulse.domain.TenderRepository
import com.tenderpulse.subscriber.InterestProfileResponse
import com.tenderpulse.subscriber.ProfileRequest
import com.tenderpulse.subscriber.RegisterRequest
import com.tenderpulse.subscriber.SubscriberResponse
import com.tenderpulse.subscriber.SubscriberService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
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

@RestController
@RequestMapping("/api/v1/admin")
class AdminController(
    private val aggregationService: AggregationService
) {
    /** Trigger one aggregation cycle (for ops / scheduled jobs). */
    @PostMapping("/aggregate")
    fun aggregate() = aggregationService.runAggregationCycle()
}
