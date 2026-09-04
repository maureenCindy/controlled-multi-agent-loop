package com.tenderpulse.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.util.UUID
import java.util.regex.Pattern

/**
 * Enforces "a request can only act on the subscriber tied to its authenticated token, not any
 * UUID in the path" (TP-038 AC) for `/api/v1/subscribers/{id}/profiles...`.
 *
 * Kept out of [com.tenderpulse.api.SubscriberController] deliberately: [SecurityConfig] already
 * guarantees only *authenticated* requests reach this far for these paths (an unauthenticated
 * request is rejected 401 before the servlet is even dispatched to), so by the time this
 * interceptor runs there is always a subscriber-id principal to compare against — this class's
 * only job is comparing that principal to the path, and returning 403 on mismatch.
 */
@Component
class SubscriberOwnershipInterceptor : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val matcher = PROFILE_PATH_PATTERN.matcher(request.requestURI)
        if (!matcher.matches()) return true

        val pathSubscriberId = runCatching { UUID.fromString(matcher.group(1)) }.getOrNull() ?: return true
        val principal = SecurityContextHolder.getContext().authentication?.principal as? UUID

        if (principal == null || principal != pathSubscriberId) {
            response.status = HttpServletResponse.SC_FORBIDDEN
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.writer.write(
                """{"error":"forbidden","message":"Not authorized for this subscriber"}"""
            )
            return false
        }
        return true
    }

    companion object {
        // Matches /api/v1/subscribers/{uuid}/profiles and /api/v1/subscribers/{uuid}/profiles/{profileId}.
        // Deliberately does NOT match plain /api/v1/subscribers/{uuid} (no such endpoint) or
        // /api/v1/subscribers (signup, which must stay unauthenticated).
        private val PROFILE_PATH_PATTERN: Pattern =
            Pattern.compile("^/api/v1/subscribers/([0-9a-fA-F-]{36})/profiles(?:/.*)?$")
    }
}

@Configuration
class WebMvcConfig(private val subscriberOwnershipInterceptor: SubscriberOwnershipInterceptor) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(subscriberOwnershipInterceptor)
            .addPathPatterns("/api/v1/subscribers/*/profiles/**", "/api/v1/subscribers/*/profiles")
    }
}
