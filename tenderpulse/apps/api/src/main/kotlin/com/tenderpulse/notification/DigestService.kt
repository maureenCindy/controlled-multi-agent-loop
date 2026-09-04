package com.tenderpulse.notification

import com.tenderpulse.auth.UnsubscribeService
import com.tenderpulse.domain.DigestQueueEntry
import com.tenderpulse.domain.DigestQueueEntryRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Sends the Free-tier daily digest (TP-013, issue #92): one email per subscriber summarising
 * every [DigestQueueEntry] queued (by [NotificationService], TP-012) since their last digest,
 * rather than one email per match. Mirrors
 * [com.tenderpulse.aggregation.AggregationService]'s scheduled-job structure and
 * disabled-by-default config pattern.
 *
 * [runDigestCycle] is deliberately **not** `@Transactional` as a whole: each subscriber is
 * processed and persisted independently (via [UnsubscribeService.buildUnsubscribeLink]'s and
 * [DigestQueueEntryRepository.saveAll]'s own per-call transactions) so that a failure handling
 * one subscriber can never mark a single shared transaction rollback-only and silently undo
 * another, already-successfully-digested subscriber's [DigestQueueEntry.digestedAt] updates at
 * commit time. That per-subscriber isolation is what the "a send failure for one subscriber must
 * not block digests for other subscribers in the same run" acceptance criterion actually
 * requires.
 */
@Service
class DigestService(
    private val digestQueueEntryRepository: DigestQueueEntryRepository,
    private val unsubscribeService: UnsubscribeService,
    private val digestMailSender: DigestMailSender,
    @Value("\${tenderpulse.digest.scheduled-enabled:false}")
    private val scheduledEnabled: Boolean
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun runDigestCycle(): DigestResult {
        val undigested = digestQueueEntryRepository.findAllByDigestedAtIsNull()
        val bySubscriberId = undigested.groupBy { it.subscriber.id }

        var digested = 0
        var failed = 0

        for (entries in bySubscriberId.values) {
            val subscriber = entries.first().subscriber
            try {
                val unsubscribeLink = unsubscribeService.buildUnsubscribeLink(subscriber)
                val success = digestMailSender.sendDigest(subscriber, entries, unsubscribeLink)
                if (success) {
                    val now = Instant.now()
                    digestQueueEntryRepository.saveAll(entries.map { it.copy(digestedAt = now) })
                    digested++
                } else {
                    failed++
                    log.warn("Digest send failed for subscriber {}; entries left undigested for next run", subscriber.id)
                }
            } catch (e: Exception) {
                failed++
                log.error("Digest cycle failed for subscriber {}: {}", subscriber.id, e.message, e)
            }
        }

        return DigestResult(subscribersDigested = digested, subscribersFailed = failed)
    }

    /**
     * Scheduled digest cycle (disabled by default).
     * Enable with: tenderpulse.digest.scheduled-enabled=true
     * Configure interval with: tenderpulse.digest.scheduled-interval-ms (default: 24 hours)
     */
    @Scheduled(
        initialDelayString = "\${tenderpulse.digest.scheduled-initial-delay-ms:0}",
        fixedDelayString = "\${tenderpulse.digest.scheduled-interval-ms:86400000}"
    )
    fun scheduledDigestCycle() {
        if (!scheduledEnabled) {
            return
        }
        log.info("Running scheduled digest cycle")
        val result = runDigestCycle()
        log.info(
            "Scheduled digest cycle completed: subscribersDigested={}, subscribersFailed={}",
            result.subscribersDigested,
            result.subscribersFailed
        )
    }
}

data class DigestResult(
    val subscribersDigested: Int,
    val subscribersFailed: Int
)
