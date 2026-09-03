package com.tenderpulse.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class TenderSchemaTest {

    /**
     * Test Case 1: Sample ZW tender from PRAZ e-GP with all required fields
     *
     * Maps a real e-GP bulletin notice to the Tender schema.
     * Reference: Supply and Delivery of Computer Consumables (TR22053)
     */
    @Test
    fun `sample ZW tender maps with all required fields`() {
        val publishedAt = Instant.parse("2026-08-31T10:00:00Z")
        val deadline = Instant.parse("2026-09-15T15:00:00Z")

        val zwTender = Tender(
            id = UUID.randomUUID(),
            title = "Supply and Delivery of Computer Consumables",
            description = "Supply and delivery of computer consumables including printers, photocopiers, networking equipment.",
            sector = Sector.IT,
            issuingAuthority = "Ministry of Finance, Economic Development and Investment Promotion",
            externalTenderId = "TR22053",
            publishedAt = publishedAt,
            deadline = deadline,
            sourceUrl = "https://egp.praz.org.zw/tenders/2026/TR22053",
            sourceName = "egp.praz.org.zw",
            valueMin = BigDecimal("100000"),
            valueMax = BigDecimal("500000"),
            currency = "USD",
            region = "Harare",
            keywords = mutableSetOf("GC006", "computers", "printers", "networking", "equipment")
        )

        // Verify all required fields are present
        assertNotNull(zwTender.id)
        assertEquals("Supply and Delivery of Computer Consumables", zwTender.title)
        assertEquals("Ministry of Finance, Economic Development and Investment Promotion", zwTender.issuingAuthority)
        assertEquals("TR22053", zwTender.externalTenderId)
        assertEquals(publishedAt, zwTender.publishedAt)
        assertEquals(deadline, zwTender.deadline)
        assertEquals("https://egp.praz.org.zw/tenders/2026/TR22053", zwTender.sourceUrl)
        assertEquals("egp.praz.org.zw", zwTender.sourceName)

        // Verify optional fields present in sample
        assertEquals(Sector.IT, zwTender.sector)
        assertEquals(BigDecimal("100000"), zwTender.valueMin)
        assertEquals(BigDecimal("500000"), zwTender.valueMax)
        assertEquals("USD", zwTender.currency)
        assertEquals("Harare", zwTender.region)
        assertTrue(zwTender.keywords.contains("GC006"))
        assertTrue(zwTender.keywords.contains("computers"))
    }

    /**
     * Test Case 2: Tender without deadline is stored and matchable
     *
     * Some e-GP notices may not have a closing date set initially.
     * Schema must accept null deadline and not break matching.
     */
    @Test
    fun `tender without deadline is stored and usable`() {
        val publishedAt = Instant.parse("2026-08-31T10:00:00Z")

        val tenderNoDeadline = Tender(
            id = UUID.randomUUID(),
            title = "Supply of Office Equipment",
            issuingAuthority = "Ministry of Education",
            externalTenderId = "TR22054",
            publishedAt = publishedAt,
            deadline = null, // No deadline set
            sourceUrl = "https://egp.praz.org.zw/tenders/2026/TR22054",
            sourceName = "egp.praz.org.zw",
            sector = Sector.EDUCATION,
            keywords = mutableSetOf("office", "equipment")
        )

        // Verify required fields present
        assertNotNull(tenderNoDeadline.id)
        assertEquals("Supply of Office Equipment", tenderNoDeadline.title)
        assertEquals("Ministry of Education", tenderNoDeadline.issuingAuthority)
        assertEquals("TR22054", tenderNoDeadline.externalTenderId)

        // Verify deadline is nullable
        assertNull(tenderNoDeadline.deadline)

        // Verify schema still valid for matching (has title, sector, authority, keywords)
        assertEquals(Sector.EDUCATION, tenderNoDeadline.sector)
        assertFalse(tenderNoDeadline.keywords.isEmpty())
    }

    /**
     * Test Case 3: Currency field for ZW tender (USD or ZWL)
     *
     * Zimbabwe tenders may reference USD (more common for international bids)
     * or ZWL (local currency). Currency field captures this.
     */
    @Test
    fun `currency field supports ZW tenders in USD and ZWL`() {
        val usdTender = Tender(
            title = "Construction of Hospital",
            issuingAuthority = "Ministry of Health",
            sourceUrl = "https://egp.praz.org.zw/t1",
            sourceName = "egp.praz.org.zw",
            valueMin = BigDecimal("5000000"),
            valueMax = BigDecimal("10000000"),
            currency = "USD"
        )

        val zwlTender = Tender(
            title = "Supply of Medical Supplies",
            issuingAuthority = "Ministry of Health",
            sourceUrl = "https://egp.praz.org.zw/t2",
            sourceName = "egp.praz.org.zw",
            valueMin = BigDecimal("50000000"),
            valueMax = BigDecimal("100000000"),
            currency = "ZWL"
        )

        assertEquals("USD", usdTender.currency)
        assertEquals("ZWL", zwlTender.currency)
    }

    /**
     * Test Case 4: External tender ID enables deduplication
     *
     * e-GP reference number (e.g., TR22053) must be stored to prevent
     * duplicate imports on re-run.
     */
    @Test
    fun `external tender ID stored for deduplication`() {
        val tender1 = Tender(
            title = "IT Equipment",
            issuingAuthority = "Ministry of ICT",
            sourceUrl = "https://egp.praz.org.zw/tenders/2026/TR22055",
            sourceName = "egp.praz.org.zw",
            externalTenderId = "TR22055"
        )

        val tender2 = Tender(
            title = "IT Equipment",
            issuingAuthority = "Ministry of ICT",
            sourceUrl = "https://egp.praz.org.zw/tenders/2026/TR22055",
            sourceName = "egp.praz.org.zw",
            externalTenderId = "TR22055"
        )

        // Same external ID means same tender
        assertEquals(tender1.externalTenderId, tender2.externalTenderId)
    }

    /**
     * Test Case 5: Sector enum supports ZW categories
     *
     * e-GP category codes (e.g., GC006 for IT) map to Sector enum.
     */
    @Test
    fun `sector enum provides ZW-ready categories`() {
        // Verify all sectors are available for ZW tenders
        assertNotNull(Sector.IT)
        assertNotNull(Sector.CONSTRUCTION)
        assertNotNull(Sector.HEALTHCARE)
        assertNotNull(Sector.EDUCATION)
        assertNotNull(Sector.TRANSPORT)
        assertNotNull(Sector.ENERGY)
        assertNotNull(Sector.AGRICULTURE)
        assertNotNull(Sector.OTHER)

        val sectors = Sector.values()
        assertTrue(sectors.size >= 8, "Expected at least 8 sectors")
    }

    /**
     * Test Case 6: All fields have safe defaults / nullability
     *
     * Schema is backward-compatible; new fields don't break existing code.
     */
    @Test
    fun `schema provides safe defaults for all optional fields`() {
        val minimalTender = Tender(
            title = "Generic Tender",
            issuingAuthority = "Generic Authority",
            sourceUrl = "https://example.com/tender/1",
            sourceName = "example.com"
            // All other fields use defaults
        )

        // Defaults and nulls are applied
        assertEquals(Sector.OTHER, minimalTender.sector)
        assertNull(minimalTender.description)
        assertNull(minimalTender.deadline)
        assertNull(minimalTender.externalTenderId)
        assertNull(minimalTender.currency)
        assertNull(minimalTender.valueMin)
        assertNull(minimalTender.valueMax)
        assertNull(minimalTender.region)
        assertTrue(minimalTender.keywords.isEmpty())
        assertNotNull(minimalTender.publishedAt)
        assertNotNull(minimalTender.createdAt)
    }
}
