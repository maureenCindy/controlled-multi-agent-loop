package com.tenderpulse.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.tenderpulse.domain.InterestProfile
import com.tenderpulse.domain.InterestProfileRepository
import com.tenderpulse.domain.Sector
import com.tenderpulse.domain.Subscriber
import com.tenderpulse.domain.SubscriberRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.math.BigDecimal
import java.util.Optional
import java.util.UUID

/**
 * Standalone MockMvc tests for [SubscriberController]'s interest-profile endpoints (TP-010).
 * No Spring context is booted; repositories are mockk mocks and the request body is validated
 * by the default standalone validator (registered automatically by
 * [MockMvcBuilders.standaloneSetup]), which exercises the same `@Valid` / `@ResponseStatus`
 * wiring the real application uses.
 */
class SubscriberControllerTest {

    private val subscriberRepository = mockk<SubscriberRepository>()
    private val profileRepository = mockk<InterestProfileRepository>()
    private val objectMapper: ObjectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
    private lateinit var mockMvc: MockMvc

    private val subscriberId: UUID = UUID.randomUUID()
    private val subscriber = Subscriber(id = subscriberId, email = "sub@example.com")

    @BeforeEach
    fun setUp() {
        val controller = SubscriberController(subscriberRepository, profileRepository)
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setMessageConverters(MappingJackson2HttpMessageConverter(objectMapper))
            .build()
    }

    private fun profilesUrl(id: UUID = subscriberId) = "/api/v1/subscribers/$id/profiles"

    private fun profileJson(
        sectors: Set<Sector> = setOf(Sector.IT),
        valueMin: BigDecimal? = null,
        valueMax: BigDecimal? = null,
        region: String? = null,
        active: Boolean? = null
    ): String {
        val body = LinkedHashMap<String, Any?>()
        body["sectors"] = sectors
        body["valueMin"] = valueMin
        body["valueMax"] = valueMax
        body["region"] = region
        if (active != null) body["active"] = active
        return objectMapper.writeValueAsString(body)
    }

    // ---- create ----

    @Test
    fun `create profile returns 201 and persists what was requested`() {
        every { subscriberRepository.findById(subscriberId) } returns Optional.of(subscriber)
        val saved = slot<InterestProfile>()
        every { profileRepository.save(capture(saved)) } answers { saved.captured }

        mockMvc.perform(
            post(profilesUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    profileJson(
                        sectors = setOf(Sector.IT),
                        valueMin = BigDecimal("100000"),
                        valueMax = BigDecimal("500000"),
                        region = "Harare"
                    )
                )
        ).andExpect(status().isCreated)

        assertEquals(setOf(Sector.IT), saved.captured.sectors)
        assertEquals(BigDecimal("100000"), saved.captured.valueMin)
        assertEquals(BigDecimal("500000"), saved.captured.valueMax)
        assertEquals("Harare", saved.captured.region)
        assertEquals(subscriberId, saved.captured.subscriber.id)
        assertTrue(saved.captured.active)
    }

    @Test
    fun `create with valueMin greater than valueMax returns 400`() {
        every { subscriberRepository.findById(subscriberId) } returns Optional.of(subscriber)

        mockMvc.perform(
            post(profilesUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .content(profileJson(valueMin = BigDecimal("500000"), valueMax = BigDecimal("100000")))
        ).andExpect(status().isBadRequest)

        verify(exactly = 0) { profileRepository.save(any()) }
    }

    @Test
    fun `create with equal valueMin and valueMax is accepted`() {
        every { subscriberRepository.findById(subscriberId) } returns Optional.of(subscriber)
        val saved = slot<InterestProfile>()
        every { profileRepository.save(capture(saved)) } answers { saved.captured }

        mockMvc.perform(
            post(profilesUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .content(profileJson(valueMin = BigDecimal("250000"), valueMax = BigDecimal("250000")))
        ).andExpect(status().isCreated)

        assertEquals(BigDecimal("250000"), saved.captured.valueMin)
        assertEquals(BigDecimal("250000"), saved.captured.valueMax)
    }

    @Test
    fun `create with only valueMin set is accepted`() {
        every { subscriberRepository.findById(subscriberId) } returns Optional.of(subscriber)
        val saved = slot<InterestProfile>()
        every { profileRepository.save(capture(saved)) } answers { saved.captured }

        mockMvc.perform(
            post(profilesUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .content(profileJson(valueMin = BigDecimal("250000"), valueMax = null))
        ).andExpect(status().isCreated)

        assertEquals(BigDecimal("250000"), saved.captured.valueMin)
        assertEquals(null, saved.captured.valueMax)
    }

    @Test
    fun `create for unknown subscriber returns 404`() {
        every { subscriberRepository.findById(subscriberId) } returns Optional.empty()

        mockMvc.perform(
            post(profilesUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .content(profileJson())
        ).andExpect(status().isNotFound)

        verify(exactly = 0) { profileRepository.save(any()) }
    }

    // ---- list ----

    @Test
    fun `list returns all profiles for the subscriber including an inactive one`() {
        every { subscriberRepository.findById(subscriberId) } returns Optional.of(subscriber)
        val activeProfile = InterestProfile(subscriber = subscriber, active = true)
        val inactiveProfile = InterestProfile(subscriber = subscriber, active = false)
        every { profileRepository.findBySubscriberId(subscriberId) } returns listOf(activeProfile, inactiveProfile)

        mockMvc.perform(get(profilesUrl()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].active").value(true))
            .andExpect(jsonPath("$[1].active").value(false))
    }

    @Test
    fun `list for unknown subscriber returns 404`() {
        every { subscriberRepository.findById(subscriberId) } returns Optional.empty()

        mockMvc.perform(get(profilesUrl())).andExpect(status().isNotFound)
    }

    // ---- update ----

    @Test
    fun `update mutates the intended fields and persists`() {
        val profileId = UUID.randomUUID()
        val existing = InterestProfile(
            id = profileId,
            subscriber = subscriber,
            sectors = mutableSetOf(Sector.IT),
            region = "Harare"
        )
        every { subscriberRepository.findById(subscriberId) } returns Optional.of(subscriber)
        every { profileRepository.findById(profileId) } returns Optional.of(existing)
        val saved = slot<InterestProfile>()
        every { profileRepository.save(capture(saved)) } answers { saved.captured }

        mockMvc.perform(
            put("${profilesUrl()}/$profileId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(profileJson(sectors = setOf(Sector.HEALTHCARE), region = "Bulawayo"))
        ).andExpect(status().isOk)

        assertEquals(profileId, saved.captured.id)
        assertEquals(setOf(Sector.HEALTHCARE), saved.captured.sectors)
        assertEquals("Bulawayo", saved.captured.region)
    }

    @Test
    fun `update can deactivate a profile`() {
        val profileId = UUID.randomUUID()
        val existing = InterestProfile(id = profileId, subscriber = subscriber, active = true)
        every { subscriberRepository.findById(subscriberId) } returns Optional.of(subscriber)
        every { profileRepository.findById(profileId) } returns Optional.of(existing)
        val saved = slot<InterestProfile>()
        every { profileRepository.save(capture(saved)) } answers { saved.captured }

        mockMvc.perform(
            put("${profilesUrl()}/$profileId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(profileJson(active = false))
        ).andExpect(status().isOk)

        assertFalse(saved.captured.active)
    }

    @Test
    fun `update with valueMin greater than valueMax returns 400`() {
        mockMvc.perform(
            put("${profilesUrl()}/${UUID.randomUUID()}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(profileJson(valueMin = BigDecimal("500000"), valueMax = BigDecimal("100000")))
        ).andExpect(status().isBadRequest)

        verify(exactly = 0) { profileRepository.save(any()) }
    }

    @Test
    fun `update for unknown subscriber returns 404`() {
        every { subscriberRepository.findById(subscriberId) } returns Optional.empty()

        mockMvc.perform(
            put("${profilesUrl()}/${UUID.randomUUID()}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(profileJson())
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `update for unknown profile returns 404`() {
        val profileId = UUID.randomUUID()
        every { subscriberRepository.findById(subscriberId) } returns Optional.of(subscriber)
        every { profileRepository.findById(profileId) } returns Optional.empty()

        mockMvc.perform(
            put("${profilesUrl()}/$profileId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(profileJson())
        ).andExpect(status().isNotFound)

        verify(exactly = 0) { profileRepository.save(any()) }
    }

    @Test
    fun `update for a profile belonging to a different subscriber returns 404`() {
        val otherSubscriber = Subscriber(id = UUID.randomUUID(), email = "other@example.com")
        val profileId = UUID.randomUUID()
        val existing = InterestProfile(id = profileId, subscriber = otherSubscriber)
        every { subscriberRepository.findById(subscriberId) } returns Optional.of(subscriber)
        every { profileRepository.findById(profileId) } returns Optional.of(existing)

        mockMvc.perform(
            put("${profilesUrl()}/$profileId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(profileJson())
        ).andExpect(status().isNotFound)

        verify(exactly = 0) { profileRepository.save(any()) }
    }
}
