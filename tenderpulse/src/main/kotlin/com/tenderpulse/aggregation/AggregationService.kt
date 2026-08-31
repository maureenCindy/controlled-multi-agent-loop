package com.tenderpulse.aggregation

import com.tenderpulse.domain.Tender
import com.tenderpulse.domain.TenderRepository
import com.tenderpulse.matching.MatchingService
import com.tenderpulse.notification.NotificationService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Continuously (or on schedule) pulls notices from registered sources,
 * normalises & stores them, then triggers matching + notifications.
 */
@Service
class AggregationService(
    private val sources: List<TenderSource>,
    private val tenderRepository: TenderRepository,
    private val matchingService: MatchingService,
    private val notificationService: NotificationService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun runAggregationCycle(): AggregationResult {
        var fetched = 0
        var stored = 0
        var notified = 0

        for (source in sources) {
            try {
                val notices = source.fetchNewNotices()
                fetched += notices.size
                for (notice in notices) {
                    val existing = tenderRepository.findBySourceUrl(notice.sourceUrl)
                    if (existing != null) continue

                    val saved = tenderRepository.save(notice)
                    stored++
                    notified += notificationService.notifyMatchingSubscribers(saved)
                }
            } catch (e: Exception) {
                log.error("Source {} failed: {}", source.name, e.message, e)
            }
        }

        return AggregationResult(fetched = fetched, stored = stored, notificationsSent = notified)
    }
}

data class AggregationResult(
    val fetched: Int,
    val stored: Int,
    val notificationsSent: Int
)
