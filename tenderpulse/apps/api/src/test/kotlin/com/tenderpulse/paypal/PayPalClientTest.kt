package com.tenderpulse.paypal

import com.tenderpulse.domain.PayPalApiException
import com.tenderpulse.domain.PayPalPlanPricingException
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestTemplate
import java.math.BigDecimal
import java.net.URI

/**
 * Unit tests for [PayPalClient] (TP-042). No live network calls — [RestTemplate] is a mockk mock,
 * mirroring the pattern already used for [com.tenderpulse.aggregation.sources.PrazEgpTenderSource].
 */
class PayPalClientTest {

    private val restTemplate = mockk<RestTemplate>()
    private val client = PayPalClient(
        restTemplate = restTemplate,
        baseUrl = "https://api-m.sandbox.paypal.com",
        clientId = "test-client-id",
        clientSecret = "test-client-secret"
    )

    private fun stubToken(accessToken: String = "test-access-token", expiresIn: Long = 32000) {
        every {
            restTemplate.postForObject(
                "https://api-m.sandbox.paypal.com/v1/oauth2/token",
                any<HttpEntity<*>>(),
                PayPalTokenResponse::class.java
            )
        } returns PayPalTokenResponse(accessToken = accessToken, tokenType = "Bearer", expiresIn = expiresIn)
    }

    @Test
    fun `fetchSubscription returns the subscription for a valid, active subscription`() {
        stubToken()
        every {
            restTemplate.exchange(
                "https://api-m.sandbox.paypal.com/v1/billing/subscriptions/I-VALID123",
                HttpMethod.GET,
                any<HttpEntity<Void>>(),
                PayPalSubscriptionResponse::class.java
            )
        } returns ResponseEntity.ok(
            PayPalSubscriptionResponse(
                id = "I-VALID123",
                status = "ACTIVE",
                planId = "P-EXPECTED",
                subscriber = PayPalSubscriberInfo(emailAddress = "payer@example.com")
            )
        )

        val result = client.fetchSubscription("I-VALID123")

        assertEquals("I-VALID123", result?.id)
        assertEquals("ACTIVE", result?.status)
        assertEquals("P-EXPECTED", result?.planId)
        assertEquals("payer@example.com", result?.subscriber?.emailAddress)
    }

    @Test
    fun `fetchSubscription returns null for a nonexistent subscription id (PayPal 404)`() {
        stubToken()
        every {
            restTemplate.exchange(
                any<String>(),
                HttpMethod.GET,
                any<HttpEntity<Void>>(),
                PayPalSubscriptionResponse::class.java
            )
        } throws HttpClientErrorException.NotFound.create(
            HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, ByteArray(0), null
        )

        val result = client.fetchSubscription("I-DOES-NOT-EXIST")

        assertNull(result)
    }

    @Test
    fun `fetchSubscription wraps a PayPal 5xx failure as PayPalApiException`() {
        stubToken()
        every {
            restTemplate.exchange(
                any<String>(),
                HttpMethod.GET,
                any<HttpEntity<Void>>(),
                PayPalSubscriptionResponse::class.java
            )
        } throws HttpServerErrorException.create(
            HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", HttpHeaders.EMPTY, ByteArray(0), null
        )

        assertThrows(PayPalApiException::class.java) {
            client.fetchSubscription("I-VALID123")
        }
    }

    @Test
    fun `fetchSubscription wraps a network timeout as PayPalApiException`() {
        stubToken()
        every {
            restTemplate.exchange(
                any<String>(),
                HttpMethod.GET,
                any<HttpEntity<Void>>(),
                PayPalSubscriptionResponse::class.java
            )
        } throws ResourceAccessException("Read timed out")

        assertThrows(PayPalApiException::class.java) {
            client.fetchSubscription("I-VALID123")
        }
    }

    @Test
    fun `a failed OAuth token request itself is also wrapped as PayPalApiException`() {
        every {
            restTemplate.postForObject(
                any<String>(),
                any<HttpEntity<*>>(),
                PayPalTokenResponse::class.java
            )
        } throws ResourceAccessException("Connection refused")

        assertThrows(PayPalApiException::class.java) {
            client.fetchSubscription("I-VALID123")
        }
    }

    @Test
    fun `access token is cached and reused across multiple fetchSubscription calls`() {
        stubToken()
        every {
            restTemplate.exchange(
                any<String>(),
                HttpMethod.GET,
                any<HttpEntity<Void>>(),
                PayPalSubscriptionResponse::class.java
            )
        } returns ResponseEntity.ok(
            PayPalSubscriptionResponse(id = "I-VALID123", status = "ACTIVE", planId = "P-EXPECTED")
        )

        client.fetchSubscription("I-VALID123")
        client.fetchSubscription("I-VALID123")
        client.fetchSubscription("I-VALID123")

        // Token endpoint should only be hit once — the short-lived token is cached, not
        // re-fetched per request.
        verify(exactly = 1) {
            restTemplate.postForObject(
                "https://api-m.sandbox.paypal.com/v1/oauth2/token",
                any<HttpEntity<*>>(),
                PayPalTokenResponse::class.java
            )
        }
    }

    @Test
    fun `an expiring token is refreshed rather than reused`() {
        // expiresIn just over the 60s safety buffer, so it's already "expired" for caching
        // purposes by the time of a second call.
        stubToken(accessToken = "short-lived-token", expiresIn = 60)
        every {
            restTemplate.exchange(
                any<String>(),
                HttpMethod.GET,
                any<HttpEntity<Void>>(),
                PayPalSubscriptionResponse::class.java
            )
        } returns ResponseEntity.ok(
            PayPalSubscriptionResponse(id = "I-VALID123", status = "ACTIVE", planId = "P-EXPECTED")
        )

        client.fetchSubscription("I-VALID123")
        client.fetchSubscription("I-VALID123")

        verify(atLeast = 2) {
            restTemplate.postForObject(
                "https://api-m.sandbox.paypal.com/v1/oauth2/token",
                any<HttpEntity<*>>(),
                PayPalTokenResponse::class.java
            )
        }
    }

    @Test
    fun `access token is sent as a Bearer authorization header on the subscription lookup`() {
        stubToken(accessToken = "the-access-token")
        val headersEntity = slot<HttpEntity<Void>>()
        every {
            restTemplate.exchange(
                any<String>(),
                HttpMethod.GET,
                capture(headersEntity),
                PayPalSubscriptionResponse::class.java
            )
        } returns ResponseEntity.ok(
            PayPalSubscriptionResponse(id = "I-VALID123", status = "ACTIVE", planId = "P-EXPECTED")
        )

        client.fetchSubscription("I-VALID123")

        assertEquals("Bearer the-access-token", headersEntity.captured.headers.getFirst("Authorization"))
    }

    // ---- updatePlanPricing (TP-044) ----

    /** Test case 5: valid input -> the PayPal update-pricing-schemes call succeeds. */
    @Test
    fun `updatePlanPricing sends the expected pricing scheme body and succeeds for a valid plan`() {
        stubToken()
        val entityCaptor = slot<HttpEntity<UpdatePricingSchemesRequest>>()
        every {
            restTemplate.exchange(
                URI.create("https://api-m.sandbox.paypal.com/v1/billing/plans/P-VALIDPLAN/update-pricing-schemes"),
                HttpMethod.POST,
                capture(entityCaptor),
                Void::class.java
            )
        } returns ResponseEntity.noContent().build()

        client.updatePlanPricing(
            planId = "P-VALIDPLAN",
            currencyCode = "USD",
            fixedPrice = BigDecimal("19.99")
        )

        val sentBody = entityCaptor.captured.body!!
        assertEquals(1, sentBody.pricingSchemes.size)
        assertEquals(1, sentBody.pricingSchemes[0].billingCycleSequence)
        assertEquals("19.99", sentBody.pricingSchemes[0].pricingScheme.fixedPrice.value)
        assertEquals("USD", sentBody.pricingSchemes[0].pricingScheme.fixedPrice.currencyCode)
        assertEquals("Bearer test-access-token", entityCaptor.captured.headers.getFirst("Authorization"))
    }

    @Test
    fun `updatePlanPricing honors a non-default billing cycle sequence`() {
        stubToken()
        val entityCaptor = slot<HttpEntity<UpdatePricingSchemesRequest>>()
        every {
            restTemplate.exchange(any<URI>(), HttpMethod.POST, capture(entityCaptor), Void::class.java)
        } returns ResponseEntity.noContent().build()

        client.updatePlanPricing(
            planId = "P-VALIDPLAN",
            currencyCode = "EUR",
            fixedPrice = BigDecimal("5.00"),
            billingCycleSequence = 2
        )

        assertEquals(2, entityCaptor.captured.body!!.pricingSchemes[0].billingCycleSequence)
    }

    /** Test case 6 (nonexistent plan id): PayPal 404 -> PayPalPlanPricingException, not PayPalApiException. */
    @Test
    fun `updatePlanPricing for a nonexistent plan id throws PayPalPlanPricingException`() {
        stubToken()
        every {
            restTemplate.exchange(any<URI>(), HttpMethod.POST, any<HttpEntity<*>>(), Void::class.java)
        } throws HttpClientErrorException.NotFound.create(
            HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, ByteArray(0), null
        )

        val ex = assertThrows(PayPalPlanPricingException::class.java) {
            client.updatePlanPricing("P-DOES-NOT-EXIST", "USD", BigDecimal("10.00"))
        }
        assertTrue(ex.message!!.contains("P-DOES-NOT-EXIST"))
    }

    /** Test case 6 (invalid input): PayPal 422 for a bad pricing scheme -> also PayPalPlanPricingException (4xx). */
    @Test
    fun `updatePlanPricing for an invalid pricing scheme throws PayPalPlanPricingException`() {
        stubToken()
        every {
            restTemplate.exchange(any<URI>(), HttpMethod.POST, any<HttpEntity<*>>(), Void::class.java)
        } throws HttpClientErrorException.UnprocessableEntity.create(
            HttpStatus.UNPROCESSABLE_ENTITY, "Unprocessable Entity", HttpHeaders.EMPTY, ByteArray(0), null
        )

        assertThrows(PayPalPlanPricingException::class.java) {
            client.updatePlanPricing("P-VALIDPLAN", "USD", BigDecimal("-5.00"))
        }
    }

    @Test
    fun `updatePlanPricing wraps a PayPal 5xx failure as PayPalApiException, not PayPalPlanPricingException`() {
        stubToken()
        every {
            restTemplate.exchange(any<URI>(), HttpMethod.POST, any<HttpEntity<*>>(), Void::class.java)
        } throws HttpServerErrorException.create(
            HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", HttpHeaders.EMPTY, ByteArray(0), null
        )

        assertThrows(PayPalApiException::class.java) {
            client.updatePlanPricing("P-VALIDPLAN", "USD", BigDecimal("10.00"))
        }
    }

    @Test
    fun `updatePlanPricing wraps a network timeout as PayPalApiException`() {
        stubToken()
        every {
            restTemplate.exchange(any<URI>(), HttpMethod.POST, any<HttpEntity<*>>(), Void::class.java)
        } throws ResourceAccessException("Read timed out")

        assertThrows(PayPalApiException::class.java) {
            client.updatePlanPricing("P-VALIDPLAN", "USD", BigDecimal("10.00"))
        }
    }

    /**
     * Issue #68 defense-in-depth: even if a `/`-bearing planId ever reached this client (it
     * shouldn't — [com.tenderpulse.api.AdminController.updatePlanPricing] rejects it first), the
     * request URL is built via [org.springframework.web.util.UriComponentsBuilder.pathSegment]
     * rather than string interpolation, so the `/` is percent-encoded *within* the plan-id
     * segment rather than being treated as a path separator that could redirect the call to a
     * different PayPal resource.
     */
    @Test
    fun `updatePlanPricing percent-encodes a slash in planId instead of letting it restructure the URL`() {
        stubToken()
        val uriCaptor = slot<URI>()
        every {
            restTemplate.exchange(capture(uriCaptor), HttpMethod.POST, any<HttpEntity<*>>(), Void::class.java)
        } returns ResponseEntity.noContent().build()

        client.updatePlanPricing("P-VALID/../admin-only", "USD", BigDecimal("10.00"))

        val requestedUri = uriCaptor.captured
        // The slash lands inside one percent-encoded path segment, never splitting the path.
        assertEquals(
            "https://api-m.sandbox.paypal.com/v1/billing/plans/P-VALID%2F..%2Fadmin-only/update-pricing-schemes",
            requestedUri.toString()
        )
        assertEquals(5, requestedUri.rawPath.split("/").size - 1)
    }
}
