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
 * TP-041/TP-037: the standalone privacy note is still served as a static resource even though
 * the old waitlist landing page that used to link to it (TP-030) was retired in TP-037 (#38) —
 * it's a real, still-useful page, just no longer linked from anywhere in this backend. See
 * PR for TP-037 for the retirement rationale.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PrivacyPageTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `GET privacy html contains the MVP-stage disclaimer and no third-party sharing statement`() {
        mockMvc.perform(get("/privacy.html"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("not vetted legal advice")))
            .andExpect(content().string(containsString("don't sell or share")))
    }
}
