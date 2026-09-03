package com.tenderpulse.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.tenderpulse.domain.Sector
import com.tenderpulse.domain.WaitlistEntry
import com.tenderpulse.domain.WaitlistEntryRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.UUID

/**
 * Standalone MockMvc tests for [WaitlistController] (TP-020). No Spring context is booted;
 * the repository is a mockk mock and the request body is validated by the default standalone
 * validator (registered automatically by [MockMvcBuilders.standaloneSetup]).
 */
class WaitlistControllerTest {

    private val waitlistRepository = mockk<WaitlistEntryRepository>()
    private val objectMapper: ObjectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        val controller = WaitlistController(waitlistRepository)
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setMessageConverters(MappingJackson2HttpMessageConverter(objectMapper))
            .build()
    }

    private fun waitlistJson(
        email: String? = "biz@example.co.zw",
        sectors: Set<Sector> = setOf(Sector.CONSTRUCTION),
        province: String? = "Harare",
        company: String? = "Acme Builders"
    ): String {
        val body = LinkedHashMap<String, Any?>()
        body["email"] = email
        body["sectors"] = sectors
        body["province"] = province
        body["company"] = company
        return objectMapper.writeValueAsString(body)
    }

    // ---- test case 1: valid signup ----

    @Test
    fun `valid signup returns 201 and stores the entry`() {
        every { waitlistRepository.findByEmail("biz@example.co.zw") } returns null
        val saved = slot<WaitlistEntry>()
        every { waitlistRepository.save(capture(saved)) } answers { saved.captured }

        mockMvc.perform(
            post("/api/v1/waitlist")
                .contentType(MediaType.APPLICATION_JSON)
                .content(waitlistJson())
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").value(saved.captured.id.toString()))
            .andExpect(jsonPath("$.email").value("biz@example.co.zw"))
            .andExpect(jsonPath("$.sectors[0]").value("CONSTRUCTION"))
            .andExpect(jsonPath("$.province").value("Harare"))
            .andExpect(jsonPath("$.company").value("Acme Builders"))

        assertEquals("biz@example.co.zw", saved.captured.email)
        assertEquals(setOf(Sector.CONSTRUCTION), saved.captured.sectors)
        assertEquals("Harare", saved.captured.province)
        assertEquals("Acme Builders", saved.captured.company)
        verify(exactly = 1) { waitlistRepository.save(any()) }
    }

    @Test
    fun `valid signup without optional company is accepted`() {
        every { waitlistRepository.findByEmail("noco@example.co.zw") } returns null
        val saved = slot<WaitlistEntry>()
        every { waitlistRepository.save(capture(saved)) } answers { saved.captured }

        mockMvc.perform(
            post("/api/v1/waitlist")
                .contentType(MediaType.APPLICATION_JSON)
                .content(waitlistJson(email = "noco@example.co.zw", company = null))
        ).andExpect(status().isCreated)

        assertEquals(null, saved.captured.company)
    }

    // ---- test case 2: invalid email ----

    @Test
    fun `invalid email format returns 400`() {
        mockMvc.perform(
            post("/api/v1/waitlist")
                .contentType(MediaType.APPLICATION_JSON)
                .content(waitlistJson(email = "not-an-email"))
        ).andExpect(status().isBadRequest)

        verify(exactly = 0) { waitlistRepository.save(any()) }
    }

    @Test
    fun `blank email returns 400`() {
        mockMvc.perform(
            post("/api/v1/waitlist")
                .contentType(MediaType.APPLICATION_JSON)
                .content(waitlistJson(email = ""))
        ).andExpect(status().isBadRequest)

        verify(exactly = 0) { waitlistRepository.save(any()) }
    }

    @Test
    fun `missing email returns 400`() {
        mockMvc.perform(
            post("/api/v1/waitlist")
                .contentType(MediaType.APPLICATION_JSON)
                .content(waitlistJson(email = null))
        ).andExpect(status().isBadRequest)

        verify(exactly = 0) { waitlistRepository.save(any()) }
    }

    // ---- test case 3: duplicate email is idempotent ----

    @Test
    fun `duplicate email updates the existing row and returns 200 with a single logical record`() {
        val existingId = UUID.randomUUID()
        val createdAt = Instant.now().minusSeconds(3600)
        val existing = WaitlistEntry(
            id = existingId,
            email = "biz@example.co.zw",
            sectors = mutableSetOf(Sector.IT),
            province = "Bulawayo",
            company = null,
            createdAt = createdAt,
            updatedAt = createdAt
        )
        every { waitlistRepository.findByEmail("biz@example.co.zw") } returns existing
        val saved = slot<WaitlistEntry>()
        every { waitlistRepository.save(capture(saved)) } answers { saved.captured }

        mockMvc.perform(
            post("/api/v1/waitlist")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    waitlistJson(
                        sectors = setOf(Sector.CONSTRUCTION),
                        province = "Harare",
                        company = "Acme Builders"
                    )
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(existingId.toString()))
            .andExpect(jsonPath("$.sectors[0]").value("CONSTRUCTION"))
            .andExpect(jsonPath("$.province").value("Harare"))
            .andExpect(jsonPath("$.company").value("Acme Builders"))

        // Same id preserved -> single logical record, updated in place rather than duplicated.
        assertEquals(existingId, saved.captured.id)
        assertEquals(setOf(Sector.CONSTRUCTION), saved.captured.sectors)
        assertEquals("Harare", saved.captured.province)
        assertEquals("Acme Builders", saved.captured.company)
        verify(exactly = 1) { waitlistRepository.save(any()) }
    }

    @Test
    fun `duplicate email submission does not throw and completes successfully`() {
        val existing = WaitlistEntry(email = "biz@example.co.zw")
        every { waitlistRepository.findByEmail("biz@example.co.zw") } returns existing
        every { waitlistRepository.save(any()) } answers { firstArg() }

        mockMvc.perform(
            post("/api/v1/waitlist")
                .contentType(MediaType.APPLICATION_JSON)
                .content(waitlistJson())
        ).andExpect(status().is2xxSuccessful)
    }
}
