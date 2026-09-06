package com.tenderpulse.notification

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Drift-guard for TP-092 (issue #103)'s final acceptance criterion / test case: the
 * template-constants recorded in [WhatsAppTemplates.kt][com.tenderpulse.notification] must keep
 * matching what Meta actually approved, as documented in
 * `tenderpulse/docs/templates/notification/whatsapp/tender_match_alert.md` and
 * `tender_deadline_reminder.md`.
 *
 * The expected values below are transcribed literally from those two `.md` files (Meta-approved
 * 2026-09-06) rather than derived from [WhatsAppTemplateParams.PLACEHOLDER_ORDER] itself — if
 * this test instead compared the production constant against itself, it could never fail no
 * matter how the constant was edited. Reusing the shared production list here would defeat the
 * point of a drift guard: a future edit to [WhatsAppTemplateParams.PLACEHOLDER_ORDER] (e.g.
 * adding, removing, or reordering a placeholder) must fail one of these assertions.
 */
class WhatsAppTemplatesTest {

    // Transcribed from the "Placeholders" table shared verbatim by both approved template docs.
    private val approvedPlaceholderOrder = listOf(
        "matched interest profile name",
        "tender title",
        "issuing authority",
        "deadline",
        "official tender link",
    )
    private val approvedPlaceholderCount = 5
    private val approvedLanguageCode = "en_US"

    @Test
    fun `tender_match_alert matches the name, language, and placeholder count Meta approved`() {
        val template = WhatsAppTemplate.TENDER_MATCH_ALERT

        assertEquals("tender_match_alert", template.templateName)
        assertEquals(approvedLanguageCode, template.languageCode)
        assertEquals(approvedPlaceholderCount, template.placeholderCount)
    }

    @Test
    fun `tender_deadline_reminder matches the name, language, and placeholder count Meta approved`() {
        val template = WhatsAppTemplate.TENDER_DEADLINE_REMINDER

        assertEquals("tender_deadline_reminder", template.templateName)
        assertEquals(approvedLanguageCode, template.languageCode)
        assertEquals(approvedPlaceholderCount, template.placeholderCount)
    }

    @Test
    fun `shared placeholder order matches the approved template docs exactly, including order`() {
        assertEquals(approvedPlaceholderOrder, WhatsAppTemplateParams.PLACEHOLDER_ORDER)
    }

    @Test
    fun `WhatsAppTemplateParams produces the 5 approved placeholder values in {{1}} to {{5}} order`() {
        val params = WhatsAppTemplateParams(
            matchedProfileName = "IT Tenders Harare",
            tenderTitle = "Supply and Delivery of Networking Equipment",
            issuingAuthority = "Ministry of ICT, Postal and Courier Services",
            deadline = "15 September 2026",
            officialTenderLink = "https://egp.praz.org.zw/tender/12345",
        )

        val orderedValues = params.toOrderedValues()

        assertEquals(
            listOf(
                "IT Tenders Harare",
                "Supply and Delivery of Networking Equipment",
                "Ministry of ICT, Postal and Courier Services",
                "15 September 2026",
                "https://egp.praz.org.zw/tender/12345",
            ),
            orderedValues,
        )
        assertEquals(approvedPlaceholderCount, orderedValues.size)
        assertEquals(WhatsAppTemplate.TENDER_MATCH_ALERT.placeholderCount, orderedValues.size)
        assertEquals(WhatsAppTemplate.TENDER_DEADLINE_REMINDER.placeholderCount, orderedValues.size)
    }
}
