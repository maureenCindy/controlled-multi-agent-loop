package com.tenderpulse.auth

import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/**
 * Minimal, stateless Spring Security wiring for TP-038 (subscriber auth) and TP-044 (admin auth)
 * — deliberately NOT full form-login, session, or RBAC.
 *
 * Two independent, stateless header-based filters authenticate; neither trusts the other's
 * header, and each only grants its own authority:
 * - [BearerTokenAuthFilter] validates `Authorization: Bearer <token>` and grants
 *   `ROLE_SUBSCRIBER`. The only authorization rule for it here is "the subscriber profile
 *   endpoints require *some* authenticated caller" — the finer-grained "must be *that*
 *   subscriber's own token" check lives in [SubscriberOwnershipInterceptor], since it needs the
 *   `{id}` path variable, not just the URL pattern.
 * - [AdminKeyAuthFilter] validates `X-Admin-Key` and grants [AdminKeyAuthFilter.ADMIN_AUTHORITY].
 *   Every admin route requires that specific authority (`hasAuthority`, not just
 *   `authenticated()`) — a valid subscriber bearer token must NOT be sufficient to reach admin
 *   routes, since a bearer token would otherwise satisfy a bare "authenticated" check here too.
 *
 * Everything else (`POST /api/v1/subscribers` signup, everything under `/api/v1/auth`, tender
 * listings) stays open — protecting those is out of scope.
 *
 * `.cors { ... }` (TP-034, extended by TP-134) is wired to a separate, narrowly-scoped
 * [CorsConfigurationSource] bean ([WebsiteCorsConfig], below) that only covers an explicit,
 * exact-path list of routes the marketing site actually calls cross-origin — it neither grants
 * nor widens any of the authorization rules above; a cross-origin browser request still has to
 * satisfy the same `authorizeHttpRequests` rules as every other request (CORS only governs
 * whether the browser is allowed to *attempt* the cross-origin call at all).
 */
@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val bearerTokenAuthFilter: BearerTokenAuthFilter,
    private val adminKeyAuthFilter: AdminKeyAuthFilter,
    private val corsConfigurationSource: CorsConfigurationSource
) {

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .cors { it.configurationSource(corsConfigurationSource) }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { authorize ->
                authorize
                    .requestMatchers("/api/v1/admin/**").hasAuthority(AdminKeyAuthFilter.ADMIN_AUTHORITY)
                    // TP-065: shared with SubscriberOwnershipInterceptor via SubscriberOwnershipPaths
                    // so the two path-pattern lists can't silently drift apart -- see that object's
                    // kdoc (in SubscriberOwnershipInterceptor.kt) for why.
                    .requestMatchers(*SubscriberOwnershipPaths.PROTECTED_PATH_PATTERNS.toTypedArray())
                    .authenticated()
                    // TP-127 (issue #127): POST /api/v1/billing/paypal/subscriptions/confirm requires an
                    // authenticated subscriber bearer token, same as the ownership-scoped paths above, but
                    // has no `{id}` path variable to compare against -- it acts on the authenticated
                    // caller's own record (see com.tenderpulse.billing.BillingController), so it's a plain
                    // `authenticated()` matcher here rather than being added to SubscriberOwnershipPaths
                    // (whose PROTECTED_PATH_PATTERNS -- and SubscriberOwnershipPathCoverageTest -- are
                    // specifically about /api/v1/subscribers/{id}/... routes on SubscriberController).
                    // GET /api/v1/billing/public-config is intentionally NOT listed here -- it's
                    // display/bootstrap-only and public, and falls through to permitAll below.
                    .requestMatchers(HttpMethod.POST, "/api/v1/billing/paypal/subscriptions/confirm")
                    .authenticated()
                    .anyRequest().permitAll()
            }
            .exceptionHandling { handling ->
                handling
                    .authenticationEntryPoint { _, response, _ -> writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, "unauthorized", "Authentication required") }
                    .accessDeniedHandler { _, response, _ -> writeJsonError(response, HttpServletResponse.SC_FORBIDDEN, "forbidden", "Not authorized for this resource") }
            }
            .addFilterBefore(bearerTokenAuthFilter, UsernamePasswordAuthenticationFilter::class.java)
            .addFilterBefore(adminKeyAuthFilter, UsernamePasswordAuthenticationFilter::class.java)

        return http.build()
    }

    private fun writeJsonError(response: HttpServletResponse, status: Int, error: String, message: String) {
        response.status = status
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.writer.write("""{"error":"$error","message":"$message"}""")
    }
}

/**
 * CORS wiring for the marketing site (TP-034, extended by TP-134/#134 for the Max checkout flow).
 *
 * Scoped as narrowly as the AC calls for: only the exact routes the site actually calls cross-
 * origin get a CORS configuration at all — every other route (including the authenticated
 * per-subscriber profile endpoints, and admin/tender routes) has no CORS configuration
 * registered for it, so a browser will refuse cross-origin calls to them regardless of origin.
 * Registration is by **exact path, no wildcard suffix**, so this never accidentally widens to the
 * authenticated per-subscriber profile endpoints (e.g. `subscribers/{id}/profiles`).
 *
 * TP-134 needed to add 4 more routes for the Max checkout flow (`billing/public-config`,
 * `billing/paypal/subscriptions/confirm`, `auth/magic-link`, `auth/verify`), which between them
 * need `GET` (2 of the 4) and `Authorization` (1 of the 4) — neither of which the original
 * POST+Content-Type-only [CorsConfiguration] supported. Rather than widen that one shared config
 * (which would also have started technically accepting a GET *preflight* — not an actual GET
 * response, since [UrlBasedCorsConfigurationSource] plus a per-path [CorsConfiguration] governs
 * only whether a preflight/actual cross-origin request is allowed through, never what HTTP
 * methods a controller itself exposes at that path — for the two original POST-only routes),
 * three separate [CorsConfiguration] instances are used, one per distinct method+header shape
 * actually required, and each unchanged/new route is registered against whichever shape it needs:
 *
 * - [postOnlyCorsConfig] (POST, `Content-Type` only) — the original config, byte-for-byte
 *   unchanged, still backing `/api/v1/subscribers` and `/api/v1/subscribers/pro`.
 *   `/api/v1/auth/magic-link` needs exactly this same shape (POST with a JSON body, no auth), so
 *   it's registered against this same config rather than a new one — this is reuse, not
 *   widening: the config object itself, and what it grants, is identical to what already shipped.
 * - [publicGetCorsConfig] (GET only) — new, for the two public, unauthenticated GET-only routes
 *   (`/api/v1/billing/public-config`, `/api/v1/auth/verify`). Neither needs `Authorization` (both
 *   are callable before the browser holds a token at all), so this config deliberately omits it.
 * - [authenticatedConfirmCorsConfig] (POST + `Authorization`) — new, scoped to exactly
 *   `/api/v1/billing/paypal/subscriptions/confirm`, the only one of the 4 new routes that needs
 *   the browser to send a bearer token cross-origin. Kept on its own config (rather than folded
 *   into [postOnlyCorsConfig]) specifically so `Authorization` is never granted to the two
 *   original signup routes, which don't need it.
 *
 * The allowed origin(s) are environment-driven (`WEBSITE_ALLOWED_ORIGINS`, comma-separated), not
 * hardcoded — see `application.yml` and `.env.example`. No default origin is whitelisted, so a
 * deployment that forgets to set this env var simply has no working cross-origin signup (fails
 * closed) rather than accidentally allowing an unintended origin. All three configs above share
 * the same resolved `allowedOrigins` list.
 */
@Configuration
class WebsiteCorsConfig {

    @Bean
    fun corsConfigurationSource(
        @Value("\${tenderpulse.website.allowed-origins:}") allowedOriginsCsv: String
    ): CorsConfigurationSource {
        val allowedOrigins = allowedOriginsCsv.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val postOnlyCorsConfig = CorsConfiguration().apply {
            this.allowedOrigins = allowedOrigins
            allowedMethods = listOf(HttpMethod.POST.name())
            allowedHeaders = listOf("Content-Type")
            allowCredentials = false
        }

        val publicGetCorsConfig = CorsConfiguration().apply {
            this.allowedOrigins = allowedOrigins
            allowedMethods = listOf(HttpMethod.GET.name())
            allowedHeaders = listOf("Content-Type")
            allowCredentials = false
        }

        val authenticatedConfirmCorsConfig = CorsConfiguration().apply {
            this.allowedOrigins = allowedOrigins
            allowedMethods = listOf(HttpMethod.POST.name())
            allowedHeaders = listOf("Content-Type", "Authorization")
            allowCredentials = false
        }

        val source = UrlBasedCorsConfigurationSource()
        // Exact paths only, no wildcard suffix, so this never accidentally widens to the
        // authenticated per-subscriber profile endpoints (e.g. subscribers/{id}/profiles).
        source.registerCorsConfiguration("/api/v1/subscribers", postOnlyCorsConfig)
        source.registerCorsConfiguration("/api/v1/subscribers/pro", postOnlyCorsConfig)
        source.registerCorsConfiguration("/api/v1/auth/magic-link", postOnlyCorsConfig)
        source.registerCorsConfiguration("/api/v1/billing/public-config", publicGetCorsConfig)
        source.registerCorsConfiguration("/api/v1/auth/verify", publicGetCorsConfig)
        source.registerCorsConfiguration(
            "/api/v1/billing/paypal/subscriptions/confirm",
            authenticatedConfirmCorsConfig
        )
        return source
    }
}
