package com.tenderpulse.auth

import com.tenderpulse.api.SubscriberController
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.util.AntPathMatcher
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import java.lang.reflect.Method
import java.util.UUID

/**
 * TP-065 drift guard, independent of [SubscriberOwnershipPaths] itself (see that object's kdoc,
 * in `SubscriberOwnershipInterceptor.kt`, for the primary fix -- both [SecurityConfig] and
 * [SubscriberOwnershipInterceptor] now read that single shared list instead of each
 * hand-maintaining their own copy).
 *
 * This test is the second, independent line of defence: it reflects over
 * [SubscriberController]'s *actual* `@...Mapping` annotations and fails if any
 * subscriber-id-scoped route (`/api/v1/subscribers/{id}/...`) is ever added that
 * [SubscriberOwnershipPaths.PROTECTED_PATH_PATTERNS] does not cover. Since it re-derives the set
 * of routes from the controller rather than from [SubscriberOwnershipPaths] itself, a future
 * change that adds a new `{id}`-scoped endpoint to the controller but forgets to update
 * [SubscriberOwnershipPaths] still gets caught here -- there's no single point of failure that
 * silently keeps both "in sync" with each other while both drift away from the controller.
 */
class SubscriberOwnershipPathCoverageTest {

    private val pathMatcher = AntPathMatcher()

    @Test
    fun `every subscriber-id-scoped route on SubscriberController is covered by PROTECTED_PATH_PATTERNS`() {
        val basePath = SubscriberController::class.java.getAnnotation(RequestMapping::class.java)!!.value.first()
        val subscriberScopedRoutes = SubscriberController::class.java.methods
            .flatMap { mappingSuffixes(it) }
            .filter { it.startsWith("/{") } // e.g. "/{id}/profiles" -- scoped to a path-variable subscriber id
            .map { basePath + it }
            .distinct()

        assertTrue(
            subscriberScopedRoutes.isNotEmpty(),
            "sanity check failed: expected at least one subscriber-id-scoped route on SubscriberController " +
                "(if this legitimately becomes zero, this test -- and arguably SubscriberOwnershipPaths -- " +
                "can be deleted)"
        )

        val sampleId = UUID.randomUUID().toString()
        for (route in subscriberScopedRoutes) {
            val concretePath = route.replace(Regex("\\{[^}]+}"), sampleId)
            val covered = SubscriberOwnershipPaths.PROTECTED_PATH_PATTERNS.any { pattern ->
                pathMatcher.match(pattern, concretePath)
            }
            assertTrue(
                covered,
                "SubscriberController route '$route' is subscriber-id-scoped but is not matched by any " +
                    "pattern in SubscriberOwnershipPaths.PROTECTED_PATH_PATTERNS " +
                    "(${SubscriberOwnershipPaths.PROTECTED_PATH_PATTERNS}) -- add a matching pattern there " +
                    "(it automatically applies to both SecurityConfig and SubscriberOwnershipInterceptor), " +
                    "or if this route deliberately does not need per-subscriber ownership enforcement, " +
                    "document why and adjust this test's filter accordingly."
            )
        }
    }

    /** The path suffix(es) (relative to the class-level @RequestMapping) a method is mapped to, or empty if none. */
    private fun mappingSuffixes(method: Method): List<String> {
        method.getAnnotation(GetMapping::class.java)?.let { return it.value.ifEmpty { arrayOf("") }.toList() }
        method.getAnnotation(PostMapping::class.java)?.let { return it.value.ifEmpty { arrayOf("") }.toList() }
        method.getAnnotation(PutMapping::class.java)?.let { return it.value.ifEmpty { arrayOf("") }.toList() }
        method.getAnnotation(DeleteMapping::class.java)?.let { return it.value.ifEmpty { arrayOf("") }.toList() }
        method.getAnnotation(PatchMapping::class.java)?.let { return it.value.ifEmpty { arrayOf("") }.toList() }
        return emptyList()
    }
}
