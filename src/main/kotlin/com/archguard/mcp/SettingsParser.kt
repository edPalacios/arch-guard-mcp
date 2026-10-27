package com.archguard.mcp

/**
 * Result of statically reading a `settings.gradle.kts`.
 *
 * @property rootProjectName value of `rootProject.name`, if it is a plain string literal.
 * @property modules Gradle project paths in declaration order, normalised to start with `:`.
 * @property unresolved `include(...)` arguments that are not plain string literals
 *   (string templates, variables, loops...). They can't be resolved without running Gradle,
 *   so they are reported instead of silently dropped.
 */
data class SettingsInfo(
    val rootProjectName: String?,
    val modules: List<String>,
    val unresolved: List<String>,
)

/**
 * Static (no Gradle execution) parser for `settings.gradle.kts`.
 *
 * Supported: `include(":a")`, `include(":a", ":b")` spread over several lines, repeated
 * `include` calls, line and block comments. `includeBuild(...)` is ignored on purpose:
 * composite builds are separate builds, not modules of this one.
 */
object SettingsParser {

    private val includeCall = Regex("""\binclude\s*\(""")
    private val rootProjectName = Regex("""rootProject\.name\s*=\s*"([^"$]*)"""")

    fun parse(source: String): SettingsInfo {
        val code = stripComments(source)
        val modules = linkedSetOf<String>()
        val unresolved = mutableListOf<String>()

        for (match in includeCall.findAll(code)) {
            val args = argumentsOf(code, match.range.last + 1) ?: continue
            for (arg in splitArguments(args)) {
                val literal = plainStringLiteral(arg)
                if (literal != null && literal.isNotBlank()) {
                    modules += normalise(literal)
                } else if (arg.isNotBlank()) {
                    unresolved += arg
                }
            }
        }

        return SettingsInfo(
            rootProjectName = rootProjectName.find(code)?.groupValues?.get(1),
            modules = modules.toList(),
            unresolved = unresolved,
        )
    }

    private fun normalise(path: String): String = if (path.startsWith(":")) path else ":$path"

    /** Returns `"x"`'s content if [arg] is exactly one string literal without templates. */
    private fun plainStringLiteral(arg: String): String? {
        if (arg.length < 2 || !arg.startsWith('"') || !arg.endsWith('"')) return null
        val content = arg.substring(1, arg.length - 1)
        return content.takeUnless { '"' in it || '$' in it || '\\' in it }
    }

    /** Text between the `(` that precedes [start] and its matching `)`, or null if unbalanced. */
    private fun argumentsOf(code: String, start: Int): String? {
        var depth = 1
        var i = start
        var inString = false
        while (i < code.length) {
            val c = code[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                !inString && c == '(' -> depth++
                !inString && c == ')' -> if (--depth == 0) return code.substring(start, i)
            }
            i++
        }
        return null
    }

    /** Splits on top-level commas (outside strings and nested parentheses). */
    private fun splitArguments(args: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var inString = false
        var i = 0
        while (i < args.length) {
            val c = args[i]
            if (inString && c == '\\' && i + 1 < args.length) {
                current.append(c).append(args[++i])
            } else {
                when {
                    c == '"' -> inString = !inString
                    !inString && c == '(' -> depth++
                    !inString && c == ')' -> depth--
                }
                if (!inString && depth == 0 && c == ',') {
                    result += current.toString().trim()
                    current.clear()
                } else {
                    current.append(c)
                }
            }
            i++
        }
        result += current.toString().trim()
        return result.filter { it.isNotEmpty() }
    }

    /**
     * Removes `//` and `/* */` comments while respecting string literals, so URLs such as
     * `maven("https://...")` don't get truncated.
     */
    internal fun stripComments(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        var inString = false
        while (i < source.length) {
            val c = source[i]
            val next = source.getOrNull(i + 1)
            when {
                inString -> {
                    out.append(c)
                    if (c == '\\' && next != null) {
                        out.append(next); i++
                    } else if (c == '"') {
                        inString = false
                    }
                }
                c == '"' -> { inString = true; out.append(c) }
                c == '/' && next == '/' -> {
                    while (i < source.length && source[i] != '\n') i++
                    continue
                }
                c == '/' && next == '*' -> {
                    val end = source.indexOf("*/", i + 2)
                    i = if (end == -1) source.length else end + 2
                    out.append(' ')
                    continue
                }
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }
}
