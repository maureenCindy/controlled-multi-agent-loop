package com.tenderpulse.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.tenderpulse.domain.ConflictException
import com.tenderpulse.domain.InterestProfile
import com.tenderpulse.domain.NotFoundException
import com.tenderpulse.domain.PayPalApiException
import com.tenderpulse.domain.Sector
import com.tenderpulse.domain.Subscriber
import com.tenderpulse.domain.SubscriptionTier
import com.tenderpulse.domain.SubscriptionVerificationException
import com.tenderpulse.subscriber.SubscriberService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
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
import java.util.UUID

/**
 * Standalone MockMvc tests for [SubscriberController] (TP-010, TP-037). No Spring context is
 * booted; [SubscriberService] is a mockk mock, so these tests exercise only the controller's
 * own responsibilities — request validation (`@Valid`), delegation to the service, HTTP status
 * mapping, and entity -> DTO mapping — not the business logic itself (that's
 * [com.tenderpulse.subscriber.SubscriberServiceTest]). The request body is validated by the
 * default standalone validator (registered automatically by
 * [MockMvcBuilders.standaloneSetup]), which exercises the same `@Valid` / `@ResponseStatus`
 * wiring the real application uses. [GlobalExceptionHandler] (#64) is registered explicitly via
 * `setControllerAdvice`, since standalone setup does not pick up `@RestControllerAdvice` beans
 * from a Spring context the way `@SpringBootTest`/`@WebMvcTest` would.
 */
class SubscriberControllerTest {

    private val subscriberService = mockk<SubscriberService>()
    private val objectMapper: ObjectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
    private lateinit var mockMvc: MockMvc

    private val subscriberId: UUID = UUID.randomUUID()
    private val subscriber = Subscriber(id = subscriberId, email = "sub@example.com")

    @BeforeEach
    fun setUp() {
        val controller = SubscriberController(subscriberService)
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(GlobalExceptionHandler())
            .setMessageConverters(MappingJackson2HttpMessageConverter(objectMapper))
            .build()
    }

    private fun profilesUrl(id: UUID = subscriberId) = "/api/v1/subscribers/$id/profiles"

    private fun profileJson(
        sectors: Set<Sector> = setOf(Sector.IT),
        valueMin: BigDecimal? = null,
        valueMax: BigDecimal? = null,
        region: String? = null,
        active: Boolean? = null,
        name: String = "Test Profile"
    ): String {
        val body = LinkedHashMap<String, Any?>()
        body["name"] = name
        body["sectors"] = sectors
        body["valueMin"] = valueMin
        body["valueMax"] = valueMax
        body["region"] = region
        if (active != null) body["active"] = active
        return objectMapper.writeValueAsString(body)
    }

    // ---- register ----

    @Test
    fun `register returns 201 with a subscriber response DTO, not the raw entity`() {
        every { subscriberService.register(any()) } returns
            Subscriber(email = "new@example.com", tier = SubscriptionTier.FREE)

        mockMvc.perform(
            post("/api/v1/subscribers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"new@example.com"}""")
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.email").value("new@example.com"))
            .andExpect(jsonPath("$.tier").value("FREE"))
            .andExpect(jsonPath("$.id").exists())
    }

    @Test
    fun `register with an already-registered email returns 409`() {
        every { subscriberService.register(any()) } throws ConflictException("Email already registered")

        mockMvc.perform(
            post("/api/v1/subscribers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"sub@example.com"}""")
        ).andExpect(status().isConflict)
    }

    /**
     * #64, test case 1/2: the *losing* side of a register() TOCTOU race — both concurrent
     * requests pass the app-level `findByEmail` check, so the DB's unique constraint (not
     * [ConflictException]) is what rejects the second save, surfacing as a raw
     * [DataIntegrityViolationException] out of the service. [GlobalExceptionHandler] must map
     * that to the same clean 409 the "normal" duplicate-email path gets, with a structured body
     * and no stack trace.
     */
    @Test
    fun `register racing on a duplicate email at the DB level returns 409 with a structured body, not 500`() {
        every { subscriberService.register(any()) } throws
            DataIntegrityViolationException("could not execute statement; constraint [uk_subscribers_email]")

        mockMvc.perform(
            post("/api/v1/subscribers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"sub@example.com"}""")
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("conflict"))
            .andExpect(jsonPath("$.message").exists())
    }

    @Test
    fun `register with an invalid email returns 400`() {
        mockMvc.perform(
            post("/api/v1/subscribers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"not-an-email"}""")
        ).andExpect(status().isBadRequest)

        verify(exactly = 0) { subscriberService.register(any()) }
    }

    // ---- registerPro (TP-042) ----

    private fun proJson(email: String = "pro@example.com", subscriptionId: String = "I-VALIDSUB123") =
        """{"email":"$email","paypalSubscriptionId":"$subscriptionId"}"""

    @Test
    fun `registerPro returns 200 with the upgraded subscriber DTO, including the stored subscription id`() {
        every { subscriberService.registerPro(any()) } returns Subscriber(
            email = "pro@example.com",
            tier = SubscriptionTier.PAID,
            paypalSubscriptionId = "I-VALIDSUB123"
        )

        mockMvc.perform(
            post("/api/v1/subscribers/pro")
                .contentType(MediaType.APPLICATION_JSON)
                .content(proJson())
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.email").value("pro@example.com"))
            .andExpect(jsonPath("$.tier").value("PAID"))
            .andExpect(jsonPath("$.paypalSubscriptionId").value("I-VALIDSUB123"))
    }

    @Test
    fun `registerPro with an unverifiable subscription returns 400 and does not touch the free path`() {
        every { subscriberService.registerPro(any()) } throws
            SubscriptionVerificationException("PayPal subscription 'I-FAKE' was not found")

        mockMvc.perform(
            post("/api/v1/subscribers/pro")
                .contentType(MediaType.APPLICATION_JSON)
                .content(proJson(subscriptionId = "I-FAKE"))
        ).andExpect(status().isBadRequest)

        verify(exactly = 0) { subscriberService.register(any()) }
    }

    /**
     * #64, test case 2: the losing side of a `registerPro()` TOCTOU race on
     * `paypalSubscriptionId` — see the equivalent email-uniqueness test above for the full
     * rationale; [SubscriberServiceConcurrencyTest] exercises the real race against the
     * database, this test just proves the controller/handler wiring maps the resulting
     * exception to 409 rather than an unhandled 500.
     */
    @Test
    fun `registerPro racing on a duplicate paypalSubscriptionId at the DB level returns 409, not 500`() {
        every { subscriberService.registerPro(any()) } throws
            DataIntegrityViolationException("could not execute statement; constraint [uk_subscribers_paypal_subscription_id]")

        mockMvc.perform(
            post("/api/v1/subscribers/pro")
                .contentType(MediaType.APPLICATION_JSON)
                .content(proJson())
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("conflict"))
            .andExpect(jsonPath("$.message").exists())
    }

    @Test
    fun `registerPro when PayPal's API call itself fails returns 502`() {
        every { subscriberService.registerPro(any()) } throws PayPalApiException("PayPal timed out")

        mockMvc.perform(
            post("/api/v1/subscribers/pro")
                .contentType(MediaType.APPLICATION_JSON)
                .content(proJson())
        ).andExpect(status().isBadGateway)
    }

    @Test
    fun `registerPro with a missing subscription id returns 400 and never calls the service`() {
        mockMvc.perform(
            post("/api/v1/subscribers/pro")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"pro@example.com","paypalSubscriptionId":""}""")
        ).andExpect(status().isBadRequest)

        verify(exactly = 0) { subscriberService.registerPro(any()) }
    }

    /**
     * Issue #81, test case 2: a `paypalSubscriptionId` containing `/` is rejected (400) before
     * [SubscriberService] (and therefore [com.tenderpulse.paypal.PayPalClient.fetchSubscription])
     * is ever reached. Unlike issue #68's `planId` path variable, `paypalSubscriptionId` is a
     * `@RequestBody` field here — it never touches the request URL at all, so there is no
     * `StrictHttpFirewall` question to empirically resolve the way #68's PR required: the value is
     * plain JSON body text, and [com.tenderpulse.subscriber.ProSubscribeRequest]'s `@field:Pattern`
     * (validated by the standalone MockMvc setup's default `@Valid` wiring, same as the
     * `register with an invalid email` test above) is the only thing that can reject it, and does.
     */
    @Test
    fun `registerPro with a subscription id containing a slash returns 400 and never calls the service`() {
        mockMvc.perform(
            post("/api/v1/subscribers/pro")
                .contentType(MediaType.APPLICATION_JSON)
                .content(proJson(subscriptionId = "I-VALID/../admin-only"))
        ).andExpect(status().isBadRequest)

        verify(exactly = 0) { subscriberService.registerPro(any()) }
    }

    /** Issue #81, test case 3: a `paypalSubscriptionId` containing `..` is rejected (400). */
    @Test
    fun `registerPro with a subscription id containing dot-dot returns 400 and never calls the service`() {
        mockMvc.perform(
            post("/api/v1/subscribers/pro")
                .contentType(MediaType.APPLICATION_JSON)
                .content(proJson(subscriptionId = "I-VALID..EVIL"))
        ).andExpect(status().isBadRequest)

        verify(exactly = 0) { subscriberService.registerPro(any()) }
    }

    /** Issue #81, test case 4: a `paypalSubscriptionId` containing `?` is rejected (400). */
    @Test
    fun `registerPro with a subscription id containing a question mark returns 400 and never calls the service`() {
        mockMvc.perform(
            post("/api/v1/subscribers/pro")
                .contentType(MediaType.APPLICATION_JSON)
                .content(proJson(subscriptionId = "I-VALID?evil=true"))
        ).andExpect(status().isBadRequest)

        verify(exactly = 0) { subscriberService.registerPro(any()) }
    }

    // ---- create profile ----

    @Test
    fun `create profile returns 201 and delegates to the service`() {
        val profile = InterestProfile(
            subscriber = subscriber,
            name = "Harare IT Profile",
            sectors = mutableSetOf(Sector.IT),
            valueMin = BigDecimal("100000"),
            valueMax = BigDecimal("500000"),
            region = "Harare"
        )
        every { subscriberService.createProfile(subscriberId, any()) } returns profile

        mockMvc.perform(
            post(profilesUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    profileJson(
                        name = "Harare IT Profile",
                        sectors = setOf(Sector.IT),
                        valueMin = BigDecimal("100000"),
                        valueMax = BigDecimal("500000"),
                        region = "Harare"
                    )
                )
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").value(profile.id.toString()))
            .andExpect(jsonPath("$.name").value("Harare IT Profile"))
            .andExpect(jsonPath("$.sectors[0]").value("IT"))
            .andExpect(jsonPath("$.valueMin").value(100000))
            .andExpect(jsonPath("$.valueMax").value(500000))
            .andExpect(jsonPath("$.region").value("Harare"))
            .andExpect(jsonPath("$.active").value(true))
            .andExpect(jsonPath("$.subscriber").doesNotExist())
            .andExpect(jsonPath("$.subscriberId").doesNotExist())
            .andExpect(jsonPath("$.email").doesNotExist())
    }

    @Test
    fun `create with valueMin greater than valueMax returns 400`() {
        mockMvc.perform(
            post(profilesUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .content(profileJson(valueMin = BigDecimal("500000"), valueMax = BigDecimal("100000")))
        ).andExpect(status().isBadRequest)

        verify(exactly = 0) { subscriberService.createProfile(any(), any()) }
    }

    /** Issue #58 AC: `name` is required on create. */
    @Test
    fun `create with a blank name returns 400`() {
        mockMvc.perform(
            post(profilesUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .content(profileJson(name = ""))
        ).andExpect(status().isBadRequest)

        verify(exactly = 0) { subscriberService.createProfile(any(), any()) }
    }

    @Test
    fun `create with equal valueMin and valueMax is accepted`() {
        val profile = InterestProfile(
            subscriber = subscriber,
            name = "Equal Value Profile",
            valueMin = BigDecimal("250000"),
            valueMax = BigDecimal("250000")
        )
        every { subscriberService.createProfile(subscriberId, any()) } returns profile

        mockMvc.perform(
            post(profilesUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .content(profileJson(valueMin = BigDecimal("250000"), valueMax = BigDecimal("250000")))
        ).andExpect(status().isCreated)
    }

    @Test
    fun `create for unknown subscriber returns 404`() {
        every { subscriberService.createProfile(subscriberId, any()) } throws
            NotFoundException("Subscriber $subscriberId")

        mockMvc.perform(
            post(profilesUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .content(profileJson())
        ).andExpect(status().isNotFound)
    }

    // ---- list ----

    @Test
    fun `list returns all profiles for the subscriber including an inactive one`() {
        val activeProfile = InterestProfile(subscriber = subscriber, name = "Active Profile", active = true)
        val inactiveProfile = InterestProfile(subscriber = subscriber, name = "Inactive Profile", active = false)
        every { subscriberService.listProfiles(subscriberId) } returns listOf(activeProfile, inactiveProfile)

        mockMvc.perform(get(profilesUrl()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].id").value(activeProfile.id.toString()))
            .andExpect(jsonPath("$[0].name").value("Active Profile"))
            .andExpect(jsonPath("$[0].active").value(true))
            .andExpect(jsonPath("$[1].id").value(inactiveProfile.id.toString()))
            .andExpect(jsonPath("$[1].name").value("Inactive Profile"))
            .andExpect(jsonPath("$[1].active").value(false))
            .andExpect(jsonPath("$[0].subscriber").doesNotExist())
            .andExpect(jsonPath("$[0].subscriberId").doesNotExist())
            .andExpect(jsonPath("$[0].email").doesNotExist())
    }

    /**
     * Issue #58, test case 4: a subscriber with 2 saved profiles gets both back from the list
     * endpoint, each attributed by its own distinct name.
     */
    @Test
    fun `list returns two profiles for the same subscriber each with their own name`() {
        val profileA = InterestProfile(subscriber = subscriber, name = "Construction Tenders")
        val profileB = InterestProfile(subscriber = subscriber, name = "IT Tenders")
        every { subscriberService.listProfiles(subscriberId) } returns listOf(profileA, profileB)

        mockMvc.perform(get(profilesUrl()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].name").value("Construction Tenders"))
            .andExpect(jsonPath("$[1].name").value("IT Tenders"))
    }

    @Test
    fun `list for unknown subscriber returns 404`() {
        every { subscriberService.listProfiles(subscriberId) } throws NotFoundException("Subscriber $subscriberId")

        mockMvc.perform(get(profilesUrl())).andExpect(status().isNotFound)
    }

    // ---- update ----

    @Test
    fun `update mutates the intended fields and returns the mapped DTO`() {
        val profileId = UUID.randomUUID()
        val updated = InterestProfile(
            id = profileId,
            subscriber = subscriber,
            name = "Test Profile",
            sectors = mutableSetOf(Sector.HEALTHCARE),
            region = "Bulawayo"
        )
        every { subscriberService.updateProfile(subscriberId, profileId, any()) } returns updated

        mockMvc.perform(
            put("${profilesUrl()}/$profileId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(profileJson(sectors = setOf(Sector.HEALTHCARE), region = "Bulawayo"))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(profileId.toString()))
            .andExpect(jsonPath("$.sectors[0]").value("HEALTHCARE"))
            .andExpect(jsonPath("$.region").value("Bulawayo"))
            .andExpect(jsonPath("$.subscriber").doesNotExist())
            .andExpect(jsonPath("$.subscriberId").doesNotExist())
            .andExpect(jsonPath("$.email").doesNotExist())
    }

    @Test
    fun `update can deactivate a profile`() {
        val profileId = UUID.randomUUID()
        val updated = InterestProfile(id = profileId, subscriber = subscriber, name = "Test Profile", active = false)
        every { subscriberService.updateProfile(subscriberId, profileId, any()) } returns updated

        mockMvc.perform(
            put("${profilesUrl()}/$profileId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(profileJson(active = false))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.active").value(false))
            .andExpect(jsonPath("$.email").doesNotExist())
    }

    @Test
    fun `update with valueMin greater than valueMax returns 400`() {
        mockMvc.perform(
            put("${profilesUrl()}/${UUID.randomUUID()}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(profileJson(valueMin = BigDecimal("500000"), valueMax = BigDecimal("100000")))
        ).andExpect(status().isBadRequest)

        verify(exactly = 0) { subscriberService.updateProfile(any(), any(), any()) }
    }

    @Test
    fun `update for unknown subscriber returns 404`() {
        val profileId = UUID.randomUUID()
        every { subscriberService.updateProfile(subscriberId, profileId, any()) } throws
            NotFoundException("Subscriber $subscriberId")

        mockMvc.perform(
            put("${profilesUrl()}/$profileId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(profileJson())
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `update for unknown profile returns 404`() {
        val profileId = UUID.randomUUID()
        every { subscriberService.updateProfile(subscriberId, profileId, any()) } throws
            NotFoundException("Profile $profileId")

        mockMvc.perform(
            put("${profilesUrl()}/$profileId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(profileJson())
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `update for a profile belonging to a different subscriber returns 404`() {
        val profileId = UUID.randomUUID()
        every { subscriberService.updateProfile(subscriberId, profileId, any()) } throws
            NotFoundException("Profile $profileId")

        mockMvc.perform(
            put("${profilesUrl()}/$profileId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(profileJson())
        ).andExpect(status().isNotFound)
    }
}
