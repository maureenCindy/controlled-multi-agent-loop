package com.tenderpulse.api

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Full-context tests for TP-034's CORS wiring ([com.tenderpulse.auth.WebsiteCorsConfig]): the
 * marketing site's configured origin (`http://localhost:4321` — see
 * `src/test/resources/application.yml`) must be allowed to call the two public signup
 * endpoints, an arbitrary other origin must not, and the CORS configuration must not leak onto
 * routes it wasn't meant for (the authenticated profile endpoints, or GET /api/v1/tenders).
 *
 * [JavaMailSender] is mocked purely to keep the Spring context boot fast/side-effect-free, same
 * as [AuthIntegrationTest] — it is not exercised by any of these requests.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebsiteCorsIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var javaMailSender: JavaMailSender

    private val allowedOrigin = "http://localhost:4321"
    private val otherOrigin = "http://evil.example"

    @Test
    fun `preflight from the configured site origin is allowed for the Free signup endpoint`() {
        mockMvc.perform(
            options("/api/v1/subscribers")
                .header(HttpHeaders.ORIGIN, allowedOrigin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
        )
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, allowedOrigin))
    }

    @Test
    fun `preflight from the configured site origin is allowed for the Pro signup endpoint`() {
        mockMvc.perform(
            options("/api/v1/subscribers/pro")
                .header(HttpHeaders.ORIGIN, allowedOrigin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
        )
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, allowedOrigin))
    }

    @Test
    fun `an actual POST from the configured site origin carries the CORS allow header`() {
        mockMvc.perform(
            post("/api/v1/subscribers")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ORIGIN, allowedOrigin)
                .content("""{"email":"cors-allowed@example.com"}""")
        )
            .andExpect(status().isCreated)
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, allowedOrigin))
    }

    @Test
    fun `preflight from an arbitrary other origin is rejected`() {
        mockMvc.perform(
            options("/api/v1/subscribers")
                .header(HttpHeaders.ORIGIN, otherOrigin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
        )
            .andExpect(status().isForbidden)
    }

    @Test
    fun `an actual POST from an arbitrary other origin is rejected`() {
        mockMvc.perform(
            post("/api/v1/subscribers")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ORIGIN, otherOrigin)
                .content("""{"email":"cors-blocked@example.com"}""")
        )
            .andExpect(status().isForbidden)
    }

    @Test
    fun `CORS is not configured for routes outside the two signup endpoints`() {
        mockMvc.perform(
            options("/api/v1/tenders")
                .header(HttpHeaders.ORIGIN, allowedOrigin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
        )
            .andExpect(status().isForbidden)
    }

    @Test
    fun `a plain same-origin request with no Origin header is unaffected by CORS`() {
        mockMvc.perform(get("/api/v1/tenders"))
            .andExpect(status().isOk)
    }
}
