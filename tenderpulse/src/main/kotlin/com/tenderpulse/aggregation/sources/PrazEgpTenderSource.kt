package com.tenderpulse.aggregation.sources

import com.tenderpulse.aggregation.TenderSource
import com.tenderpulse.domain.Sector
import com.tenderpulse.domain.Tender
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.RestTemplate
import org.springframework.web.client.RestClientException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Tender source adapter for PRAZ e-GP bulletin board.
 *
 * Fetches open tenders from https://egp.praz.org.zw/ by scraping the public bulletin board.
 * Parses HTML table rows into normalised Tender objects.
 *
 * Errors are logged; fetching returns an empty list on failure (no uncaught exception).
 */
class PrazEgpTenderSource(
    private val restTemplate: RestTemplate,
    private val baseUrl: String = "https://egp.praz.org.zw/"
) : TenderSource {
    override val name: String = "egp.praz.org.zw"

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Fetches new tenders from the PRAZ e-GP bulletin board.
     *
     * Returns a list of Tender objects parsed from the HTML bulletin.
     * On network error or parse failure, logs the error and returns an empty list.
     *
     * @return List of parsed tenders, or empty list on failure
     */
    override fun fetchNewNotices(): List<Tender> {
        return try {
            val html = fetchBulletinHtml()
            parseTenders(html)
        } catch (e: RestClientException) {
            log.error("Failed to fetch PRAZ e-GP bulletin: {}", e.message)
            emptyList()
        } catch (e: Exception) {
            log.error("Error parsing PRAZ e-GP bulletin: {}", e.message, e)
            emptyList()
        }
    }

    /**
     * Fetches the HTML content of the PRAZ e-GP bulletin board.
     *
     * @return HTML document as string
     * @throws RestClientException if the request fails
     */
    private fun fetchBulletinHtml(): String {
        return restTemplate.getForObject(baseUrl, String::class.java)
            ?: throw Exception("No HTML content received from $baseUrl")
    }

    /**
     * Parses the PRAZ e-GP bulletin HTML and extracts tender information.
     *
     * Expects an HTML table with rows containing:
     * - Tender ID / Reference Number
     * - Tender Title
     * - Procuring Entity
     * - Category / Sector
     * - Publish Date
     * - Closing Date
     *
     * @param html Raw HTML content
     * @return List of parsed Tender objects
     */
    private fun parseTenders(html: String): List<Tender> {
        val doc = Jsoup.parse(html)
        val tenders = mutableListOf<Tender>()

        // Select all rows from the bulletin table (adjust selector based on actual HTML structure)
        // The e-GP site uses a table with class or ID for the bulletin board
        val rows = doc.select("table tbody tr, table.bulletin-table tr, div.tender-row, tr[data-tender-id]")

        if (rows.isEmpty()) {
            log.warn("No tender rows found in PRAZ e-GP bulletin. HTML structure may have changed.")
            return emptyList()
        }

        for (row in rows) {
            try {
                val tender = parseTenderRow(row.html())
                if (tender != null) {
                    tenders.add(tender)
                }
            } catch (e: Exception) {
                log.debug("Skipped row due to parse error: {}", e.message)
            }
        }

        log.info("Parsed {} tenders from PRAZ e-GP bulletin", tenders.size)
        return tenders
    }

    /**
     * Parses a single HTML row into a Tender object.
     *
     * Expected HTML structure (flexible — adapts to common patterns):
     * <tr>
     *   <td>Tender ID / Reference</td>
     *   <td>Title</td>
     *   <td>Procuring Entity</td>
     *   <td>Category / Sector Code</td>
     *   <td>Publish Date</td>
     *   <td>Closing Date</td>
     *   <td><a href="...">View / Details link</a></td>
     * </tr>
     *
     * @param rowHtml HTML content of a single row
     * @return Parsed Tender, or null if parsing fails
     */
    private fun parseTenderRow(rowHtml: String): Tender? {
        val row = Jsoup.parse(rowHtml).selectFirst("tr")
            ?: Jsoup.parse(rowHtml).selectFirst("div")
            ?: return null

        val cells = row.select("td, div.tender-cell, [data-cell]")
        if (cells.size < 5) {
            return null // Insufficient columns
        }

        try {
            // Extract cell content with fallback to index-based extraction
            val tenderIdOrRef = cells.getOrNull(0)?.text()?.trim() ?: return null
            val title = cells.getOrNull(1)?.text()?.trim() ?: return null
            val issuingAuthority = cells.getOrNull(2)?.text()?.trim() ?: return null
            val categoryCode = cells.getOrNull(3)?.text()?.trim() ?: ""
            val publishDateStr = cells.getOrNull(4)?.text()?.trim() ?: ""
            val deadlineStr = cells.getOrNull(5)?.text()?.trim() ?: ""

            if (title.isBlank() || issuingAuthority.isBlank()) {
                return null
            }

            // Extract source URL from link in the row
            val sourceUrl = row.select("a").firstOrNull()?.attr("href")?.let {
                if (it.startsWith("http")) it else "$baseUrl${it.removePrefix("/")}"
            } ?: "https://egp.praz.org.zw/tenders/${tenderIdOrRef.replace("/", "-")}"

            val publishedAt = parseDateTime(publishDateStr)
            val deadline = parseDateTime(deadlineStr)

            // Map category code to sector (simple mapping)
            val sector = mapCategoryToSector(categoryCode)
            val keywords = extractKeywords(categoryCode, title)

            return Tender(
                title = title,
                description = null,
                sector = sector,
                issuingAuthority = issuingAuthority,
                sourceUrl = sourceUrl,
                sourceName = name,
                publishedAt = publishedAt,
                deadline = deadline,
                externalTenderId = tenderIdOrRef,
                currency = "USD", // Default; can be refined based on additional parsing
                keywords = keywords,
                region = null // Can be inferred from authority later
            )
        } catch (e: Exception) {
            log.debug("Failed to parse tender row: {}", e.message)
            return null
        }
    }

    /**
     * Parses a date string in various formats (ISO 8601, "dd/MM/yyyy HH:mm", etc.).
     *
     * @param dateStr Date string to parse
     * @return Instant, or current time if parsing fails
     */
    private fun parseDateTime(dateStr: String): Instant {
        if (dateStr.isBlank()) {
            return Instant.now()
        }

        return try {
            // Try ISO 8601 first
            Instant.parse(dateStr)
        } catch (e: Exception) {
            try {
                // Try common formats (dd/MM/yyyy HH:mm, yyyy-MM-dd HH:mm, etc.)
                val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                    .withZone(ZoneId.systemDefault())
                LocalDateTime.parse(dateStr, formatter).atZone(ZoneId.systemDefault()).toInstant()
            } catch (e: Exception) {
                Instant.now()
            }
        }
    }

    /**
     * Maps e-GP category code to our Sector enum.
     *
     * e-GP uses codes like GC001, GC002, etc. This function provides a simple mapping.
     * Common codes (from PRAZ docs):
     * - GC001: Professional services
     * - GC002: IT Services
     * - GC006: Computers, Networking
     * - GC008: Construction
     *
     * @param categoryCode e-GP category code (e.g., "GC006")
     * @return Mapped Sector, or OTHER if unknown
     */
    private fun mapCategoryToSector(categoryCode: String): Sector {
        return when {
            categoryCode.contains("GC006", ignoreCase = true) || categoryCode.contains("IT", ignoreCase = true) -> Sector.IT
            categoryCode.contains("GC008", ignoreCase = true) || categoryCode.contains("construction", ignoreCase = true) -> Sector.CONSTRUCTION
            categoryCode.contains("health", ignoreCase = true) -> Sector.HEALTHCARE
            categoryCode.contains("education", ignoreCase = true) -> Sector.EDUCATION
            categoryCode.contains("transport", ignoreCase = true) -> Sector.TRANSPORT
            categoryCode.contains("energy", ignoreCase = true) -> Sector.ENERGY
            categoryCode.contains("agriculture", ignoreCase = true) -> Sector.AGRICULTURE
            else -> Sector.OTHER
        }
    }

    /**
     * Extracts keywords from category code and title for matching.
     *
     * @param categoryCode e-GP category code
     * @param title Tender title
     * @return Set of keywords
     */
    private fun extractKeywords(categoryCode: String, title: String): MutableSet<String> {
        val keywords = mutableSetOf<String>()

        // Add category code
        if (categoryCode.isNotBlank()) {
            keywords.add(categoryCode)
        }

        // Extract meaningful words from title (3+ characters)
        title.split(Regex("\\W+"))
            .filter { it.length >= 3 }
            .map { it.lowercase() }
            .take(10) // Limit to 10 keywords
            .forEach { keywords.add(it) }

        return keywords
    }
}
