package com.tenderpulse.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.tenderpulse.domain.NotFoundException
import com.tenderpulse.domain.Sector
import com.tenderpulse.domain.Tender
import com.tenderpulse.tender.TenderService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.math.BigDecimal
import java.util.UUID

/**
 * Standalone MockMvc tests for [TenderController] (TP-052). No Spring context is booted;
 * [TenderService] is a mockk mock, so these tests exercise only the controller's own
 * responsibilities — request binding, delegation to the service, HTTP status mapping, and
 * entity -> DTO mapping — not the filtering/sorting logic itself (that now lives in
 * [TenderService] and is covered separately). Same pattern as
 * [com.tenderpulse.api.SubscriberControllerTest] (TP-037).
 */
class TenderControllerTest {

    private val tenderService = mockk<TenderService>()
    private val objectMapper: ObjectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        val controller = TenderController(tenderService)
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setMessageConverters(MappingJackson2HttpMessageConverter(objectMapper))
            .build()
    }

    private fun tender(
        id: UUID = UUID.randomUUID(),
        title: String = "Road Maintenance",
        sector: Sector = Sector.CONSTRUCTION
    ) = Tender(
        id = id,
        title = title,
        sector = sector,
        issuingAuthority = "Ministry of Transport",
        sourceUrl = "https://egp.praz.org.zw/tenders/$id",
        sourceName = "PRAZ",
        valueMin = BigDecimal("100000"),
        valueMax = BigDecimal("500000"),
        region = "Harare"
    )

    // ---- list ----

    @Test
    fun `list returns a DTO for every tender, not the raw entity`() {
        val t1 = tender(title = "Road Maintenance")
        val t2 = tender(title = "School IT Upgrade", sector = Sector.IT)
        every { tenderService.list(null, 0, 20) } returns listOf(t1, t2)

        mockMvc.perform(get("/api/v1/tenders"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].id").value(t1.id.toString()))
            .andExpect(jsonPath("$[0].title").value("Road Maintenance"))
            .andExpect(jsonPath("$[0].sector").value("CONSTRUCTION"))
            .andExpect(jsonPath("$[0].issuingAuthority").value("Ministry of Transport"))
            .andExpect(jsonPath("$[0].sourceUrl").value(t1.sourceUrl))
            .andExpect(jsonPath("$[1].title").value("School IT Upgrade"))
    }

    @Test
    fun `list passes the sector query param through to the service unmodified`() {
        every { tenderService.list(Sector.IT, 0, 20) } returns emptyList()

        mockMvc.perform(get("/api/v1/tenders").param("sector", "IT"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(0))

        verify(exactly = 1) { tenderService.list(Sector.IT, 0, 20) }
    }

    @Test
    fun `list passes page and size query params through to the service`() {
        every { tenderService.list(null, 2, 5) } returns emptyList()

        mockMvc.perform(get("/api/v1/tenders").param("page", "2").param("size", "5"))
            .andExpect(status().isOk)

        verify(exactly = 1) { tenderService.list(null, 2, 5) }
    }

    // ---- get ----

    @Test
    fun `get returns a DTO with the matching id`() {
        val t = tender()
        every { tenderService.get(t.id) } returns t

        mockMvc.perform(get("/api/v1/tenders/${t.id}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(t.id.toString()))
            .andExpect(jsonPath("$.title").value(t.title))
            .andExpect(jsonPath("$.region").value("Harare"))
    }

    @Test
    fun `get for an unknown id returns 404`() {
        val id = UUID.randomUUID()
        every { tenderService.get(id) } throws NotFoundException("Tender $id")

        mockMvc.perform(get("/api/v1/tenders/$id"))
            .andExpect(status().isNotFound)
    }
}
