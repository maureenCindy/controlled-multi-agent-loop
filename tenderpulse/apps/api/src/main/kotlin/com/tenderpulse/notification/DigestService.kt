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
 *
 * ### Delivery semantics: at-least-once, not exactly-once (issue #97)
 *
 * [runDigestCycle]'s per-subscriber loop calls [DigestMailSender.sendDigest] and only
 * *afterwards* calls [DigestQueueEntryRepository.saveAll] to mark that subscriber's entries
 * [DigestQueueEntry.digestedAt]. If the process crashes (or is killed) after `sendDigest()` has
 * already returned `true` — i.e. the email has actually gone out — but before the follow-up
 * `saveAll()` commits, those entries are still `digestedAt = null` in the database. The *next*
 * run of [runDigestCycle] then finds them via
 * [DigestQueueEntryRepository.findAllByDigestedAtIsNull] and resends the same digest to the same
 * subscriber, i.e. this method provides **at-least-once**, not exactly-once, delivery.
 *
 * This is a deliberate, accepted MVP trade-off, not an oversight, and it is intentionally **not**
 * being fixed by reordering send/mark-digested or by introducing an intermediate "sending" state
 * machine (both were explicitly considered and rejected — see issue #97). The failure mode this
 * accepts is narrow and low-severity: in the rare window described above, a subscriber may receive
 * one duplicate digest email. The alternative failure mode this deliberately avoids — marking
 * entries digested *before* confirming the send succeeded — would instead risk **silent
 * non-delivery** (entries marked digested but the subscriber never actually received the email),
 * which is strictly worse for this product: a subscriber occasionally getting one duplicate email
 * is a minor annoyance; a subscriber silently never being told about a matching tender defeats the
 * entire point of the digest. See `tenderpulse/docs/specs/aggregation-policy.md` for the
 * product-level note on this trade-off.
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

    /**
     * Runs one Free-tier digest cycle: groups every undigested [DigestQueueEntry] by subscriber,
     * sends one summary email per subscriber, and marks that subscriber's entries
     * [DigestQueueEntry.digestedAt] once the send succeeds.
     *
     * **Delivery semantics: at-least-once, not exactly-once** (issue #97, class-level KDoc above
     * for the full reasoning). A crash between a successful [DigestMailSender.sendDigest] and the
     * follow-up [DigestQueueEntryRepository.saveAll] leaves that subscriber's entries undigested,
     * so the next call to this method resends the same digest. This is an accepted MVP trade-off,
     * not an oversight — it was chosen deliberately over the alternative failure mode (marking
     * entries digested before confirming the send, risking silent non-delivery), and over adding
     * state-machine complexity, both of which were explicitly considered and rejected.
     */
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
