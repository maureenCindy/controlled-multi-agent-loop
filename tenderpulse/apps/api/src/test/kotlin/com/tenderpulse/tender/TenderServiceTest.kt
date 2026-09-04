package com.tenderpulse.tender

import com.tenderpulse.domain.NotFoundException
import com.tenderpulse.domain.Sector
import com.tenderpulse.domain.Tender
import com.tenderpulse.domain.TenderRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.Optional
import java.util.UUID

/**
 * Unit tests for [TenderService] (TP-052) — the filtering/take(size) logic that used to live
 * directly in `com.tenderpulse.api.TenderController` before that controller was made thin, same
 * pattern as [com.tenderpulse.subscriber.SubscriberServiceTest] for TP-037.
 */
class TenderServiceTest {

    private val tenderRepository = mockk<TenderRepository>()
    private val service = TenderService(tenderRepository)

    private fun tender(sector: Sector = Sector.OTHER) = Tender(
        title = "Tender",
        sector = sector,
        issuingAuthority = "Authority",
        sourceUrl = "https://egp.praz.org.zw/tenders/${UUID.randomUUID()}",
        sourceName = "PRAZ"
    )

    // ---- list ----

    @Test
    fun `list with no sector returns all tenders up to size`() {
        val tenders = listOf(tender(), tender(), tender())
        every { tenderRepository.findAll() } returns tenders

        val result = service.list(sector = null, page = 0, size = 20)

        assertEquals(3, result.size)
    }

    @Test
    fun `list filters by sector when provided`() {
        val itTender = tender(sector = Sector.IT)
        val healthTender = tender(sector = Sector.HEALTHCARE)
        every { tenderRepository.findAll() } returns listOf(itTender, healthTender)

        val result = service.list(sector = Sector.IT, page = 0, size = 20)

        assertEquals(listOf(itTender), result)
    }

    @Test
    fun `list truncates to the requested size`() {
        val tenders = (1..5).map { tender() }
        every { tenderRepository.findAll() } returns tenders

        val result = service.list(sector = null, page = 0, size = 2)

        assertEquals(2, result.size)
    }

    // ---- get ----

    @Test
    fun `get returns the tender when it exists`() {
        val t = tender()
        every { tenderRepository.findById(t.id) } returns Optional.of(t)

        assertEquals(t, service.get(t.id))
    }

    @Test
    fun `get for an unknown id throws NotFoundException`() {
        val id = UUID.randomUUID()
        every { tenderRepository.findById(id) } returns Optional.empty()

        assertThrows(NotFoundException::class.java) { service.get(id) }
    }
}
