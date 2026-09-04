package com.tenderpulse.paypal

import com.tenderpulse.domain.PayPalApiException
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
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
}
