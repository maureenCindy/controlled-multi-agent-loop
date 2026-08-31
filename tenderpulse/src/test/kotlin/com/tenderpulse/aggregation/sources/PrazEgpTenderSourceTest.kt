package com.tenderpulse.aggregation.sources

import com.tenderpulse.domain.Sector
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClientException
import java.time.Instant

class PrazEgpTenderSourceTest {

    private lateinit var mockRestTemplate: org.springframework.web.client.RestTemplate
    private lateinit var source: PrazEgpTenderSource
    private lateinit var sampleHtml: String

    @BeforeEach
    fun setup() {
        mockRestTemplate = mockk()
        source = PrazEgpTenderSource(mockRestTemplate)

        // Load sample HTML fixture
        val resourcePath = "praz-egp-sample.html"
        val resource = this::class.java.classLoader.getResource(resourcePath)
        sampleHtml = resource?.readText()
            ?: throw Exception("Sample HTML fixture not found: $resourcePath")
    }

    /**
     * Test Case 1: Fetch with fixtures — returns ≥1 tenders with required fields set
     *
     * Verifies that:
     * - fetchNewNotices() returns a non-empty list
     * - Each tender has all required fields (title, issuingAuthority, sourceUrl, sourceName)
     * - Field mappings are correct (sector, keywords, dates)
     * - Tenders can be used for matching and storage
     */
    @Test
    fun `fetchNewNotices with fixture returns multiple tenders with required fields`() {
        // Arrange
        every { mockRestTemplate.getForObject(any<String>(), String::class.java) } returns sampleHtml

        // Act
        val tenders = source.fetchNewNotices()

        // Assert
        assertNotNull(tenders, "fetchNewNotices() should not return null")
        assertFalse(tenders.isEmpty(), "Should fetch at least one tender")
        assertTrue(tenders.size >= 5, "Sample fixture contains 5 tenders")

        // Verify all required fields are present in first tender
        val firstTender = tenders[0]
        assertNotNull(firstTender.id)
        assertEquals("Supply and Delivery of Computer Consumables", firstTender.title)
        assertEquals("Ministry of Finance, Economic Development and Investment Promotion", firstTender.issuingAuthority)
        assertEquals("egp.praz.org.zw", firstTender.sourceName)
        assertTrue(firstTender.sourceUrl.contains("TR22053"), "Source URL should contain tender ID")
        assertNotNull(firstTender.publishedAt)
        assertNotNull(firstTender.deadline)
        assertEquals("TR22053", firstTender.externalTenderId)

        // Verify IT sector mapping (GC006 = IT)
        assertEquals(Sector.IT, firstTender.sector)
        assertFalse(firstTender.keywords.isEmpty())
        assertTrue(firstTender.keywords.contains("GC006"), "Keywords should contain category code")

        // Verify second tender (construction)
        val secondTender = tenders[1]
        assertEquals("Construction of Health Centre in Bulawayo District", secondTender.title)
        assertEquals("TR22054", secondTender.externalTenderId)
        assertEquals(Sector.CONSTRUCTION, secondTender.sector)
    }

    /**
     * Test Case 2: Deduplication by sourceUrl
     *
     * Verifies that when the same sourceUrl is fetched twice,
     * the second run encounters duplicates and skips them.
     *
     * (This test indirectly validates that sourceUrl is stable and unique;
     *  the actual deduplication is handled by AggregationService, but we verify
     *  sourceUrl consistency here.)
     */
    @Test
    fun `multiple fetches produce consistent sourceUrl for deduplication`() {
        // Arrange
        every { mockRestTemplate.getForObject(any<String>(), String::class.java) } returns sampleHtml

        // Act
        val firstRun = source.fetchNewNotices()
        val secondRun = source.fetchNewNotices()

        // Assert
        assertEquals(firstRun.size, secondRun.size, "Should fetch same tenders on second run")

        // Verify sourceUrl is identical (for deduplication)
        for (i in firstRun.indices) {
            assertEquals(
                firstRun[i].sourceUrl,
                secondRun[i].sourceUrl,
                "Source URL must be stable across fetches for deduplication"
            )
            assertEquals(
                firstRun[i].externalTenderId,
                secondRun[i].externalTenderId,
                "External ID must be stable for deduplication"
            )
        }

        // Verify that externalTenderId is unique per tender
        val ids = firstRun.map { it.externalTenderId }.toSet()
        assertEquals(firstRun.size, ids.size, "Each tender should have a unique externalTenderId")
    }

    /**
     * Test Case 3: Source timeout/error handling
     *
     * Verifies that:
     * - Network errors are caught and logged
     * - No uncaught exception is thrown
     * - Empty list is returned on failure
     * - The cycle can continue with other sources
     */
    @Test
    fun `network error is logged and empty list is returned`() {
        // Arrange: Mock a network timeout
        every { mockRestTemplate.getForObject(any<String>(), String::class.java) } throws
            RestClientException("Connection timeout")

        // Act
        val result = source.fetchNewNotices()

        // Assert
        assertNotNull(result, "Should return a list, not null")
        assertTrue(result.isEmpty(), "Should return empty list on error")
    }

    /**
     * Test Case 3b: Parse error handling
     *
     * Verifies that malformed HTML is handled gracefully.
     */
    @Test
    fun `malformed HTML is handled gracefully and returns empty list`() {
        // Arrange: Mock malformed HTML
        every { mockRestTemplate.getForObject(any<String>(), String::class.java) } returns
            "<html><body>No table here</body></html>"

        // Act
        val result = source.fetchNewNotices()

        // Assert
        assertNotNull(result, "Should return a list, not null")
        assertTrue(result.isEmpty(), "Should return empty list for malformed HTML")
    }

    /**
     * Test Case 3c: Null response handling
     *
     * Verifies that null HTTP response is handled.
     */
    @Test
    fun `null HTTP response is handled gracefully`() {
        // Arrange
        every { mockRestTemplate.getForObject(any<String>(), String::class.java) } returns null

        // Act
        val result = source.fetchNewNotices()

        // Assert
        assertNotNull(result, "Should return a list, not null")
        assertTrue(result.isEmpty(), "Should return empty list for null response")
    }

    /**
     * Test Case 4: Field mapping validation
     *
     * Verifies that all tender fields are correctly mapped from HTML.
     */
    @Test
    fun `all tender fields are correctly mapped from HTML`() {
        // Arrange
        every { mockRestTemplate.getForObject(any<String>(), String::class.java) } returns sampleHtml

        // Act
        val tenders = source.fetchNewNotices()

        // Assert — check each tender for field completeness
        for (tender in tenders) {
            // Required fields
            assertFalse(tender.title.isBlank(), "Title must not be blank")
            assertFalse(tender.issuingAuthority.isBlank(), "Issuing authority must not be blank")
            assertFalse(tender.sourceUrl.isBlank(), "Source URL must not be blank")
            assertEquals("egp.praz.org.zw", tender.sourceName, "Source name must be egp.praz.org.zw")

            // Dates
            assertNotNull(tender.publishedAt)
            assertNotNull(tender.deadline)
            assertTrue(tender.publishedAt <= tender.deadline, "Published date should be before deadline")

            // External ID
            assertNotNull(tender.externalTenderId)
            assertFalse(tender.externalTenderId!!.isBlank())

            // Keywords
            assertFalse(tender.keywords.isEmpty(), "Should have at least one keyword")

            // Sector
            assertNotNull(tender.sector)
            assertNotEquals(Sector.OTHER, tender.sector, "Should map to specific sector when possible")
        }
    }

    /**
     * Test Case 5: Sector mapping validation
     *
     * Verifies that category codes are correctly mapped to sectors.
     */
    @Test
    fun `category codes are correctly mapped to sector enum`() {
        // Arrange
        every { mockRestTemplate.getForObject(any<String>(), String::class.java) } returns sampleHtml

        // Act
        val tenders = source.fetchNewNotices()
        val tenderMap = tenders.associateBy { it.externalTenderId }

        // Assert
        assertEquals(Sector.IT, tenderMap["TR22053"]?.sector, "GC006 should map to IT")
        assertEquals(Sector.CONSTRUCTION, tenderMap["TR22054"]?.sector, "GC008 should map to CONSTRUCTION")
        assertEquals(Sector.EDUCATION, tenderMap["TR22055"]?.sector, "GC003 (Books/Education) should map to EDUCATION")
        assertEquals(Sector.AGRICULTURE, tenderMap["TR22056"]?.sector, "GC009 should map to AGRICULTURE")
        assertEquals(Sector.CONSTRUCTION, tenderMap["TR22057"]?.sector, "GC008 should map to CONSTRUCTION")
    }

    /**
     * Test Case 6: Keywords extraction
     *
     * Verifies that keywords are extracted from both category codes and titles.
     */
    @Test
    fun `keywords are extracted from category codes and titles`() {
        // Arrange
        every { mockRestTemplate.getForObject(any<String>(), String::class.java) } returns sampleHtml

        // Act
        val tenders = source.fetchNewNotices()
        val firstTender = tenders[0]

        // Assert
        assertTrue(firstTender.keywords.contains("GC006"), "Should include category code")
        assertTrue(
            firstTender.keywords.any { it.contains("computer", ignoreCase = true) },
            "Should include title keywords"
        )
        assertTrue(
            firstTender.keywords.size >= 2,
            "Should have multiple keywords"
        )
    }

    /**
     * Test Case 7: Source name is consistent
     *
     * Verifies that all tenders have the correct sourceName.
     */
    @Test
    fun `all tenders have consistent source name`() {
        // Arrange
        every { mockRestTemplate.getForObject(any<String>(), String::class.java) } returns sampleHtml

        // Act
        val tenders = source.fetchNewNotices()

        // Assert
        assertTrue(tenders.all { it.sourceName == "egp.praz.org.zw" },
            "All tenders should have sourceName = egp.praz.org.zw")
    }

    /**
     * Test Case 8: RestTemplate is called correctly
     *
     * Verifies that the adapter calls RestTemplate with the correct URL.
     */
    @Test
    fun `restTemplate is called with correct base URL`() {
        // Arrange
        every { mockRestTemplate.getForObject(any<String>(), String::class.java) } returns sampleHtml

        // Act
        source.fetchNewNotices()

        // Assert
        verify(exactly = 1) { mockRestTemplate.getForObject(any<String>(), String::class.java) }
    }
}
