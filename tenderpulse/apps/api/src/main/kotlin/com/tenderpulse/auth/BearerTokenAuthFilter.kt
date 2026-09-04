package com.tenderpulse.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Stateless bearer-token authentication (TP-038). Not Spring Security's form-login/session
 * machinery — just this one filter: read `Authorization: Bearer <token>`, validate it via
 * [BearerTokenService] (no DB lookup, see that class), and if valid, put the subscriber's id in
 * the [org.springframework.security.core.context.SecurityContext] as the authentication
 * principal. Downstream, [SubscriberOwnershipInterceptor] compares that principal against the
 * `{subscriberId}` path variable on profile endpoints.
 *
 * A missing/invalid header is not itself an error here — it just leaves the request
 * unauthenticated, and [SecurityConfig]'s `authorizeHttpRequests` rule turns that into a 401 for
 * the specific paths that require authentication.
 */
@Component
class BearerTokenAuthFilter(private val bearerTokenService: BearerTokenService) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val header = request.getHeader("Authorization")
        if (header != null && header.startsWith("Bearer ")) {
            val rawToken = header.removePrefix("Bearer ").trim()
            val subscriberId = bearerTokenService.parse(rawToken)
            if (subscriberId != null) {
                SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
                    subscriberId,
                    null,
                    listOf(SimpleGrantedAuthority("ROLE_SUBSCRIBER"))
                )
            }
        }
        filterChain.doFilter(request, response)
    }
}
