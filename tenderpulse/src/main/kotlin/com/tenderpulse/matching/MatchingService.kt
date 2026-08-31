package com.tenderpulse.matching

import com.tenderpulse.domain.InterestProfile
import com.tenderpulse.domain.Tender
import org.springframework.stereotype.Service
import java.math.BigDecimal

/**
 * Matches incoming tenders against subscriber interest profiles.
 *
 * Matching rules (all must pass when the corresponding profile field is set):
 * - Sector: tender.sector is in profile.sectors (if profile has sectors)
 * - Value range: tender value overlaps profile valueMin/valueMax
 * - Issuing authority: profile substring appears in tender.issuingAuthority (case-insensitive)
 * - Region: exact or contains match (case-insensitive)
 * - Keywords: at least one profile keyword appears in title, description, or tender keywords
 */
@Service
class MatchingService {

    fun matches(tender: Tender, profile: InterestProfile): Boolean {
        if (!profile.active) return false

        if (profile.sectors.isNotEmpty() && tender.sector !in profile.sectors) {
            return false
        }

        if (!valueOverlaps(tender, profile)) return false

        profile.issuingAuthorityContains?.takeIf { it.isNotBlank() }?.let { needle ->
            if (!tender.issuingAuthority.contains(needle, ignoreCase = true)) return false
        }

        profile.region?.takeIf { it.isNotBlank() }?.let { region ->
            val tenderRegion = tender.region ?: return false
            if (!tenderRegion.contains(region, ignoreCase = true) &&
                !region.contains(tenderRegion, ignoreCase = true)
            ) {
                return false
            }
        }

        if (profile.keywords.isNotEmpty() && !keywordHit(tender, profile.keywords)) {
            return false
        }

        return true
    }

    private fun valueOverlaps(tender: Tender, profile: InterestProfile): Boolean {
        val pMin = profile.valueMin
        val pMax = profile.valueMax
        if (pMin == null && pMax == null) return true

        val tMin = tender.valueMin ?: tender.valueMax
        val tMax = tender.valueMax ?: tender.valueMin
        if (tMin == null && tMax == null) return true // no value on tender → do not exclude

        val effectiveTMin = tMin ?: BigDecimal.ZERO
        val effectiveTMax = tMax ?: effectiveTMin

        if (pMax != null && effectiveTMin > pMax) return false
        if (pMin != null && effectiveTMax < pMin) return false
        return true
    }

    private fun keywordHit(tender: Tender, keywords: Set<String>): Boolean {
        val haystack = buildString {
            append(tender.title.lowercase())
            append(' ')
            append(tender.description?.lowercase().orEmpty())
            append(' ')
            tender.keywords.forEach { append(it.lowercase()).append(' ') }
        }
        return keywords.any { haystack.contains(it.lowercase()) }
    }
}
