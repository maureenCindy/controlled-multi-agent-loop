package com.tenderpulse.paypal

import com.fasterxml.jackson.annotation.JsonProperty
import com.tenderpulse.domain.PayPalApiException
import com.tenderpulse.domain.PayPalPlanPricingException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestTemplate
import org.springframework.web.util.UriComponentsBuilder
import java.math.BigDecimal
import java.time.Instant

/**
 * Server-to-server PayPal REST API client (TP-042).
 *
 * Owns two calls, both against PayPal's Subscriptions v1 API:
 * - `POST /v1/oauth2/token` — OAuth2 client-credentials grant, Basic Auth with Client ID/Secret.
 *   The returned access token is short-lived; this class caches it in memory and only refreshes
 *   it once it's within [TOKEN_EXPIRY_BUFFER_SECONDS] of expiring, rather than fetching a fresh
 *   token per request.
 * - `GET /v1/billing/subscriptions/{id}` — fetches a subscription's current status and plan ID
 *   for [com.tenderpulse.subscriber.SubscriberService] to verify server-side before any tier
 *   upgrade. The client never trusts a status/plan claimed by the caller — only what PayPal
 *   itself returns for the given subscription ID.
 *
 * `baseUrl` is environment-configured (sandbox vs. live) rather than hardcoded — see
 * [com.tenderpulse.PayPalConfig]. Credentials are never logged.
 */
class PayPalClient(
    private val restTemplate: RestTemplate,
    private val baseUrl: String,
    private val clientId: String,
    private val clientSecret: String
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var cachedToken: CachedToken? = null

    /**
     * Fetches a subscription's details from PayPal.
     *
     * @return the subscription, or `null` if PayPal reports no subscription with that ID exists
     * @throws PayPalApiException if the call to PayPal itself fails (network error, timeout, or a
     *   non-404 error status) — distinct from a "not found" result, which is a normal outcome the
     *   caller (service layer) is expected to reject as an unverifiable subscription.
     */
    fun fetchSubscription(subscriptionId: String): PayPalSubscriptionResponse? {
        val headers = HttpHeaders().apply { setBearerAuth(accessToken()) }
        // Built via UriComponentsBuilder.pathSegment (issue #81, mirroring #68's fix to
        // updatePlanPricing) rather than string interpolation: pathSegment treats subscriptionId
        // as one opaque segment, and `.encode()` percent-encodes any reserved character (e.g.
        // `/`, `?`) *within* it instead of letting it restructure the URL, so a malformed
        // subscriptionId can only ever reach here as an inert value, not a path-altering payload.
        // subscriptionId is also validated at the DTO level (ProSubscribeRequest, via @Valid on
        // SubscriberController.registerPro) before this is ever called, so this is
        // defense-in-depth, not the only line of protection.
        val uri = UriComponentsBuilder.fromUriString(baseUrl)
            .pathSegment("v1", "billing", "subscriptions", subscriptionId)
            .build()
            .encode()
            .toUri()
        return try {
            val response = restTemplate.exchange(
                uri,
                HttpMethod.GET,
                HttpEntity<Void>(headers),
                PayPalSubscriptionResponse::class.java
            )
            response.body
        } catch (e: HttpClientErrorException.NotFound) {
            null
        } catch (e: RestClientException) {
            log.error("PayPal subscription lookup failed for {}: {}", subscriptionId, e.message)
            throw PayPalApiException("Failed to verify PayPal subscription: ${e.message}", e)
        }
    }

    /**
     * Updates a PayPal Plan's pricing scheme (TP-044 admin override) via PayPal's
     * `POST /v1/billing/plans/{plan_id}/update-pricing-schemes`, reusing the same cached OAuth2
     * token as [fetchSubscription] rather than duplicating that logic.
     *
     * PayPal responds `204 No Content` on success (nothing to return); a non-existent plan ID or
     * an invalid pricing scheme both come back as a PayPal 4xx (404 / 422 respectively) — both
     * are surfaced uniformly as [PayPalPlanPricingException] (400) since, in both cases, PayPal
     * itself answered and rejected the *request*, unlike [PayPalApiException] (network error,
     * timeout, or a PayPal-side 5xx).
     *
     * @throws PayPalPlanPricingException if PayPal rejects the plan ID or pricing scheme (4xx)
     * @throws PayPalApiException if the call to PayPal itself fails (network error, timeout, 5xx)
     */
    fun updatePlanPricing(
        planId: String,
        currencyCode: String,
        fixedPrice: BigDecimal,
        billingCycleSequence: Int = 1
    ) {
        val headers = HttpHeaders().apply {
            setBearerAuth(accessToken())
            contentType = MediaType.APPLICATION_JSON
        }
        val body = UpdatePricingSchemesRequest(
            pricingSchemes = listOf(
                PricingSchemeUpdate(
                    billingCycleSequence = billingCycleSequence,
                    pricingScheme = PricingScheme(
                        fixedPrice = FixedPrice(value = fixedPrice.toPlainString(), currencyCode = currencyCode)
                    )
                )
            )
        )
        // Built via UriComponentsBuilder.pathSegment (issue #68) rather than string
        // interpolation: pathSegment treats each argument as one opaque segment, and `.encode()`
        // percent-encodes any reserved character (e.g. `/`, `?`) *within* planId instead of
        // letting it restructure the URL, so a malformed planId can only ever reach here as an
        // inert value, not a path-altering payload. planId is also validated at the controller
        // (AdminController.updatePlanPricing) before this is ever called, so this is
        // defense-in-depth, not the only line of protection.
        val uri = UriComponentsBuilder.fromUriString(baseUrl)
            .pathSegment("v1", "billing", "plans", planId, "update-pricing-schemes")
            .build()
            .encode()
            .toUri()
        try {
            restTemplate.exchange(
                uri,
                HttpMethod.POST,
                HttpEntity(body, headers),
                Void::class.java
            )
        } catch (e: HttpClientErrorException) {
            log.error("PayPal rejected pricing update for plan {}: {} {}", planId, e.statusCode, e.message)
            throw PayPalPlanPricingException(
                "PayPal rejected the pricing update for plan '$planId' (${e.statusCode}): ${e.message}",
                e
            )
        } catch (e: RestClientException) {
            log.error("PayPal plan pricing update failed for {}: {}", planId, e.message)
            throw PayPalApiException("Failed to update PayPal plan pricing: ${e.message}", e)
        }
    }

    /** Returns a cached access token if still valid, otherwise requests and caches a new one. */
    @Synchronized
    private fun accessToken(): String {
        val cached = cachedToken
        if (cached != null && cached.expiresAt.isAfter(Instant.now())) {
            return cached.value
        }

        val headers = HttpHeaders().apply {
            setBasicAuth(clientId, clientSecret)
            contentType = MediaType.APPLICATION_FORM_URLENCODED
        }
        val body = LinkedMultiValueMap<String, String>().apply { add("grant_type", "client_credentials") }

        val tokenResponse = try {
            restTemplate.postForObject(
                "$baseUrl/v1/oauth2/token",
                HttpEntity(body, headers),
                PayPalTokenResponse::class.java
            ) ?: throw PayPalApiException("PayPal token endpoint returned an empty response")
        } catch (e: RestClientException) {
            log.error("PayPal OAuth token request failed: {}", e.message)
            throw PayPalApiException("Failed to obtain PayPal access token: ${e.message}", e)
        }

        val expiresAt = Instant.now().plusSeconds(
            (tokenResponse.expiresIn - TOKEN_EXPIRY_BUFFER_SECONDS).coerceAtLeast(0)
        )
        cachedToken = CachedToken(tokenResponse.accessToken, expiresAt)
        return tokenResponse.accessToken
    }

    private data class CachedToken(val value: String, val expiresAt: Instant)

    companion object {
        /** Refresh the cached token this many seconds before its actual expiry, as a safety margin. */
        private const val TOKEN_EXPIRY_BUFFER_SECONDS = 60L
    }
}

/** Response shape of `POST /v1/oauth2/token`. */
data class PayPalTokenResponse(
    @JsonProperty("access_token") val accessToken: String,
    @JsonProperty("token_type") val tokenType: String? = null,
    @JsonProperty("expires_in") val expiresIn: Long
)

/**
 * Response shape of `GET /v1/billing/subscriptions/{id}` (only the fields TP-042 needs).
 * `status` is expected to be one of `APPROVAL_PENDING`, `APPROVED`, `ACTIVE`, `SUSPENDED`,
 * `CANCELLED`, `EXPIRED` — only `ACTIVE` is treated as sufficient to upgrade a subscriber's tier.
 *
 * `subscriber.email_address` (the PayPal payer's own email) is required so
 * [com.tenderpulse.subscriber.SubscriberService.registerPro] can confirm the subscription
 * actually belongs to the email being upgraded — otherwise one genuinely-ACTIVE subscription
 * could be replayed against arbitrary emails to mint unlimited free Pro accounts.
 */
data class PayPalSubscriptionResponse(
    val id: String,
    val status: String,
    @JsonProperty("plan_id") val planId: String,
    val subscriber: PayPalSubscriberInfo? = null
)

/** The `subscriber` object nested in [PayPalSubscriptionResponse] — the PayPal payer's own details. */
data class PayPalSubscriberInfo(
    @JsonProperty("email_address") val emailAddress: String? = null
)

/** Request body of `POST /v1/billing/plans/{plan_id}/update-pricing-schemes` (TP-044). */
data class UpdatePricingSchemesRequest(
    @JsonProperty("pricing_schemes") val pricingSchemes: List<PricingSchemeUpdate>
)

data class PricingSchemeUpdate(
    @JsonProperty("billing_cycle_sequence") val billingCycleSequence: Int,
    @JsonProperty("pricing_scheme") val pricingScheme: PricingScheme
)

data class PricingScheme(
    @JsonProperty("fixed_price") val fixedPrice: FixedPrice
)

data class FixedPrice(
    val value: String,
    @JsonProperty("currency_code") val currencyCode: String
)
