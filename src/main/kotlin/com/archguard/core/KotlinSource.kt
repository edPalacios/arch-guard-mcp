package com.archguard.core

/**
 * Reading Kotlin build scripts as text (ADR 0001). Enough syntax to find calls and their
 * arguments without a parser: comments, string literals, balanced parentheses and braces.
 */

/** A fragment of source, with its offset in the text it was taken from. */
internal data class Argument(val text: String, val offset: Int)

/** Returns `"x"`'s content if [argument] is exactly one string literal without templates. */
internal fun plainStringLiteral(argument: String): String? {
    if (argument.length < 2 || !argument.startsWith('"') || !argument.endsWith('"')) return null
    val content = argument.substring(1, argument.length - 1)
    return content.takeUnless { '"' in it || '$' in it || '\\' in it }
}

/** Text between the `(` that precedes [start] and its matching `)`, or null if unbalanced. */
internal fun argumentsOf(code: String, start: Int): String? = balanced(code, start, '(', ')')

/** Text between the `{` that precedes [start] and its matching `}`, or null if unbalanced. */
internal fun blockOf(code: String, start: Int): String? = balanced(code, start, '{', '}')

/** Splits on top-level commas (outside strings and nested parentheses). */
internal fun splitArguments(arguments: String): List<Argument> {
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
internal fun blankComments(source: String): String {
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
internal fun lineOf(code: String, index: Int): Int {
    var line = 1
    for (i in 0 until index) if (code[i] == '\n') line++
    return line
}

/** Text from [start] up to the [close] that matches the [open] preceding it, strings apart. */
private fun balanced(code: String, start: Int, open: Char, close: Char): String? {
    var depth = 1
    var i = start
    var inString = false
    while (i < code.length) {
        val c = code[i]
        when {
            inString && c == '\\' -> i++
            c == '"' -> inString = !inString
            !inString && c == open -> depth++
            !inString && c == close -> if (--depth == 0) return code.substring(start, i)
        }
        i++
    }
    return null
}
