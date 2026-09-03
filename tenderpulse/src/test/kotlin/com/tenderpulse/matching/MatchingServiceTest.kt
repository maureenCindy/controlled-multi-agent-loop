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
        keywords: Set<String> = setOf("network"),
        active: Boolean = true
    ): InterestProfile {
        val sub = Subscriber(email = "test@example.com", tier = SubscriptionTier.PAID)
        return InterestProfile(
            subscriber = sub,
            sectors = sectors.toMutableSet(),
            valueMin = valueMin,
            valueMax = valueMax,
            issuingAuthorityContains = authority,
            region = region,
            keywords = keywords.toMutableSet(),
            active = active
        )
    }

    @Test
    fun `matches when all criteria align`() {
        assertTrue(matching.matches(sampleTender(), sampleProfile()))
    }

    @Test
    fun `rejects an inactive profile even when every other dimension matches`() {
        val inactiveProfile = sampleProfile(active = false)
        assertFalse(matching.matches(sampleTender(), inactiveProfile))
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
    fun `empty filters match a tender that would fail every individual filter`() {
        val openProfile = sampleProfile(
            sectors = emptySet(),
            valueMin = null,
            valueMax = null,
            authority = null,
            region = null,
            keywords = emptySet()
        )
        val unrelatedTender = sampleTender(
            sector = Sector.AGRICULTURE,
            valueMin = BigDecimal("5"),
            valueMax = BigDecimal("10"),
            authority = "Rural District Council",
            region = "Matabeleland North",
            title = "Supply of fertiliser",
            keywords = setOf("fertiliser")
        )
        assertTrue(matching.matches(unrelatedTender, openProfile))
    }

    @Test
    fun `rejects province mismatch`() {
        val profile = sampleProfile(region = "Harare")
        val tender = sampleTender(region = "Bulawayo")
        assertFalse(matching.matches(tender, profile))
    }

    @Test
    fun `rejects a region fragment that only overlaps part of a province name`() {
        val profile = sampleProfile(region = "land")
        assertFalse(matching.matches(sampleTender(region = "Mashonaland East"), profile))
        assertFalse(matching.matches(sampleTender(region = "Matabeleland North"), profile))
    }

    @Test
    fun `rejects sibling provinces that share a word`() {
        val profile = sampleProfile(region = "Mashonaland East")
        assertFalse(matching.matches(sampleTender(region = "Mashonaland West"), profile))
    }

    @Test
    fun `matches when the profile region is a whole word within the tender region`() {
        val profile = sampleProfile(region = "Harare")
        assertTrue(matching.matches(sampleTender(region = "Harare Province"), profile))
    }

    @Test
    fun `matches when the tender region is narrower than the profile region`() {
        val profile = sampleProfile(region = "Harare Metropolitan Province")
        assertTrue(matching.matches(sampleTender(region = "Harare"), profile))
    }

    @Test
    fun `matches region ignoring case and punctuation`() {
        val profile = sampleProfile(region = "matabeleland north")
        assertTrue(matching.matches(sampleTender(region = "Matabeleland-North"), profile))
    }

    @Test
    fun `rejects tender with null region when the profile sets a region filter`() {
        val profile = sampleProfile(region = "Harare")
        assertFalse(matching.matches(sampleTender(region = null), profile))
    }

    @Test
    fun `matches tender with null region when the profile sets no region filter`() {
        val profile = sampleProfile(region = null)
        assertTrue(matching.matches(sampleTender(region = null), profile))
    }

    @Test
    fun `matches when tender valueMax lands exactly on profile valueMin`() {
        val profile = sampleProfile(valueMin = BigDecimal("100000"), valueMax = BigDecimal("500000"))
        val tender = sampleTender(valueMin = BigDecimal("20000"), valueMax = BigDecimal("100000"))
        assertTrue(matching.matches(tender, profile))
    }

    @Test
    fun `matches when tender valueMin lands exactly on profile valueMax`() {
        val profile = sampleProfile(valueMin = BigDecimal("100000"), valueMax = BigDecimal("500000"))
        val tender = sampleTender(valueMin = BigDecimal("500000"), valueMax = BigDecimal("800000"))
        assertTrue(matching.matches(tender, profile))
    }

    @Test
    fun `rejects when tender valueMax falls one unit below profile valueMin`() {
        val profile = sampleProfile(valueMin = BigDecimal("100000"), valueMax = BigDecimal("500000"))
        val tender = sampleTender(valueMin = BigDecimal("20000"), valueMax = BigDecimal("99999"))
        assertFalse(matching.matches(tender, profile))
    }

    @Test
    fun `rejects when tender valueMin falls one unit above profile valueMax`() {
        val profile = sampleProfile(valueMin = BigDecimal("100000"), valueMax = BigDecimal("500000"))
        val tender = sampleTender(valueMin = BigDecimal("500001"), valueMax = BigDecimal("800000"))
        assertFalse(matching.matches(tender, profile))
    }

    @Test
    fun `matches keyword found only in title, not in tender keywords`() {
        val profile = sampleProfile(keywords = setOf("consumables"))
        val tender = sampleTender(
            title = "Supply and Delivery of Computer Consumables",
            keywords = setOf("computers", "printers")
        )
        assertTrue(matching.matches(tender, profile))
    }

    @Test
    fun `matches keyword case-insensitively`() {
        val profile = sampleProfile(keywords = setOf("NETWORK"))
        val tender = sampleTender(title = "Supply of network switches", keywords = setOf("Switches"))
        assertTrue(matching.matches(tender, profile))
    }

    @Test
    fun `matches issuing authority case-insensitively`() {
        val profile = sampleProfile(authority = "cape town")
        val tender = sampleTender(authority = "CITY OF CAPE TOWN")
        assertTrue(matching.matches(tender, profile))
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
