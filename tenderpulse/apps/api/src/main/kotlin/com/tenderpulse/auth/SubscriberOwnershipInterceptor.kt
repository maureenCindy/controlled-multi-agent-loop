package com.tenderpulse.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.util.UUID

/**
 * Enforces "a request can only act on the subscriber tied to its authenticated token, not any
 * UUID in the path" (TP-038 AC) for `/api/v1/subscribers/{id}/profiles...`.
 *
 * Deliberately does NOT re-parse [HttpServletRequest.getRequestURI] itself: an earlier version
 * matched a hand-rolled regex against the *raw* (possibly percent-encoded) URI, which a
 * reviewer showed could be bypassed by encoding a single character of `profiles` (e.g.
 * `prof%69les`) — the raw string didn't match the guard's own regex, so it returned "not
 * guarded", while Spring's request-mapping machinery decoded the path and dispatched to
 * [com.tenderpulse.api.SubscriberController] anyway, completely skipping the ownership check.
 * Instead this reads the `id` path variable from the same, already-decoded
 * [HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE] map that Spring's own handler mapping
 * resolved *before* invoking this interceptor — i.e. the same source of truth the controller
 * itself will use — so there is no separate parse of the URI to disagree with it. Path scoping
 * (which requests reach this interceptor at all) is still handled by
 * [WebMvcConfig.addInterceptors]'s `addPathPatterns`, which — unlike the removed regex — is
 * Spring's own decoded-path matcher.
 *
 * Kept out of [com.tenderpulse.api.SubscriberController] deliberately: [SecurityConfig] already
 * guarantees only *authenticated* requests reach this far for these paths (an unauthenticated
 * request is rejected 401 before the servlet is even dispatched to), so by the time this
 * interceptor runs there is always a subscriber-id principal to compare against — this class's
 * only job is comparing that principal to the path, and returning 403 on mismatch.
 */
@Component
class SubscriberOwnershipInterceptor : HandlerInterceptor {

    @Suppress("UNCHECKED_CAST")
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val pathVariables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) as? Map<String, String>
        val rawSubscriberId = pathVariables?.get(SUBSCRIBER_ID_PATH_VARIABLE) ?: return true

        val pathSubscriberId = runCatching { UUID.fromString(rawSubscriberId) }.getOrNull() ?: return true
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
        // Matches SubscriberController's `@PathVariable id: UUID` on the profile endpoints.
        private const val SUBSCRIBER_ID_PATH_VARIABLE = "id"
    }
}

@Configuration
class WebMvcConfig(private val subscriberOwnershipInterceptor: SubscriberOwnershipInterceptor) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(subscriberOwnershipInterceptor)
            .addPathPatterns("/api/v1/subscribers/*/profiles/**", "/api/v1/subscribers/*/profiles")
    }
}
