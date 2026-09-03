package com.tenderpulse.aggregation

import com.tenderpulse.domain.Tender

/**
 * Abstraction over official tender sources (APIs, RSS, scrapers).
 * Implementations fetch raw notices and map them into the normalised [Tender] model.
 */
interface TenderSource {
    val name: String
    fun fetchNewNotices(): List<Tender>
}

/**
 * Stub source for local development and tests.
 * Returns a fixed set of sample tenders.
 */
class SampleTenderSource : TenderSource {
    override val name: String = "sample-source"

    override fun fetchNewNotices(): List<Tender> = emptyList() // populated by tests / seed data
}
