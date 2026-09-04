package com.tenderpulse.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest

/**
 * Stateless shared-secret authentication for every route under `/api/v1/admin` (TP-044):
 * compares the `X-Admin-Key` request header against `tenderpulse.admin.key` (env
 * `TENDERPULSE_ADMIN_KEY`, never committed — see `application.yml` / `.env.example`).
 *
 * Mirrors [BearerTokenAuthFilter]'s shape — a missing or wrong header just leaves the request
 * unauthenticated, and [SecurityConfig]'s `authorizeHttpRequests` turns that into a 401/403 for
 * the specific paths that require it — but grants [ADMIN_AUTHORITY] rather than
 * `ROLE_SUBSCRIBER`, and is intentionally a *single* shared secret for one operator (see issue
 * #44's "single operator" assumption), not a per-user credential or IAM integration (that was
 * considered and explicitly reverted for this issue — see the issue comments).
 *
 * There is deliberately no insecure-but-functional fallback value the way
 * `tenderpulse.auth.token-secret` has one (see [InsecureDefaultSecretGuard]): if
 * `TENDERPULSE_ADMIN_KEY` is never set, [configuredKey] is blank, [matches] always returns
 * false, and the admin API is simply unusable (fails closed) — there is no insecure-but-working
 * default to warn about or refuse to boot over.
 *
 * Uses a constant-time comparison ([MessageDigest.isEqual], the same primitive
 * [BearerTokenService] uses for its signature check) so response timing can't be used to guess
 * the key one byte at a time.
 */
@Component
class AdminKeyAuthFilter(
    @Value("\${tenderpulse.admin.key:}") private val configuredKey: String
) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val providedKey = request.getHeader(ADMIN_KEY_HEADER)
        if (providedKey != null && matches(providedKey)) {
            SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
                "admin",
                null,
                listOf(SimpleGrantedAuthority(ADMIN_AUTHORITY))
            )
        }
        filterChain.doFilter(request, response)
    }

    private fun matches(providedKey: String): Boolean {
        if (configuredKey.isEmpty()) return false
        return MessageDigest.isEqual(
            configuredKey.toByteArray(Charsets.UTF_8),
            providedKey.toByteArray(Charsets.UTF_8)
        )
    }

    companion object {
        const val ADMIN_KEY_HEADER = "X-Admin-Key"
        const val ADMIN_AUTHORITY = "ROLE_ADMIN"
    }
}
