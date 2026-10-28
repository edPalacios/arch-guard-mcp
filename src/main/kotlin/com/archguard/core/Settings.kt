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
                modules += modulePath(path)
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

/** A module path as the guard reports it: always rooted, as in `:feature:profile`. */
internal fun modulePath(path: String): String = if (path.startsWith(":")) path else ":$path"
