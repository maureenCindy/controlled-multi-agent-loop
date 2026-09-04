package com.tenderpulse.tender

import com.tenderpulse.domain.NotFoundException
import com.tenderpulse.domain.Sector
import com.tenderpulse.domain.Tender
import com.tenderpulse.domain.TenderRepository
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Business logic for public tender listing/lookup (TP-052), following the same layering as
 * [com.tenderpulse.subscriber.SubscriberService] (TP-037): owns every repository call this
 * domain needs so [com.tenderpulse.api.TenderController] only validates input, delegates here,
 * and maps the returned entity to a response DTO.
 */
@Service
class TenderService(
    private val tenderRepository: TenderRepository
) {
    /** Same filtering/sorting behaviour as the pre-TP-052 controller: simple findAll, optional sector filter, take(size). */
    fun list(sector: Sector?, page: Int, size: Int): List<Tender> {
        // Scaffold: simple findAll; add pagination/spec in production
        return tenderRepository.findAll().let { list ->
            if (sector != null) list.filter { it.sector == sector } else list
        }.take(size)
    }

    fun get(id: UUID): Tender =
        tenderRepository.findById(id).orElseThrow { NotFoundException("Tender $id") }
}
