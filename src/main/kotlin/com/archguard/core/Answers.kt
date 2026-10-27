package com.archguard.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a tool answers. Serialised with a `"type"` field naming the variant, so the calling
 * agent branches on it instead of on transport-level errors (ADR 0003).
 */
@Serializable
sealed interface Answer

/** What [ArchGuard.listModules] answers. */
@Serializable
sealed interface ListAnswer : Answer

/**
 * A problem of the analysed project, not of the guard: expected, reported as a value, and
 * marked as an error result by the MCP layer.
 */
@Serializable
sealed interface Failure : ListAnswer

/**
 * The modules of the analysed build.
 *
 * @property rootProjectName `rootProject.name`, or null when it is not a plain string literal.
 * @property modules module paths, deduplicated and sorted.
 * @property unresolved module inclusions that need Gradle to be resolved.
 */
@Serializable
@SerialName("ModuleList")
data class ModuleList(
    val rootProjectName: String?,
    val modules: List<String>,
    val unresolved: List<UnresolvedDeclaration>,
) : ListAnswer

/**
 * There is no Gradle project to analyse at the given path.
 *
 * @property path the path the caller asked about.
 * @property reason why nothing could be read there.
 */
@Serializable
@SerialName("ProjectNotFound")
data class ProjectNotFound(
    val path: String,
    val reason: String,
) : Failure

/**
 * A module inclusion or dependency the guard found but could not resolve without running
 * Gradle (ADR 0001): string templates, variables, loops.
 *
 * @property declaration the unresolved argument, as written in the source.
 * @property file path relative to the project root, with `/`.
 * @property line 1-based line of the declaration in [file].
 */
@Serializable
data class UnresolvedDeclaration(
    val declaration: String,
    val file: String,
    val line: Int,
)
