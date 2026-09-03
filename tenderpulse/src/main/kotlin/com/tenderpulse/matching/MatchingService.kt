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
 * - Region/province: whole-word token match (case-insensitive, punctuation-insensitive).
 *   One side's word set must contain the other's, so "Harare" matches "Harare Province"
 *   and vice versa, while a fragment like "land" does not match "Mashonaland East".
 * - Keywords: at least one profile keyword appears in title, description, or tender keywords
 *   (case-insensitive)
 *
 * Empty filters match all: any profile filter field that is null, blank, or an empty
 * collection (sectors, valueMin/valueMax, issuingAuthorityContains, region, keywords) is
 * treated as unrestricted for that dimension — it never causes a rejection. A profile with
 * every filter empty matches every tender.
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
            if (!regionMatches(tenderRegion, region)) return false
        }

        if (profile.keywords.isNotEmpty() && !keywordHit(tender, profile.keywords)) {
            return false
        }

        return true
    }

    /**
     * Whole-word region comparison. Both sides are lowercased and split on any non-alphanumeric
     * character, then compared as word sets: a match requires one set to contain the other.
     *
     * This keeps the bidirectional behaviour callers rely on ("Harare" on the profile matches a
     * "Harare Province" tender and the reverse) while rejecting fragment overlaps: a profile
     * region of "land" no longer matches "Mashonaland East" or "Matabeleland North", and
     * "Mashonaland East" no longer matches "Mashonaland West".
     *
     * A profile region with no word characters at all (e.g. "--") carries no constraint and
     * matches everything, consistent with how blank filters are treated.
     */
    private fun regionMatches(tenderRegion: String, profileRegion: String): Boolean {
        val profileWords = regionWords(profileRegion)
        if (profileWords.isEmpty()) return true
        val tenderWords = regionWords(tenderRegion)
        if (tenderWords.isEmpty()) return false
        return tenderWords.containsAll(profileWords) || profileWords.containsAll(tenderWords)
    }

    private fun regionWords(region: String): Set<String> =
        region.lowercase().split(NON_WORD).filter { it.isNotEmpty() }.toSet()

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

    private companion object {
        val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    }
}
