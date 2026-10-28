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
     * With [module] — a module path or a pattern over module paths (see [matchesModulePattern])
     * — the [ModuleDependencies] the selected modules declare, which needs no config.
     *
     * Without it, the [LayerSummary] of the whole build, which needs `archguard.json`: it
     * answers [ConfigMissing] — with a config proposed from the build and the project's rule
     * sources — when the project has none, and [ConfigInvalid] when it cannot be used.
     *
     * Answers [ProjectNotFound] when [projectDir] is not a directory or holds no settings file.
     */
    fun moduleGraph(projectDir: Path, module: String? = null): GraphAnswer {
        val dir = projectDir.toAbsolutePath().normalize()
        notFound(dir)?.let { return it }
        val filter = module?.takeIf { it.isNotBlank() }
        if (filter != null) return readBuild(dir, filter).graph
        val build = readBuild(dir, ALL_MODULES)
        return when (val read = readConfig(dir, build.graph)) {
            is ConfigRead.Unusable -> read.failure
            is ConfigRead.Loaded -> layerSummary(build.graph, read.config)
        }
    }

    /**
     * The [ArchitectureReport] of [projectDir]: every module dependency its `archguard.json`
     * does not allow, and what the guard could not judge.
     *
     * Answers [ProjectNotFound] when [projectDir] is not a directory or holds no settings file,
     * [ConfigMissing] — with a config proposed from the build and the project's rule sources —
     * when the project has no config, and [ConfigInvalid] when it cannot be used.
     */
    fun checkArchitecture(projectDir: Path): CheckAnswer {
        val dir = projectDir.toAbsolutePath().normalize()
        notFound(dir)?.let { return it }
        val build = readBuild(dir, ALL_MODULES)
        return when (val read = readConfig(dir, build.graph)) {
            is ConfigRead.Unusable -> read.failure
            is ConfigRead.Loaded -> architectureReport(build, read.config)
        }
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
