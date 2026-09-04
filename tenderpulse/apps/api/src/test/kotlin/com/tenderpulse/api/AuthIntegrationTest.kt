package com.tenderpulse.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.tenderpulse.auth.BearerTokenService
import com.tenderpulse.domain.InterestProfileRepository
import com.tenderpulse.domain.Subscriber
import com.tenderpulse.domain.SubscriberRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.net.URI

/**
 * Full-context tests for TP-038's security wiring: boots the real [com.tenderpulse.auth.SecurityConfig]
 * filter chain and [com.tenderpulse.auth.SubscriberOwnershipInterceptor], unlike
 * [AuthControllerTest] (standalone MockMvc, controller only) and
 * [com.tenderpulse.api.SubscriberControllerTest] (also standalone — security isn't in play
 * there at all). This is the only place that actually proves the two are wired together
 * correctly against the real `/api/v1/subscribers/{id}/profiles...` routes.
 *
 * [JavaMailSender] is mocked (no live network — same principle as the PRAZ adapter's fixture
 * tests) so `POST /api/v1/auth/magic-link` can run against the real [MagicLinkMailSender] bean
 * without attempting to reach an SMTP server.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var subscriberRepository: SubscriberRepository

    @Autowired
    private lateinit var profileRepository: InterestProfileRepository

    @Autowired
    private lateinit var bearerTokenService: BearerTokenService

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockitoBean
    private lateinit var javaMailSender: JavaMailSender

    private fun createSubscriber(email: String): Subscriber = subscriberRepository.save(Subscriber(email = email))

    @Test
    fun `unauthenticated request to a profile endpoint is rejected 401`() {
        val subscriber = createSubscriber("int-401@example.com")

        mockMvc.perform(get("/api/v1/subscribers/${subscriber.id}/profiles"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `authenticated request for a different subscriber's profile is rejected 403`() {
        val owner = createSubscriber("owner@example.com")
        val intruder = createSubscriber("intruder@example.com")
        val intruderToken = bearerTokenService.issue(intruder.id)

        mockMvc.perform(
            get("/api/v1/subscribers/${owner.id}/profiles")
                .header("Authorization", "Bearer $intruderToken")
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `authenticated request for one's own subscriber succeeds`() {
        val subscriber = createSubscriber("self@example.com")
        val token = bearerTokenService.issue(subscriber.id)

        mockMvc.perform(
            get("/api/v1/subscribers/${subscriber.id}/profiles")
                .header("Authorization", "Bearer $token")
        )
            .andExpect(status().isOk)
            .andExpect(content().json("[]"))
    }

    @Test
    fun `POST subscribers signup remains open and unauthenticated`() {
        mockMvc.perform(
            post("/api/v1/subscribers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"new-signup@example.com"}""")
        ).andExpect(status().isCreated)
    }

    // TP-042 regression (post-#63-rebase check): /api/v1/subscribers/pro must NOT be swept up by
    // the /api/v1/subscribers/{id}/profiles/** security matcher -- it's the Pro-tier equivalent
    // of the open FREE signup path above, not a profile-management endpoint. A blank
    // paypalSubscriptionId is intentional here: it fails @Valid with 400, which -- since a
    // request blocked by Spring Security would 401 before ever reaching the controller/validator
    // -- is proof this route isn't gated by auth, without making any real call to PayPal's API
    // (real PayPalClient/RestTemplate beans are wired in this full context, so a valid request
    // here would be a live network call, which this suite deliberately avoids).
    @Test
    fun `POST subscribers pro remains open and unauthenticated`() {
        mockMvc.perform(
            post("/api/v1/subscribers/pro")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"pro-open@example.com","paypalSubscriptionId":""}""")
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `full round trip - request magic link, verify the emailed token, use the bearer token, token is then single-use`() {
        val subscriber = createSubscriber("roundtrip@example.com")

        mockMvc.perform(
            post("/api/v1/auth/magic-link")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"roundtrip@example.com"}""")
        ).andExpect(status().isOk)

        val messageCaptor = ArgumentCaptor.forClass(SimpleMailMessage::class.java)
        verify(javaMailSender).send(messageCaptor.capture())
        val rawToken = Regex("token=(\\S+)").find(messageCaptor.value.text!!)!!.groupValues[1]

        val verifyResult = mockMvc.perform(get("/api/v1/auth/verify").param("token", rawToken))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accessToken").exists())
            .andReturn()
        val accessToken = objectMapper.readTree(verifyResult.response.contentAsString).get("accessToken").asText()

        mockMvc.perform(
            get("/api/v1/subscribers/${subscriber.id}/profiles")
                .header("Authorization", "Bearer $accessToken")
        ).andExpect(status().isOk)

        // Single-use: verifying the same raw token again must fail even though it hasn't expired.
        mockMvc.perform(get("/api/v1/auth/verify").param("token", rawToken))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("already_used"))
    }

    @Test
    fun `requesting a magic link for a non-existent email sends no mail and still returns 200`() {
        mockMvc.perform(
            post("/api/v1/auth/magic-link")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"does-not-exist@example.com"}""")
        ).andExpect(status().isOk)

        verify(javaMailSender, org.mockito.Mockito.never()).send(org.mockito.ArgumentMatchers.any(SimpleMailMessage::class.java))
    }

    // ---- Regression: ownership check must not be bypassable via URI encoding ----
    //
    // A prior version of SubscriberOwnershipInterceptor compared the ownership check against
    // the raw (possibly percent-encoded) request URI. Percent-encoding a single character of
    // "profiles" (e.g. "prof%69les", which decodes to "profiles") made that regex miss even
    // though Spring's own decoded-path request mapping still routed the request to
    // SubscriberController — a full 403 bypass. These tests hit that exact encoded path and
    // assert the request is still rejected for a token that isn't the path subscriber's own.

    @Test
    fun `a read via a percent-encoded 'profiles' segment is still rejected for a non-owning token`() {
        val owner = createSubscriber("encoded-read-owner@example.com")
        val intruder = createSubscriber("encoded-read-intruder@example.com")
        val intruderToken = bearerTokenService.issue(intruder.id)

        // "prof%69les" percent-decodes to "profiles" — must resolve to the same guarded route.
        val encodedUri = URI("/api/v1/subscribers/${owner.id}/prof%69les")

        mockMvc.perform(get(encodedUri).header("Authorization", "Bearer $intruderToken"))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `a write via a percent-encoded 'profiles' segment is still rejected and plants nothing`() {
        val owner = createSubscriber("encoded-write-owner@example.com")
        val intruder = createSubscriber("encoded-write-intruder@example.com")
        val intruderToken = bearerTokenService.issue(intruder.id)
        val profilesBefore = profileRepository.findBySubscriberId(owner.id).size

        val encodedUri = URI("/api/v1/subscribers/${owner.id}/prof%69les")

        mockMvc.perform(
            post(encodedUri)
                .header("Authorization", "Bearer $intruderToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"sectors":["IT"]}""")
        ).andExpect(status().isForbidden)

        assertEquals(profilesBefore, profileRepository.findBySubscriberId(owner.id).size)
    }

    @Test
    fun `the owner's own token still works against the percent-encoded path`() {
        val owner = createSubscriber("encoded-self@example.com")
        val ownerToken = bearerTokenService.issue(owner.id)

        val encodedUri = URI("/api/v1/subscribers/${owner.id}/prof%69les")

        mockMvc.perform(get(encodedUri).header("Authorization", "Bearer $ownerToken"))
            .andExpect(status().isOk)
    }

    // ---- TP-065: committed bypass-variant regression suite ----
    //
    // During TP-038's review, a Checker independently probed several more path-mangling bypass
    // techniques (beyond the single-character percent-encoding above) against this same
    // ownership check, none of which were committed as tests. This suite closes that gap.
    //
    // Every case below uses a non-owning ("intruder") bearer token against the *owner's*
    // subscriber id, exactly like the percent-encoding tests above -- if any of these techniques
    // bypassed the ownership check, the response would be 200 with the owner's (empty, but
    // real) profile list. None of them are: each is rejected before a non-owning caller could
    // ever reach [com.tenderpulse.api.SubscriberController], by one of two independent layers,
    // and this suite asserts the actual status each layer produces rather than assuming both
    // layers behave identically:
    //
    // - Spring Security's default `StrictHttpFirewall` rejects several of these techniques
    //   itself, before routing/dispatch -- responding 400 (via `RequestRejectedException`,
    //   converted to a plain 400 response by Spring Security's own filter) without this
    //   application's filters or [SubscriberOwnershipInterceptor] ever running at all. This is a
    //   *stronger* defence than a 401/403 from our own code would be, not a weaker one: the
    //   request never reaches application logic in the first place.
    // - The remaining techniques aren't blocked by the firewall, but also don't decode/normalize
    //   to anything Spring's request mapping considers equivalent to the real
    //   `/api/v1/subscribers/{id}/profiles` route, so they 404 -- again, never reaching the
    //   controller or returning any subscriber data.
    //
    // Either outcome is a pass for this suite's purpose (proving no cross-subscriber data leak);
    // what would constitute an actual regression is any of these ever returning 200.

    @Test
    fun `double-encoded path segment is rejected, not routed to the profiles endpoint`() {
        val owner = createSubscriber("bypass-double-encoding@example.com")
        val intruderToken = bearerTokenService.issue(createSubscriber("bypass-double-encoding-intruder@example.com").id)

        // "%2570rofiles" single-decodes to the literal "%70rofiles" (the firewall's own decode
        // pass), not "profiles" -- a second decode would be needed to reach "profiles", which
        // neither the firewall nor Spring's routing performs. Spring Security's
        // StrictHttpFirewall rejects the encoded '%' (`allowUrlEncodedPercent = false` by
        // default) before this even reaches routing.
        val uri = URI("/api/v1/subscribers/${owner.id}/%2570rofiles")

        mockMvc.perform(get(uri).header("Authorization", "Bearer $intruderToken"))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `literal dot-dot path traversal segment is rejected`() {
        val owner = createSubscriber("bypass-traversal-literal@example.com")
        val intruderToken = bearerTokenService.issue(createSubscriber("bypass-traversal-literal-intruder@example.com").id)

        val uri = URI("/api/v1/subscribers/${owner.id}/../${owner.id}/profiles")

        mockMvc.perform(get(uri).header("Authorization", "Bearer $intruderToken"))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `percent-encoded dot-dot path traversal segment is rejected`() {
        val owner = createSubscriber("bypass-traversal-encoded@example.com")
        val intruderToken = bearerTokenService.issue(createSubscriber("bypass-traversal-encoded-intruder@example.com").id)

        val uri = URI("/api/v1/subscribers/${owner.id}/%2e%2e/${owner.id}/profiles")

        mockMvc.perform(get(uri).header("Authorization", "Bearer $intruderToken"))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `a null byte in the path is rejected`() {
        val owner = createSubscriber("bypass-null-byte@example.com")
        val intruderToken = bearerTokenService.issue(createSubscriber("bypass-null-byte-intruder@example.com").id)

        val uri = URI("/api/v1/subscribers/${owner.id}/profiles%00")

        mockMvc.perform(get(uri).header("Authorization", "Bearer $intruderToken"))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `an overlong UTF-8 encoded slash does not smuggle a route past the real profiles path`() {
        val owner = createSubscriber("bypass-overlong-utf8@example.com")
        val intruderToken = bearerTokenService.issue(createSubscriber("bypass-overlong-utf8-intruder@example.com").id)

        // "%c0%af" is a classic overlong (invalid, non-canonical) UTF-8 encoding of '/', historically
        // used to smuggle path separators past naive decoders/WAFs. It is not decoded as '/' here,
        // so this never matches the "profiles" route at all -- 404, not a bypass.
        val uri = URI("/api/v1/subscribers/${owner.id}/%c0%afprofiles")

        mockMvc.perform(get(uri).header("Authorization", "Bearer $intruderToken"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `a matrix parameter on the subscriber id segment is rejected`() {
        val owner = createSubscriber("bypass-matrix-param@example.com")
        val intruderToken = bearerTokenService.issue(createSubscriber("bypass-matrix-param-intruder@example.com").id)

        // ";foo=bar" is a matrix (path) parameter appended to the {id} segment -- a technique
        // sometimes used to make a raw-URI-parsing guard mis-tokenize the path. StrictHttpFirewall
        // rejects semicolons in the path by default (`allowSemicolon = false`).
        val uri = URI("/api/v1/subscribers/${owner.id};foo=bar/profiles")

        mockMvc.perform(get(uri).header("Authorization", "Bearer $intruderToken"))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `an uppercase path segment does not case-insensitively match the profiles route`() {
        val owner = createSubscriber("bypass-case-variation@example.com")
        val intruderToken = bearerTokenService.issue(createSubscriber("bypass-case-variation-intruder@example.com").id)

        // Spring's path matching is case-sensitive by default -- "PROFILES" simply doesn't match
        // the "profiles" route (404), it isn't silently normalized to it.
        val uri = URI("/api/v1/subscribers/${owner.id}/PROFILES")

        mockMvc.perform(get(uri).header("Authorization", "Bearer $intruderToken"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `a trailing slash after profiles does not match the route`() {
        val owner = createSubscriber("bypass-trailing-slash@example.com")
        val intruderToken = bearerTokenService.issue(createSubscriber("bypass-trailing-slash-intruder@example.com").id)

        // Spring Boot 3 / Spring MVC 6 no longer treat a trailing slash as equivalent to the
        // same path without one by default, so this 404s rather than reaching the controller.
        val uri = URI("/api/v1/subscribers/${owner.id}/profiles/")

        mockMvc.perform(get(uri).header("Authorization", "Bearer $intruderToken"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `a double slash before profiles is rejected`() {
        val owner = createSubscriber("bypass-double-slash@example.com")
        val intruderToken = bearerTokenService.issue(createSubscriber("bypass-double-slash-intruder@example.com").id)

        // StrictHttpFirewall rejects "//" in the path by default.
        val uri = URI("/api/v1/subscribers/${owner.id}//profiles")

        mockMvc.perform(get(uri).header("Authorization", "Bearer $intruderToken"))
            .andExpect(status().isBadRequest)
    }
}
