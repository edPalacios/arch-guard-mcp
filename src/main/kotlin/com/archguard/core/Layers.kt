package com.archguard.core

/**
 * Putting modules in layers and judging how they depend on each other: pure functions of the
 * build already read and the architecture config.
 */

/**
 * The layer of each module of the build, in path order, null for the modules the config
 * excludes or no pattern matches (see [isExcluded] to tell those two apart). Computed once per
 * call: a module path outside this map is no module of the build, so it crosses no layers.
 */
private fun layerAssignment(
    config: ArchitectureConfig,
    graph: ModuleDependencies,
): Map<String, String?> = graph.modules.associate { it.path to layerOf(config, it.path) }

/**
 * The layer [module] belongs to, or null when the config excludes it or no pattern matches it.
 * The first matching pattern wins, so the config's order is the one that decides.
 */
private fun layerOf(config: ArchitectureConfig, module: String): String? =
    if (isExcluded(config, module)) {
        null
    } else {
        config.layers.firstOrNull { matchesModulePattern(it.pattern, module) }?.name
    }

/** Whether the config deliberately leaves [module] out of every layer. */
private fun isExcluded(config: ArchitectureConfig, module: String): Boolean =
    config.excluded.any { matchesModulePattern(it, module) }

/** The layer names [config] defines, in config order, each one once. */
private fun layerNames(config: ArchitectureConfig): List<String> =
    config.layers.map { it.name }.distinct()

/**
 * The layer rule [config] governs layer [layer] by, or null when it gives it none. A config
 * with two rules for one layer never reaches here: [readConfig] answers [ConfigInvalid], so the
 * first matching rule is the only one.
 */
private fun ruleOf(config: ArchitectureConfig, layer: String): LayerRule? =
    config.rules.firstOrNull { it.layer == layer }

/** Whether [config]'s rules let the modules of layer [from] depend on those of layer [to]. */
private fun isAllowed(config: ArchitectureConfig, from: String, to: String): Boolean =
    to in (ruleOf(config, from)?.mayDependOn ?: emptyList())

/** The modules of each layer [config] defines, in config order. */
private fun layersOf(
    config: ArchitectureConfig,
    layerOfModule: Map<String, String?>,
): List<LayerModules> = layerNames(config).map { name ->
    LayerModules(layer = name, modules = layerOfModule.keys.filter { layerOfModule[it] == name })
}

/** The modules [config] neither assigns to a layer nor excludes, sorted by path. */
private fun unassignedOf(
    config: ArchitectureConfig,
    layerOfModule: Map<String, String?>,
): List<String> = layerOfModule.filterValues { it == null }.keys.filter { !isExcluded(config, it) }

/** Sorted the way a reader walks the build: by file, then line. */
private fun <T> byFileThenLine(items: List<T>, file: (T) -> String, line: (T) -> Int): List<T> =
    items.sortedWith(compareBy(file, line))

/** Summarises the whole-build [graph] against [config]. */
internal fun layerSummary(graph: ModuleDependencies, config: ArchitectureConfig): LayerSummary {
    val layerOfModule = layerAssignment(config, graph)
    val layers = layersOf(config, layerOfModule)

    return LayerSummary(
        config = config,
        layers = layers,
        matrix = matrixOf(graph, config, layerOfModule, layerNames(config)),
        unassigned = unassignedOf(config, layerOfModule),
        unanalysedHelpers = graph.unanalysedHelpers,
        warnings = warningsOf(config, layers),
    )
}

/**
 * The layer → layer edges [graph] declares under [config], for a caller that needs the matrix
 * without the rest of the summary. [LayerEdge.allowed] still reads [config]'s rules.
 */
internal fun layerEdges(graph: ModuleDependencies, config: ArchitectureConfig): List<LayerEdge> =
    matrixOf(graph, config, layerAssignment(config, graph), layerNames(config))

/** Judges the whole [build] against [config]. */
internal fun architectureReport(
    build: BuildGraph,
    config: ArchitectureConfig,
): ArchitectureReport {
    val graph = build.graph
    val layerOfModule = layerAssignment(config, graph)
    val violations = violationsOf(graph, config, layerOfModule)

    return ArchitectureReport(
        violations = violations,
        unassigned = unassignedOf(config, layerOfModule),
        // Inclusions and dependencies alike: a module the guard never saw included was judged
        // as little as one it could not resolve a dependency on.
        unresolved = byFileThenLine(
            build.unresolvedInclusions + graph.modules.flatMap { it.unresolved },
            UnresolvedDeclaration::file,
            UnresolvedDeclaration::line,
        ),
        unanalysedHelpers = graph.unanalysedHelpers,
        warnings = warningsOf(config, layersOf(config, layerOfModule)),
        counts = ReportCounts(
            modules = graph.modules.size,
            dependencies = graph.modules.sumOf { module ->
                module.dependencies.distinctBy { it.target }.size
            },
            violations = violations.size,
        ),
    )
}

/**
 * Every module dependency whose source layer's rule does not allow its target layer. A module
 * outside every layer, and a dependency on one, cross no layers and so break no rule: an
 * excluded module appears on neither side. Each source module → target module pair is one
 * violation, reported at the first line that declares it.
 */
private fun violationsOf(
    graph: ModuleDependencies,
    config: ArchitectureConfig,
    layerOfModule: Map<String, String?>,
): List<Violation> {
    val violations = graph.modules.flatMap { module ->
        val from = layerOfModule[module.path] ?: return@flatMap emptyList()
        module.dependencies
            .sortedBy { it.line }
            .distinctBy { it.target }
            .mapNotNull { dependency ->
                val to = layerOfModule[dependency.target] ?: return@mapNotNull null
                if (isAllowed(config, from, to)) return@mapNotNull null
                Violation(
                    source = module.path,
                    sourceLayer = from,
                    target = dependency.target,
                    targetLayer = to,
                    file = dependency.file,
                    line = dependency.line,
                    rule = ruleOf(config, from),
                )
            }
    }
    return byFileThenLine(violations, Violation::file, Violation::line)
}

/**
 * One cell per layer → layer pair the build declares a module dependency for, ordered by the
 * config's layers. Each source module → target module pair counts once, however many times it
 * is declared. Modules outside every layer, and dependencies on modules that are not part of
 * the build, cross no layers and are counted nowhere.
 */
private fun matrixOf(
    graph: ModuleDependencies,
    config: ArchitectureConfig,
    layerOfModule: Map<String, String?>,
    names: List<String>,
): List<LayerEdge> {
    val counts = mutableMapOf<Pair<String, String>, Int>()
    for (module in graph.modules) {
        val from = layerOfModule[module.path] ?: continue
        for (target in module.dependencies.map { it.target }.distinct()) {
            val to = layerOfModule[target] ?: continue
            counts.merge(from to to, 1, Int::plus)
        }
    }

    val order = names.withIndex().associate { (index, name) -> name to index }
    return counts.entries
        .sortedWith(compareBy({ order.getValue(it.key.first) }, { order.getValue(it.key.second) }))
        .map { (pair, count) ->
            val (from, to) = pair
            LayerEdge(from, to, count, allowed = isAllowed(config, from, to))
        }
}

/** What the config says that the build does not bear out; analysis goes on regardless. */
private fun warningsOf(config: ArchitectureConfig, layers: List<LayerModules>): List<String> =
    buildList {
        val names = layers.map { it.layer }
        for (layer in layers.filter { it.modules.isEmpty() }) {
            add("layer '${layer.layer}' matches no module")
        }
        for (rule in config.rules) {
            if (rule.layer !in names) add("rule for undefined layer '${rule.layer}'")
            for (target in rule.mayDependOn.filter { it !in names }) {
                add("rule for layer '${rule.layer}' allows undefined layer '$target'")
            }
        }
    }
