package com.archguard.core

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** What an architecture module's path contains: its tests may state the rules in code. */
internal const val ARCHITECTURE = "architecture"

/** The files holding instructions a coding agent follows, wherever in the project they sit. */
private val AGENT_FILES = setOf("CLAUDE.md", "AGENTS.md")

/** The directory whose Markdown documents the project, at any depth below it. */
private const val DOCS_PREFIX = "docs/"

private const val MARKDOWN = ".md"

/** A detekt configuration file: `detekt-config.yml`, `detekt.yaml`. Baselines hold no rules. */
private val DETEKT_CONFIG = Regex("""detekt.*\.ya?ml""", RegexOption.IGNORE_CASE)

/** A Konsist or ArchUnit file: both libraries state architecture rules as test code. */
private val ARCHITECTURE_TEST = Regex("""konsist|archunit""", RegexOption.IGNORE_CASE)

/** Directories holding no rule source, and costly to walk through: build output and tool state. */
private val SKIPPED_DIRS = setOf("build", "node_modules")

/**
 * Where [projectDir] may already state its architecture rules, [modules] included: one walk over
 * the project tree, skipping build output and hidden directories, sorted by kind then path.
 *
 * The guard only points at them; reading and interpreting them is the agent's job (ADR 0002).
 */
internal fun ruleSources(projectDir: Path, modules: List<String>): List<RuleSource> {
    val sources = mutableListOf<RuleSource>()

    Files.walkFileTree(
        projectDir,
        object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes) =
                if (dir != projectDir && isSkipped(dir.fileName.toString())) {
                    FileVisitResult.SKIP_SUBTREE
                } else {
                    FileVisitResult.CONTINUE
                }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val path = projectDir.relativize(file).joinToString("/")
                kindOf(path)?.let { sources += RuleSource(path, it) }
                return FileVisitResult.CONTINUE
            }

            // A file the walk cannot read, such as a dangling symlink, states no rule either:
            // finding rule sources is not the place to fail a read-only analysis.
            override fun visitFileFailed(file: Path, failure: IOException) =
                FileVisitResult.CONTINUE
        },
    )

    for (module in modules.filter { ARCHITECTURE in it.lowercase() }) {
        sources += RuleSource(module, RuleSourceKind.ARCHITECTURE_MODULE)
    }

    return sources.sortedWith(compareBy({ it.kind }, { it.path }))
}

/** Whether a directory named [name] is walked into. */
private fun isSkipped(name: String): Boolean = name.startsWith(".") || name in SKIPPED_DIRS

/**
 * What kind of rule source the file at [path] is, relative to the project root, or null when it
 * states no architecture rules. Markdown counts in the project root and under `docs/`, where a
 * project documents itself; elsewhere it documents one module, not the architecture.
 */
private fun kindOf(path: String): RuleSourceKind? {
    val name = path.substringAfterLast('/')
    return when {
        DETEKT_CONFIG.matches(name) -> RuleSourceKind.DETEKT_CONFIG
        ARCHITECTURE_TEST.containsMatchIn(name) -> RuleSourceKind.ARCHITECTURE_TEST
        name in AGENT_FILES -> RuleSourceKind.AGENT_INSTRUCTIONS
        !name.endsWith(MARKDOWN) -> null
        '/' !in path || path.startsWith(DOCS_PREFIX) -> RuleSourceKind.DOCUMENTATION
        else -> null
    }
}
