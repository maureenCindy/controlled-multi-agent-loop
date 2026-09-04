package com.tenderpulse.auth

import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

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
 * listings, static resources like `/privacy.html`) stays open — protecting those is out of scope.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val bearerTokenAuthFilter: BearerTokenAuthFilter,
    private val adminKeyAuthFilter: AdminKeyAuthFilter
) {

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { authorize ->
                authorize
                    .requestMatchers("/api/v1/admin/**").hasAuthority(AdminKeyAuthFilter.ADMIN_AUTHORITY)
                    .requestMatchers("/api/v1/subscribers/*/profiles/**", "/api/v1/subscribers/*/profiles")
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
