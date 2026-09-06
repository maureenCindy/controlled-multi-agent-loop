package com.tenderpulse.notification

/**
 * Approved Meta WhatsApp message templates for the WhatsApp notification channel (TP-092,
 * issue #103), mirroring exactly what Meta Business Manager approved for the two templates
 * documented in `tenderpulse/docs/templates/notification/whatsapp/`:
 * `tender_match_alert.md` and `tender_deadline_reminder.md`.
 *
 * This file is the source of truth for the *code-facing* name/language/placeholder-count of
 * each approved template — not for wording, which lives in the `.md` docs and in Meta Business
 * Manager itself. [WhatsAppTemplatesPlaceholderDriftTest] guards against a silent edit here
 * (e.g. adding/removing a placeholder) diverging from what those docs record without the
 * change being deliberate.
 *
 * Both templates share the same 5 placeholders, in the same order, so [WhatsAppTemplateParams]
 * is intentionally a single shared shape usable by either template's sender (TP-094/TP-095)
 * without redesign.
 *
 * Out of scope here: the actual WhatsApp Business API client/sender, and any dispatch-loop
 * wiring — that is TP-094/TP-095.
 */
enum class WhatsAppTemplate(
    /** Exact template name as approved in Meta Business Manager. */
    val templateName: String,
    /** Exact language code as approved in Meta Business Manager. */
    val languageCode: String,
) {
    /**
     * Sent immediately when a Paid, opted-in subscriber's interest profile matches a new tender
     * (TP-094).
     */
    TENDER_MATCH_ALERT(
        templateName = "tender_match_alert",
        languageCode = "en_US",
    ),

    /**
     * Sent when a Paid, opted-in subscriber's previously-matched tender is approaching its
     * deadline (TP-095).
     */
    TENDER_DEADLINE_REMINDER(
        templateName = "tender_deadline_reminder",
        languageCode = "en_US",
    ),
    ;

    /** Number of ordered placeholders this template expects — see [WhatsAppTemplateParams]. */
    val placeholderCount: Int
        get() = WhatsAppTemplateParams.PLACEHOLDER_ORDER.size
}

/**
 * The 5 ordered placeholders shared by every [WhatsAppTemplate], and a typed carrier for their
 * values so callers (TP-094/TP-095) cannot accidentally pass them out of order.
 *
 * Order matches the approved templates' `{{1}}`..`{{5}}` exactly:
 * 1. Matched interest profile name
 * 2. Tender title
 * 3. Issuing authority
 * 4. Deadline
 * 5. Official tender link
 */
data class WhatsAppTemplateParams(
    val matchedProfileName: String,
    val tenderTitle: String,
    val issuingAuthority: String,
    val deadline: String,
    val officialTenderLink: String,
) {
    /** Ordered values matching each template's `{{1}}`..`{{5}}` placeholders, in order. */
    fun toOrderedValues(): List<String> = listOf(
        matchedProfileName,
        tenderTitle,
        issuingAuthority,
        deadline,
        officialTenderLink,
    )

    companion object {
        /**
         * Self-documenting description of what each of the 5 shared placeholders represents,
         * in `{{1}}`..`{{5}}` order. Used by [WhatsAppTemplate.placeholderCount] and by the
         * drift-guard test to assert both approved templates still expect exactly this shape.
         */
        val PLACEHOLDER_ORDER: List<String> = listOf(
            "matched interest profile name",
            "tender title",
            "issuing authority",
            "deadline",
            "official tender link",
        )
    }
}
