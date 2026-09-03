package com.tenderpulse.aggregation

import com.tenderpulse.domain.*
import com.tenderpulse.matching.MatchingService
import com.tenderpulse.notification.NotificationService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class AggregationServiceTest {

    private lateinit var tenderRepository: TenderRepository
    private lateinit var notificationService: NotificationService
    private lateinit var aggregationService: AggregationService
    private var testSource: TestTenderSource = TestTenderSource()

    @BeforeEach
    fun setUp() {
        tenderRepository = mockk()
        notificationService = mockk()
        testSource = TestTenderSource()

        aggregationService = AggregationService(
            sources = listOf(testSource),
            tenderRepository = tenderRepository,
            matchingService = mockk(),
            notificationService = notificationService,
            scheduledEnabled = false
        )
    }

    @Test
    fun `empty source returns zero fetched and stored`() {
        // Given
        testSource.tenders = emptyList()
        every { tenderRepository.findBySourceUrl(any()) } returns null

        // When
        val result = aggregationService.runAggregationCycle()

        // Then
        assertEquals(0, result.fetched)
        assertEquals(0, result.stored)
        assertEquals(0, result.notificationsSent)
        verify(exactly = 0) { tenderRepository.save(any()) }
    }

    @Test
    fun `new tenders are stored and notifications sent`() {
        // Given
        val tender1 = Tender(
            title = "Supply of office equipment",
            issuingAuthority = "Ministry of Finance",
            sourceUrl = "https://example.gov/tender/1",
            sourceName = "test-source",
            sector = Sector.IT,
            valueMin = BigDecimal("50000"),
            valueMax = BigDecimal("100000"),
            region = "Harare",
            keywords = mutableSetOf("office", "equipment")
        )

        val tender2 = Tender(
            title = "Construction services",
            issuingAuthority = "Ministry of Infrastructure",
            sourceUrl = "https://example.gov/tender/2",
            sourceName = "test-source",
            sector = Sector.CONSTRUCTION,
            valueMin = BigDecimal("500000"),
            valueMax = BigDecimal("1000000"),
            region = "Bulawayo",
            keywords = mutableSetOf("construction")
        )

        testSource.tenders = listOf(tender1, tender2)

        every { tenderRepository.findBySourceUrl(tender1.sourceUrl) } returns null
        every { tenderRepository.findBySourceUrl(tender2.sourceUrl) } returns null
        every { tenderRepository.save(any()) } answers { it.invocation.args[0] as Tender }
        every { notificationService.notifyMatchingSubscribers(tender1) } returns 1
        every { notificationService.notifyMatchingSubscribers(tender2) } returns 2

        // When
        val result = aggregationService.runAggregationCycle()

        // Then
        assertEquals(2, result.fetched)
        assertEquals(2, result.stored)
        assertEquals(3, result.notificationsSent) // 1 + 2
        verify(exactly = 2) { tenderRepository.save(any()) }
        verify(exactly = 2) { notificationService.notifyMatchingSubscribers(any()) }
    }

    @Test
    fun `duplicate tenders by sourceUrl are skipped`() {
        // Given
        val tender = Tender(
            title = "New Tender",
            issuingAuthority = "Test Authority",
            sourceUrl = "https://example.gov/tender/1",
            sourceName = "test-source"
        )

        val existingTender = tender.copy(id = UUID.randomUUID())

        testSource.tenders = listOf(tender)
        every { tenderRepository.findBySourceUrl(tender.sourceUrl) } returns existingTender

        // When
        val result = aggregationService.runAggregationCycle()

        // Then
        assertEquals(1, result.fetched)
        assertEquals(0, result.stored)
        assertEquals(0, result.notificationsSent)
        verify(exactly = 0) { tenderRepository.save(any()) }
        verify(exactly = 0) { notificationService.notifyMatchingSubscribers(any()) }
    }

    @Test
    fun `source errors are logged and cycle continues`() {
        // Given
        val goodTender = Tender(
            title = "Good Tender",
            issuingAuthority = "Test Authority",
            sourceUrl = "https://example.gov/tender/good",
            sourceName = "good-source"
        )

        val goodSource = TestTenderSource()
        goodSource.tenders = listOf(goodTender)

        val badSource = object : TenderSource {
            override val name: String = "bad-source"
            override fun fetchNewNotices(): List<Tender> {
                throw RuntimeException("Network error")
            }
        }

        val serviceWithMultipleSources = AggregationService(
            sources = listOf(badSource, goodSource),
            tenderRepository = tenderRepository,
            matchingService = mockk(),
            notificationService = notificationService,
            scheduledEnabled = false
        )

        every { tenderRepository.findBySourceUrl(goodTender.sourceUrl) } returns null
        every { tenderRepository.save(any()) } answers { it.invocation.args[0] as Tender }
        every { notificationService.notifyMatchingSubscribers(goodTender) } returns 0

        // When
        val result = serviceWithMultipleSources.runAggregationCycle()

        // Then
        assertEquals(1, result.fetched) // only from good source
        assertEquals(1, result.stored)
        assertEquals(0, result.notificationsSent)
        verify(exactly = 1) { tenderRepository.save(any()) }
    }

    @Test
    fun `partial notification counts are recorded correctly`() {
        // Given
        val tender = Tender(
            title = "Test Tender",
            issuingAuthority = "Test Authority",
            sourceUrl = "https://example.gov/tender/1",
            sourceName = "test-source"
        )

        testSource.tenders = listOf(tender)

        every { tenderRepository.findBySourceUrl(tender.sourceUrl) } returns null
        every { tenderRepository.save(any()) } answers { it.invocation.args[0] as Tender }
        every { notificationService.notifyMatchingSubscribers(tender) } returns 2

        // When
        val result = aggregationService.runAggregationCycle()

        // Then
        assertEquals(1, result.fetched)
        assertEquals(1, result.stored)
        assertEquals(2, result.notificationsSent)
    }

    /**
     * Simple test implementation of TenderSource for testing.
     */
    class TestTenderSource : TenderSource {
        override val name: String = "test-source"
        var tenders: List<Tender> = emptyList()

        override fun fetchNewNotices(): List<Tender> = tenders
    }
}
