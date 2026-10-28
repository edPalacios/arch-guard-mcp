package com.archguard.core

import java.nio.file.Files
import java.nio.file.Path

internal const val BUILD_FILE = "build.gradle.kts"

/** The configurations a module dependency is declared in; test ones are out of scope. */
private val PRODUCTION_CONFIGURATIONS = setOf("implementation", "api", "compileOnly")

/** A `dependencies {` block, not a member call such as `project.dependencies {`. */
private val DEPENDENCIES_BLOCK = Regex("""(?<![.\w])dependencies\s*\{""")

/** A call inside that block: the configuration or the dependency helper being invoked. */
private val CALL = Regex("""(?<![.\w])([A-Za-z_]\w*)\s*\(""")

/** A `project(` call wrapping the module path. */
private val PROJECT_CALL = Regex("""^project\s*\(""")

/** A type-safe project accessor: `projects.core.ui`. */
private val PROJECT_ACCESSOR = Regex("""projects(\.\w+)+""")

/** Where a module path turns into a type-safe accessor segment: `shared-component`. */
private val SEGMENT_SEPARATOR = Regex("""[^A-Za-z0-9]+""")

/** One module's build script, read in one pass. */
private data class ModuleScript(
    val detail: ModuleDetail,
    val helpers: Map<String, Int>,
)

/**
 * The modules the settings file includes, by the two names a build script can reach them by, so
 * a dependency on anything else is recognised as dangling.
 *
 * @property paths every included module path.
 * @property byAccessor the module path each type-safe project accessor stands for.
 */
private data class Included(
    val paths: Set<String>,
    val byAccessor: Map<String, String>,
)

/**
 * What one pass over [projectDir]'s build found.
 *
 * @property graph the module dependencies of the modules the filter selected.
 * @property unresolvedInclusions the settings file's module inclusions that need Gradle to be
 *   resolved. They belong to no module's build script, so they travel beside [graph] instead of
 *   inside one of its [ModuleDetail]s.
 */
internal data class BuildGraph(
    val graph: ModuleDependencies,
    val unresolvedInclusions: List<UnresolvedDeclaration>,
)

/**
 * Reads [projectDir]'s settings file and the build scripts of the modules matching [filter].
 *
 * Only the matching modules' build scripts are read, so a filter on one module stays cheap.
 */
internal fun readBuild(projectDir: Path, filter: String): BuildGraph {
    val settings = readSettings(projectDir)
    val modules = settings.modules
    val included = Included(paths = modules.toSet(), byAccessor = modules.associateBy(::accessorOf))
    val scripts = modules
        .filter { matchesModulePattern(filter, it) }
        .map { readModuleScript(projectDir, it, included) }

    val helpers = sortedMapOf<String, Int>()
    for (script in scripts) {
        for ((helper, calls) in script.helpers) helpers.merge(helper, calls, Int::plus)
    }

    return BuildGraph(
        graph = ModuleDependencies(
            filter = filter,
            modules = scripts.map { it.detail },
            unanalysedHelpers = helpers,
            warnings = if (scripts.isEmpty()) {
                listOf("no module matches '$filter'")
            } else {
                emptyList()
            },
        ),
        unresolvedInclusions = settings.unresolved,
    )
}

/**
 * Reads [module]'s `build.gradle.kts`: the module dependencies of its `dependencies` blocks —
 * the ones pointing outside [included] marked as dangling —, the declarations that need Gradle
 * to be resolved (ADR 0001), and how often each dependency helper is called. A call that is not
 * a production configuration and takes `project` as an argument is such a helper: it is counted,
 * never resolved and reported nowhere else.
 */
private fun readModuleScript(
    projectDir: Path,
    module: String,
    included: Included,
): ModuleScript {
    val buildFile = module.removePrefix(":").replace(':', '/') + "/$BUILD_FILE"
    val file = projectDir.resolve(buildFile)
    if (!Files.isRegularFile(file)) {
        return ModuleScript(ModuleDetail(module, null, emptyList(), emptyList()), emptyMap())
    }

    val code = blankComments(Files.readString(file))
    val dependencies = mutableListOf<ModuleDependency>()
    val unresolved = mutableListOf<UnresolvedDeclaration>()
    val helpers = mutableMapOf<String, Int>()

    for (block in DEPENDENCIES_BLOCK.findAll(code)) {
        val bodyStart = block.range.last + 1
        val body = blockOf(code, bodyStart) ?: continue
        for (call in CALL.findAll(body)) {
            val configuration = call.groupValues[1]
            val argumentsStart = call.range.last + 1
            val arguments = splitArguments(argumentsOf(body, argumentsStart) ?: continue)
            fun lineAt(offset: Int) = lineOf(code, bodyStart + argumentsStart + offset)

            if (configuration !in PRODUCTION_CONFIGURATIONS) {
                if (arguments.any { it.text == "project" }) {
                    helpers[configuration] = (helpers[configuration] ?: 0) + 1
                }
                continue
            }

            for (argument in arguments) {
                val named = projectArgument(argument) ?: continue // not a module dependency
                val target = targetModule(named.text, included)
                if (target != null) {
                    dependencies += ModuleDependency(
                        target = target,
                        configuration = configuration,
                        file = buildFile,
                        line = lineAt(argument.offset),
                        dangling = target !in included.paths,
                    )
                } else {
                    unresolved += UnresolvedDeclaration(
                        declaration = named.text,
                        file = buildFile,
                        line = lineAt(argument.offset + named.offset),
                    )
                }
            }
        }
    }

    return ModuleScript(
        detail = ModuleDetail(
            path = module,
            buildFile = buildFile,
            dependencies = dependencies
                .distinctBy { it.target to it.configuration }
                .sortedWith(compareBy({ it.target }, { it.configuration })),
            unresolved = unresolved,
        ),
        helpers = helpers,
    )
}

/**
 * The module [named] identifies, or null when Gradle would be needed to resolve it. An accessor
 * that stands for no included module names a module all the same — the one the build script
 * believes in — so it is reported as written, and the caller finds it dangling.
 */
private fun targetModule(named: String, included: Included): String? =
    if (PROJECT_ACCESSOR.matches(named)) {
        included.byAccessor[named] ?: named
    } else {
        plainStringLiteral(named)?.takeIf { it.isNotBlank() }?.let(::modulePath)
    }

/**
 * The part of [argument] that names a module: `project(...)`'s own argument, or the accessor
 * itself. Null when the argument names something else, such as a library.
 */
private fun projectArgument(argument: Argument): Argument? {
    if (PROJECT_ACCESSOR.matches(argument.text)) return Argument(argument.text, 0)
    val call = PROJECT_CALL.find(argument.text) ?: return null
    val start = call.range.last + 1
    val inside = argumentsOf(argument.text, start) ?: return null
    return Argument(inside.trim(), start + (inside.length - inside.trimStart().length))
}

/**
 * The type-safe accessor Gradle generates for [module]: `:shared-component:cart` is reached
 * as `projects.sharedComponent.cart`.
 */
private fun accessorOf(module: String): String =
    module.removePrefix(":").split(":").joinToString(".", prefix = "projects.") { segment ->
        segment.split(SEGMENT_SEPARATOR).mapIndexed { i, part ->
            if (i == 0) part else part.replaceFirstChar(Char::uppercase)
        }.joinToString("")
    }
