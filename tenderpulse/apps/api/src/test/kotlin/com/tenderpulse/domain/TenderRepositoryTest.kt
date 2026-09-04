package com.tenderpulse.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import java.time.Duration
import java.time.Instant

/**
 * TP-056 (issue #56): repository-level proof (against a real H2 JPA context, not a mock) that
 * [TenderRepository.findByDeadlineBetween] actually enforces the reminder-window boundary
 * [com.tenderpulse.notification.ReminderService] relies on — a mocked-repository unit test
 * (see ReminderServiceTest) can't exercise the real `BETWEEN` query Spring Data derives from the
 * method name, and per this project's Verification Standards a boundary claim like "the query
 * excludes a tender whose deadline already passed" must be reproduced empirically, not assumed
 * correct because the method name reads right.
 */
@DataJpaTest
class TenderRepositoryTest {

    @Autowired
    private lateinit var entityManager: TestEntityManager

    @Autowired
    private lateinit var tenderRepository: TenderRepository

    private fun tender(title: String, deadline: Instant?) = Tender(
        title = title,
        issuingAuthority = "Ministry of Finance",
        sourceUrl = "https://egp.praz.org.zw/tender/$title",
        sourceName = "praz-egp",
        deadline = deadline
    )

    @Test
    fun `a tender with deadline within the window is returned`() {
        val now = Instant.now()
        val inWindow = tenderRepository.save(tender("in-window", now.plus(Duration.ofDays(2))))
        entityManager.flush()
        entityManager.clear()

        val results = tenderRepository.findByDeadlineBetween(now, now.plus(Duration.ofDays(3)))

        assertTrue(results.any { it.id == inWindow.id })
    }

    @Test
    fun `a tender with deadline outside the window is excluded`() {
        val now = Instant.now()
        val outsideWindow = tenderRepository.save(tender("outside-window", now.plus(Duration.ofDays(10))))
        entityManager.flush()
        entityManager.clear()

        val results = tenderRepository.findByDeadlineBetween(now, now.plus(Duration.ofDays(3)))

        assertFalse(results.any { it.id == outsideWindow.id })
    }

    @Test
    fun `a tender with a deadline already in the past is excluded`() {
        val now = Instant.now()
        val alreadyPassed = tenderRepository.save(tender("already-passed", now.minus(Duration.ofDays(1))))
        entityManager.flush()
        entityManager.clear()

        val results = tenderRepository.findByDeadlineBetween(now, now.plus(Duration.ofDays(3)))

        assertFalse(results.any { it.id == alreadyPassed.id })
    }

    @Test
    fun `a tender with no deadline at all is excluded`() {
        val now = Instant.now()
        val noDeadline = tenderRepository.save(tender("no-deadline", null))
        entityManager.flush()
        entityManager.clear()

        val results = tenderRepository.findByDeadlineBetween(now, now.plus(Duration.ofDays(3)))

        assertFalse(results.any { it.id == noDeadline.id })
        assertEquals(0, results.size)
    }
}
