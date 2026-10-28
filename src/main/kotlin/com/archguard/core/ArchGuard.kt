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
        notFound(dir)?.let { return it }
        return readSettings(dir)
    }

    /**
     * The module dependencies declared by the modules of [projectDir] that [module] selects:
     * a module path or a pattern over module paths (see [matchesModulePattern]). Without one,
     * every module of the build.
     *
     * Answers [ProjectNotFound] when [projectDir] is not a directory or holds no settings file.
     */
    fun moduleGraph(projectDir: Path, module: String? = null): GraphAnswer {
        val dir = projectDir.toAbsolutePath().normalize()
        notFound(dir)?.let { return it }
        return readModuleGraph(dir, module?.takeIf { it.isNotBlank() } ?: ALL_MODULES)
    }
}

/** Why [dir] holds no Gradle build to analyse, or null when it does. */
private fun notFound(dir: Path): ProjectNotFound? {
    val reason = when {
        !Files.isDirectory(dir) -> "not a directory"
        !Files.isRegularFile(dir.resolve(SETTINGS_FILE)) -> "no $SETTINGS_FILE in this directory"
        else -> return null
    }
    return ProjectNotFound(path = dir.toString(), reason = reason)
}
