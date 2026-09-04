package com.tenderpulse.api

import com.tenderpulse.auth.UnsubscribeService
import com.tenderpulse.domain.InterestProfile
import com.tenderpulse.domain.InterestProfileRepository
import com.tenderpulse.domain.Sector
import com.tenderpulse.domain.Subscriber
import com.tenderpulse.domain.SubscriberRepository
import com.tenderpulse.domain.SubscriptionTier
import com.tenderpulse.domain.Tender
import com.tenderpulse.domain.TenderRepository
import com.tenderpulse.notification.NotificationService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Full-context tests for TP-057 (issue #57): unsubscribe / email preference management.
 *
 * Covers the issue's four test cases end to end against the real `GET /api/v1/unsubscribe` route
 * and [com.tenderpulse.auth.SecurityConfig] filter chain (unlike
 * [com.tenderpulse.auth.UnsubscribeServiceTest], which mocks the repositories).
 * [JavaMailSender] is mocked (no live network), same principle as [AuthIntegrationTest].
 */
@SpringBootTest
@AutoConfigureMockMvc
class UnsubscribeIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var subscriberRepository: SubscriberRepository

    @Autowired
    private lateinit var profileRepository: InterestProfileRepository

    @Autowired
    private lateinit var tenderRepository: TenderRepository

    @Autowired
    private lateinit var unsubscribeService: UnsubscribeService

    @Autowired
    private lateinit var notificationService: NotificationService

    @MockitoBean
    private lateinit var javaMailSender: JavaMailSender

    private fun createSubscriber(email: String, tier: SubscriptionTier = SubscriptionTier.PAID): Subscriber =
        subscriberRepository.save(Subscriber(email = email, tier = tier))

    // ---- Test case 1: click unsubscribe link from a real email -> opted out, no login required ----

    @Test
    fun `clicking a valid unsubscribe link opts the subscriber out without any authentication`() {
        val subscriber = createSubscriber("click-unsubscribe@example.com")
        val link = unsubscribeService.buildUnsubscribeLink(subscriber)
        val rawToken = Regex("token=(\\S+)").find(link)!!.groupValues[1]

        // No Authorization header at all.
        mockMvc.perform(get("/api/v1/unsubscribe").param("token", rawToken))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.message").exists())

        val reloaded = subscriberRepository.findById(subscriber.id).orElseThrow()
        assertTrue(reloaded.emailOptOut)
    }

    // ---- Test case 2: opted-out subscriber matches a new tender -> excluded, no email sent ----

    @Test
    fun `an opted-out subscriber is excluded from the notification cycle for a newly matched tender`() {
        val subscriber = createSubscriber("opted-out-no-match@example.com")
        val link = unsubscribeService.buildUnsubscribeLink(subscriber)
        val rawToken = Regex("token=(\\S+)").find(link)!!.groupValues[1]
        mockMvc.perform(get("/api/v1/unsubscribe").param("token", rawToken)).andExpect(status().isOk)

        profileRepository.save(
            InterestProfile(subscriber = subscriber, sectors = mutableSetOf(Sector.IT), active = true)
        )
        val tender = tenderRepository.save(
            Tender(
                title = "IT infrastructure upgrade",
                issuingAuthority = "Ministry of ICT",
                sourceUrl = "https://egp.praz.org.zw/tenders/2026/TR-unsub-1",
                sourceName = "egp.praz.org.zw",
                sector = Sector.IT
            )
        )

        val sent = notificationService.notifyMatchingSubscribers(tender)

        assertEquals(0, sent)
        verify(javaMailSender, never()).send(org.mockito.ArgumentMatchers.any(org.springframework.mail.SimpleMailMessage::class.java))
    }

    @Test
    fun `a still-subscribed profile for the same sector keeps matching after another subscriber opts out`() {
        val optedOut = createSubscriber("opts-out-2@example.com")
        val stillIn = createSubscriber("stays-in-2@example.com")
        val link = unsubscribeService.buildUnsubscribeLink(optedOut)
        val rawToken = Regex("token=(\\S+)").find(link)!!.groupValues[1]
        mockMvc.perform(get("/api/v1/unsubscribe").param("token", rawToken)).andExpect(status().isOk)

        profileRepository.save(InterestProfile(subscriber = optedOut, sectors = mutableSetOf(Sector.HEALTHCARE), active = true))
        profileRepository.save(InterestProfile(subscriber = stillIn, sectors = mutableSetOf(Sector.HEALTHCARE), active = true))
        val tender = tenderRepository.save(
            Tender(
                title = "Hospital equipment supply",
                issuingAuthority = "Ministry of Health",
                sourceUrl = "https://egp.praz.org.zw/tenders/2026/TR-unsub-2",
                sourceName = "egp.praz.org.zw",
                sector = Sector.HEALTHCARE
            )
        )

        val sent = notificationService.notifyMatchingSubscribers(tender)

        assertEquals(1, sent)
    }

    // ---- Test case 3: reuse an already-used unsubscribe link -> no error, idempotent ----

    @Test
    fun `reusing an already-used unsubscribe link is idempotent and returns 200 both times`() {
        val subscriber = createSubscriber("reuse-link@example.com")
        val link = unsubscribeService.buildUnsubscribeLink(subscriber)
        val rawToken = Regex("token=(\\S+)").find(link)!!.groupValues[1]

        mockMvc.perform(get("/api/v1/unsubscribe").param("token", rawToken)).andExpect(status().isOk)
        mockMvc.perform(get("/api/v1/unsubscribe").param("token", rawToken)).andExpect(status().isOk)

        val reloaded = subscriberRepository.findById(subscriber.id).orElseThrow()
        assertTrue(reloaded.emailOptOut)
    }

    // ---- Test case 4: tampered/invalid unsubscribe token -> rejected, no state change ----

    @Test
    fun `a tampered or invalid unsubscribe token is rejected and changes no subscriber state`() {
        val subscriber = createSubscriber("tampered-token@example.com")

        mockMvc.perform(get("/api/v1/unsubscribe").param("token", "this-was-never-issued"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid_token"))

        val reloaded = subscriberRepository.findById(subscriber.id).orElseThrow()
        assertFalse(reloaded.emailOptOut)
    }

    @Test
    fun `GET unsubscribe remains open and unauthenticated even with no Authorization header`() {
        val subscriber = createSubscriber("no-auth-header@example.com")
        val link = unsubscribeService.buildUnsubscribeLink(subscriber)
        val rawToken = Regex("token=(\\S+)").find(link)!!.groupValues[1]

        mockMvc.perform(get("/api/v1/unsubscribe").param("token", rawToken))
            .andExpect(status().isOk)
    }
}
