package com.tenderpulse.web

import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * TP-030: verifies the static waitlist landing page is served by Spring Boot's default
 * static-resource handling at "/", with the expected copy and form markup present so the
 * form is wired to POST /api/v1/waitlist (the pre-existing TP-020 endpoint).
 */
@SpringBootTest
@AutoConfigureMockMvc
class WaitlistLandingPageTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `GET root is reachable and serves the landing page`() {
        // Spring Boot's WelcomePageHandlerMapping forwards "/" to index.html; MockMvc's mock
        // dispatcher reports this forward as an empty body even though a real server returns
        // the full page (verified manually — see PR evidence), so status is asserted here and
        // markup content is asserted against the same resource served directly below.
        mockMvc.perform(get("/")).andExpect(status().isOk)
    }

    @Test
    fun `GET index html contains the expected waitlist landing page markup`() {
        mockMvc.perform(get("/index.html"))
            .andExpect(status().isOk)
            // Zimbabwe explicit in copy
            .andExpect(content().string(containsString("Zimbabwe")))
            // form fields present
            .andExpect(content().string(containsString("id=\"email\"")))
            .andExpect(content().string(containsString("id=\"sector\"")))
            .andExpect(content().string(containsString("id=\"province\"")))
            // privacy/consent line
            .andExpect(content().string(containsString("only use your email")))
            // link to X for build updates
            .andExpect(content().string(containsString("x.com/tenderpulse_zw")))
            // TP-041: link to the short privacy note
            .andExpect(content().string(containsString("/privacy.html")))
    }

    @Test
    fun `GET waitlist assets are served`() {
        mockMvc.perform(get("/css/styles.css")).andExpect(status().isOk)
        mockMvc.perform(get("/js/waitlist.js"))
            .andExpect(status().isOk)
            // the client-side script posts to the existing TP-020 waitlist API
            .andExpect(content().string(containsString("/api/v1/waitlist")))
    }

    @Test
    fun `GET privacy html contains the MVP-stage disclaimer and no third-party sharing statement`() {
        mockMvc.perform(get("/privacy.html"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("not vetted legal advice")))
            .andExpect(content().string(containsString("don't sell or share")))
    }
}
