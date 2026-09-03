package com.tenderpulse.api

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * TP-037 (#38): Waitlist was retired entirely. Boots the real Spring context (not a standalone
 * controller) so this proves `POST /api/v1/waitlist` has no handler at all, rather than just
 * that [WaitlistController] was deleted.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WaitlistRetirementTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `POST api v1 waitlist no longer exists`() {
        mockMvc.perform(
            post("/api/v1/waitlist")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"biz@example.co.zw"}""")
        ).andExpect(status().isNotFound)
    }
}
