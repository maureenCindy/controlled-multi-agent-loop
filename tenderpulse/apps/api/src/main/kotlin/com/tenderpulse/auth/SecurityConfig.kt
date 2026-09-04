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
 * `.cors { ... }` (TP-034) is wired to a separate, narrowly-scoped [CorsConfigurationSource] bean
 * ([WebsiteCorsConfig], below) that only covers the two public signup endpoints — it neither
 * grants nor widens any of the authorization rules above; a cross-origin browser request still
 * has to satisfy the same `authorizeHttpRequests` rules as every other request.
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
 * CORS wiring for the marketing site's signup flow (TP-034).
 *
 * Scoped as narrowly as the AC calls for: only the two POST endpoints the site actually calls
 * (`/api/v1/subscribers` for Free signup, `/api/v1/subscribers/pro` for PayPal-verified Pro
 * signup) get a CORS configuration at all — every other route (including the authenticated
 * per-subscriber profile endpoints, and admin/tender routes) has no CORS configuration
 * registered for it, so a browser will refuse cross-origin calls to them regardless of origin.
 *
 * The allowed origin(s) are environment-driven (`WEBSITE_ALLOWED_ORIGINS`, comma-separated), not
 * hardcoded — see `application.yml` and `.env.example`. No default origin is whitelisted, so a
 * deployment that forgets to set this env var simply has no working cross-origin signup (fails
 * closed) rather than accidentally allowing an unintended origin.
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

        val corsConfig = CorsConfiguration().apply {
            this.allowedOrigins = allowedOrigins
            allowedMethods = listOf(HttpMethod.POST.name())
            allowedHeaders = listOf("Content-Type")
            allowCredentials = false
        }

        val source = UrlBasedCorsConfigurationSource()
        // Exact paths only, no wildcard suffix, so this never accidentally widens to the
        // authenticated per-subscriber profile endpoints (e.g. subscribers/{id}/profiles).
        source.registerCorsConfiguration("/api/v1/subscribers", corsConfig)
        source.registerCorsConfiguration("/api/v1/subscribers/pro", corsConfig)
        return source
    }
}
