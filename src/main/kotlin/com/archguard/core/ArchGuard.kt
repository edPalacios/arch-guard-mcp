package com.archguard.core

import java.nio.file.Files
import java.nio.file.Path

/**
 * The architecture guard: one function per MCP tool, each answering a value that already is
 * the wire format (ADR 0003). Reads build scripts statically, never runs Gradle (ADR 0001),
 * never writes files and never caches between calls.
 */
object ArchGuard {

    /**
     * The modules declared in [projectDir]'s `settings.gradle.kts`.
     *
     * Answers [ProjectNotFound] when [projectDir] is not a directory or holds no settings file.
     */
    fun listModules(projectDir: Path): ListAnswer {
        val dir = projectDir.toAbsolutePath().normalize()
        val reason = when {
            !Files.isDirectory(dir) -> "not a directory"
            !Files.isRegularFile(dir.resolve(SETTINGS_FILE)) -> "no $SETTINGS_FILE in this directory"
            else -> return readSettings(dir)
        }
        return ProjectNotFound(path = dir.toString(), reason = reason)
    }
}
