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
 * Minimal, stateless Spring Security wiring for TP-038 — deliberately NOT full form-login,
 * session, or RBAC. Only [BearerTokenAuthFilter] authenticates; the only authorization rule
 * enforced here is "the subscriber profile endpoints require *some* authenticated caller" — the
 * finer-grained "must be *that* subscriber's own token" check lives in
 * [SubscriberOwnershipInterceptor], since it needs the `{id}` path variable, not just the URL
 * pattern.
 *
 * Everything else (`POST /api/v1/subscribers` signup, everything under `/api/v1/auth`, tender
 * listings, the admin aggregate trigger, static resources like `/privacy.html`) stays open —
 * protecting those is out of scope for this issue (admin auth is tracked separately).
 */
@Configuration
@EnableWebSecurity
class SecurityConfig(private val bearerTokenAuthFilter: BearerTokenAuthFilter) {

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { authorize ->
                authorize
                    .requestMatchers("/api/v1/subscribers/*/profiles/**", "/api/v1/subscribers/*/profiles")
                    .authenticated()
                    .anyRequest().permitAll()
            }
            .exceptionHandling { handling ->
                handling
                    .authenticationEntryPoint { _, response, _ -> writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, "unauthorized", "Authentication required") }
                    .accessDeniedHandler { _, response, _ -> writeJsonError(response, HttpServletResponse.SC_FORBIDDEN, "forbidden", "Not authorized for this subscriber") }
            }
            .addFilterBefore(bearerTokenAuthFilter, UsernamePasswordAuthenticationFilter::class.java)

        return http.build()
    }

    private fun writeJsonError(response: HttpServletResponse, status: Int, error: String, message: String) {
        response.status = status
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.writer.write("""{"error":"$error","message":"$message"}""")
    }
}
