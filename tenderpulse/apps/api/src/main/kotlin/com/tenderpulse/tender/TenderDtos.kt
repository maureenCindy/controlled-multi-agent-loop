package com.tenderpulse.tender

import com.tenderpulse.domain.Sector
import com.tenderpulse.domain.Tender
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Response shape for tender endpoints (TP-052). Mirrors every field on [Tender] one-for-one —
 * this is already public summary data (see `docs/zw-tender-sources.md`: public summary fields
 * + official link only), so unlike [com.tenderpulse.subscriber.InterestProfileResponse] there is
 * no PII to strip here. The DTO exists so the API's public contract is decoupled from the JPA
 * entity, same as [com.tenderpulse.subscriber.SubscriberResponse] (TP-037).
 */
data class TenderResponse(
    val id: UUID,
    val title: String,
    val description: String?,
    val sector: Sector,
    val valueMin: BigDecimal?,
    val valueMax: BigDecimal?,
    val issuingAuthority: String,
    val region: String?,
    val sourceUrl: String,
    val sourceName: String,
    val publishedAt: Instant,
    val deadline: Instant?,
    val externalTenderId: String?,
    val currency: String?,
    val keywords: Set<String>,
    val createdAt: Instant
) {
    companion object {
        fun from(tender: Tender): TenderResponse = TenderResponse(
            id = tender.id,
            title = tender.title,
            description = tender.description,
            sector = tender.sector,
            valueMin = tender.valueMin,
            valueMax = tender.valueMax,
            issuingAuthority = tender.issuingAuthority,
            region = tender.region,
            sourceUrl = tender.sourceUrl,
            sourceName = tender.sourceName,
            publishedAt = tender.publishedAt,
            deadline = tender.deadline,
            externalTenderId = tender.externalTenderId,
            currency = tender.currency,
            keywords = tender.keywords,
            createdAt = tender.createdAt
        )
    }
}
