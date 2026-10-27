package com.archguard.core

import java.nio.file.Files
import java.nio.file.Path

internal const val SETTINGS_FILE = "settings.gradle.kts"

/**
 * An `include(` call. The optional `fun ` prefix tells a call apart from the declaration of a
 * project-defined `include` (the reference project wraps `settings.include` to support selective-include mode).
 */
private val INCLUDE_CALL = Regex("""\b(fun\s+)?include\s*\(""")
private val ROOT_PROJECT_NAME = Regex("""rootProject\.name\s*=\s*"([^"$]*)"""")

/** An argument of a call, with its offset in the source. */
private data class Argument(val text: String, val offset: Int)

/**
 * Reads [projectDir]'s settings file in one pass.
 *
 * Supported: `include(":a")`, `include(":a", ":b")` spread over several lines, repeated and
 * conditional `include` calls, line and block comments. `includeBuild(...)` is ignored on
 * purpose: composite builds are separate builds, not modules of this one.
 */
internal fun readSettings(projectDir: Path): ModuleList {
    val code = blankComments(Files.readString(projectDir.resolve(SETTINGS_FILE)))
    val modules = mutableSetOf<String>()
    val unresolved = mutableListOf<UnresolvedDeclaration>()

    for (call in INCLUDE_CALL.findAll(code)) {
        if (call.groupValues[1].isNotEmpty()) continue // a declaration, not a call
        val argumentsStart = call.range.last + 1
        val arguments = argumentsOf(code, argumentsStart) ?: continue
        for (argument in splitArguments(arguments)) {
            val path = plainStringLiteral(argument.text)
            if (path != null && path.isNotBlank()) {
                modules += if (path.startsWith(":")) path else ":$path"
            } else {
                unresolved += UnresolvedDeclaration(
                    declaration = argument.text,
                    file = SETTINGS_FILE,
                    line = lineOf(code, argumentsStart + argument.offset),
                )
            }
        }
    }

    return ModuleList(
        rootProjectName = ROOT_PROJECT_NAME.find(code)?.groupValues?.get(1),
        modules = modules.sorted(),
        unresolved = unresolved,
    )
}

/** Returns `"x"`'s content if [argument] is exactly one string literal without templates. */
private fun plainStringLiteral(argument: String): String? {
    if (argument.length < 2 || !argument.startsWith('"') || !argument.endsWith('"')) return null
    val content = argument.substring(1, argument.length - 1)
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
private fun splitArguments(arguments: String): List<Argument> {
    val result = mutableListOf<Argument>()
    val current = StringBuilder()
    var start = 0
    var depth = 0
    var inString = false
    var i = 0

    fun take(end: Int) {
        val text = current.toString()
        val leading = text.length - text.trimStart().length
        if (text.isNotBlank()) result += Argument(text.trim(), start + leading)
        current.clear()
        start = end
    }

    while (i < arguments.length) {
        val c = arguments[i]
        if (inString && c == '\\' && i + 1 < arguments.length) {
            current.append(c).append(arguments[++i])
        } else {
            when {
                c == '"' -> inString = !inString
                !inString && c == '(' -> depth++
                !inString && c == ')' -> depth--
            }
            if (!inString && depth == 0 && c == ',') {
                take(i + 1)
            } else {
                current.append(c)
            }
        }
        i++
    }
    take(i)
    return result
}

/**
 * Replaces comments with spaces, keeping every other character at its offset so lines stay
 * exact. String literals are respected, so `maven("https://...")` is not truncated.
 */
private fun blankComments(source: String): String {
    val out = source.toCharArray()
    var i = 0
    var inString = false
    while (i < source.length) {
        val c = source[i]
        val next = source.getOrNull(i + 1)
        when {
            inString -> if (c == '\\' && next != null) i++ else if (c == '"') inString = false
            c == '"' -> inString = true
            c == '/' && next == '/' -> while (i < source.length && source[i] != '\n') out[i++] = ' '
            c == '/' && next == '*' -> {
                val end = source.indexOf("*/", i + 2)
                val last = if (end == -1) source.length else end + 2
                while (i < last) {
                    if (out[i] != '\n') out[i] = ' '
                    i++
                }
                continue
            }
        }
        i++
    }
    return String(out)
}

/** 1-based line of [index] in [code]. */
private fun lineOf(code: String, index: Int): Int {
    var line = 1
    for (i in 0 until index) if (code[i] == '\n') line++
    return line
}
