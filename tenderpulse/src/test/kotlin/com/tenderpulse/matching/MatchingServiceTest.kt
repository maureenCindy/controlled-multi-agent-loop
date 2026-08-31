package com.tenderpulse.matching

import com.tenderpulse.domain.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class MatchingServiceTest {

    private val matching = MatchingService()

    private fun sampleTender(
        sector: Sector = Sector.IT,
        valueMin: BigDecimal? = BigDecimal("100000"),
        valueMax: BigDecimal? = BigDecimal("500000"),
        authority: String = "City of Cape Town",
        region: String? = "Western Cape",
        title: String = "Supply of network switches",
        keywords: Set<String> = setOf("network", "switches")
    ) = Tender(
        title = title,
        sector = sector,
        valueMin = valueMin,
        valueMax = valueMax,
        issuingAuthority = authority,
        region = region,
        sourceUrl = "https://example.gov/tender/1",
        sourceName = "sample",
        keywords = keywords.toMutableSet(),
        deadline = Instant.now().plusSeconds(86400 * 14)
    )

    private fun sampleProfile(
        sectors: Set<Sector> = setOf(Sector.IT),
        valueMin: BigDecimal? = BigDecimal("50000"),
        valueMax: BigDecimal? = BigDecimal("1000000"),
        authority: String? = "Cape Town",
        region: String? = "Western Cape",
        keywords: Set<String> = setOf("network")
    ): InterestProfile {
        val sub = Subscriber(email = "test@example.com", tier = SubscriptionTier.PAID)
        return InterestProfile(
            subscriber = sub,
            sectors = sectors.toMutableSet(),
            valueMin = valueMin,
            valueMax = valueMax,
            issuingAuthorityContains = authority,
            region = region,
            keywords = keywords.toMutableSet()
        )
    }

    @Test
    fun `matches when all criteria align`() {
        assertTrue(matching.matches(sampleTender(), sampleProfile()))
    }

    @Test
    fun `rejects wrong sector`() {
        val profile = sampleProfile(sectors = setOf(Sector.HEALTHCARE))
        assertFalse(matching.matches(sampleTender(), profile))
    }

    @Test
    fun `rejects value outside range`() {
        val profile = sampleProfile(valueMin = BigDecimal("600000"), valueMax = BigDecimal("900000"))
        assertFalse(matching.matches(sampleTender(), profile))
    }

    @Test
    fun `rejects missing keyword`() {
        val profile = sampleProfile(keywords = setOf("excavation"))
        assertFalse(matching.matches(sampleTender(), profile))
    }

    @Test
    fun `accepts when profile has no filters`() {
        val openProfile = sampleProfile(
            sectors = emptySet(),
            valueMin = null,
            valueMax = null,
            authority = null,
            region = null,
            keywords = emptySet()
        )
        assertTrue(matching.matches(sampleTender(), openProfile))
    }

    @Test
    fun `matches tender without deadline`() {
        val tenderNoDeadline = sampleTender().copy(deadline = null)
        assertTrue(matching.matches(tenderNoDeadline, sampleProfile()))
    }

    @Test
    fun `maps ZW tender with external ID and currency`() {
        val zwTender = sampleTender(
            title = "Supply and Delivery of Computer Consumables",
            authority = "Ministry of Finance, Economic Development and Investment Promotion",
            region = "Harare",
            keywords = setOf("GC006", "computers", "printers")
        ).copy(
            externalTenderId = "TR22053",
            currency = "USD"
        )

        assertEquals("TR22053", zwTender.externalTenderId)
        assertEquals("USD", zwTender.currency)
        assertTrue(zwTender.keywords.contains("GC006"))
    }
}
