package com.archguard.core

/** The pattern that matches every module of the build. */
internal const val ALL_MODULES = "**"

/**
 * Whether [modulePath] matches [pattern], the one place path patterns are interpreted (module
 * filters, and from the architecture config its layers and exclusions).
 *
 * A pattern is a module path whose segments may be wildcards: `*` matches exactly one segment,
 * `**` any number of them, none included. So `:feature:*` matches `:feature:orders` but not
 * `:feature:orders:history`, while `:feature:**` matches both and `:feature` itself.
 */
internal fun matchesModulePattern(pattern: String, modulePath: String): Boolean =
    matchesSegments(segmentsOf(pattern), segmentsOf(modulePath))

private fun segmentsOf(path: String): List<String> =
    path.removePrefix(":").split(":").filter { it.isNotEmpty() }

private fun matchesSegments(pattern: List<String>, path: List<String>): Boolean {
    if (pattern.isEmpty()) return path.isEmpty()
    val head = pattern.first()
    val rest = pattern.drop(1)
    return when (head) {
        // Try the shortest match first, then let `**` swallow one more segment.
        "**" -> matchesSegments(rest, path) ||
            (path.isNotEmpty() && matchesSegments(pattern, path.drop(1)))

        "*" -> path.isNotEmpty() && matchesSegments(rest, path.drop(1))
        else -> path.isNotEmpty() && head == path.first() && matchesSegments(rest, path.drop(1))
    }
}
