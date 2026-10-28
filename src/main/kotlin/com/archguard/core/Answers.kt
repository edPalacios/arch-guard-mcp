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

/** What [ArchGuard.moduleGraph] answers. */
@Serializable
sealed interface GraphAnswer : Answer

/**
 * A problem of the analysed project, not of the guard: expected, reported as a value, and
 * marked as an error result by the MCP layer.
 */
@Serializable
sealed interface Failure : ListAnswer, GraphAnswer

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
 * The module dependencies of the modules a filter selected.
 *
 * @property filter the module path or pattern the dependencies were asked for.
 * @property modules one entry per matching module, sorted by path.
 * @property unanalysedHelpers how many calls each dependency helper got, by helper name: the
 *   dependencies it declares are invisible to the guard, so the agent knows the blind spot.
 * @property warnings what the guard noticed without stopping, such as a filter matching nothing.
 */
@Serializable
@SerialName("ModuleDependencies")
data class ModuleDependencies(
    val filter: String,
    val modules: List<ModuleDetail>,
    val unanalysedHelpers: Map<String, Int>,
    val warnings: List<String>,
) : GraphAnswer

/**
 * How the modules of the analysed build sit in the layers of its architecture config.
 *
 * @property config the config the summary was computed with, so the agent can answer
 *   hypothetical questions about other layers and rules itself.
 * @property layers the modules of each layer, in config order.
 * @property matrix the layer → layer edges the build declares, in config order. Pairs with no
 *   module dependency are left out: what [config] allows but nobody declares is derivable.
 * @property unassigned modules that match no layer and are not excluded, sorted by path.
 * @property unanalysedHelpers how many calls each dependency helper got, by helper name: the
 *   dependencies it declares are invisible to the guard, so the agent knows the blind spot.
 * @property warnings what the guard noticed without stopping, such as an empty layer.
 */
@Serializable
@SerialName("LayerSummary")
data class LayerSummary(
    val config: ArchitectureConfig,
    val layers: List<LayerModules>,
    val matrix: List<LayerEdge>,
    val unassigned: List<String>,
    val unanalysedHelpers: Map<String, Int>,
    val warnings: List<String>,
) : GraphAnswer

/**
 * One layer and the modules assigned to it.
 *
 * @property layer the layer name, as the config spells it.
 * @property modules the module paths it holds, sorted.
 */
@Serializable
data class LayerModules(
    val layer: String,
    val modules: List<String>,
)

/**
 * One cell of the layer → layer matrix: how many module dependencies cross from one layer to
 * another, and whether the source layer's rule allows them.
 *
 * @property from the layer the depending modules belong to.
 * @property to the layer the modules depended on belong to.
 * @property count how many distinct module dependencies go from [from] to [to]: one per source
 *   module → target module pair, however many times the build declares it.
 * @property allowed whether [from]'s layer rule lists [to]. When false, every dependency behind
 *   [count] is a violation.
 */
@Serializable
data class LayerEdge(
    val from: String,
    val to: String,
    val count: Int,
    val allowed: Boolean,
)

/**
 * One module and what it declares in its build script.
 *
 * @property path the module path, as in `:feature:orders:history`.
 * @property buildFile path relative to the project root with `/`, or null when the module has
 *   no `build.gradle.kts`.
 * @property dependencies its module dependencies, sorted by target then configuration.
 * @property unresolved its dependency declarations that need Gradle to be resolved.
 */
@Serializable
data class ModuleDetail(
    val path: String,
    val buildFile: String?,
    val dependencies: List<ModuleDependency>,
    val unresolved: List<UnresolvedDeclaration>,
)

/**
 * A directed edge to another module of the build, declared in a production configuration.
 *
 * @property target the module depended on.
 * @property configuration the configuration it is declared in: `implementation`, `api` or
 *   `compileOnly`.
 * @property file path relative to the project root, with `/`.
 * @property line 1-based line of the declaration in [file]; the first one when the same edge
 *   is declared more than once.
 */
@Serializable
data class ModuleDependency(
    val target: String,
    val configuration: String,
    val file: String,
    val line: Int,
)

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
 * The architecture config exists but cannot be used.
 *
 * @property file the config's path relative to the project root.
 * @property problems what is wrong with it, in the order they were found.
 * @property expectedFormat the format the config is expected to follow.
 */
@Serializable
@SerialName("ConfigInvalid")
data class ConfigInvalid(
    val file: String,
    val problems: List<String>,
    val expectedFormat: String,
) : Failure

/**
 * The analysed project has no architecture config, so the guard has no layers to judge it by.
 *
 * @property file where the config is expected, relative to the project root.
 * @property expectedFormat the format the agent writes it in.
 * @property proposedConfig a config inferred from the build, for the agent to review and write.
 *   Never applied by the guard itself.
 */
@Serializable
@SerialName("ConfigMissing")
data class ConfigMissing(
    val file: String,
    val expectedFormat: String,
    val proposedConfig: ArchitectureConfig,
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
