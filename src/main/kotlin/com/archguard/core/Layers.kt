package com.archguard.core

/**
 * Putting modules in layers and summarising how they depend on each other: pure functions of
 * the build already read and the architecture config.
 */

/**
 * The layer [module] belongs to, or null when the config excludes it or no pattern matches it
 * (see [isExcluded] to tell those two apart). The first matching pattern wins, so the config's
 * order is the one that decides.
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

/** Whether [config]'s rules let the modules of layer [from] depend on those of layer [to]. */
private fun isAllowed(config: ArchitectureConfig, from: String, to: String): Boolean =
    config.rules.any { it.layer == from && to in it.mayDependOn }

/** Summarises the whole-build [graph] against [config]. */
internal fun layerSummary(graph: ModuleDependencies, config: ArchitectureConfig): LayerSummary {
    val names = layerNames(config)
    val layerOfModule = graph.modules.associate { it.path to layerOf(config, it.path) }
    val modules = graph.modules.map { it.path } // already sorted by path

    val layers = names.map { name ->
        LayerModules(layer = name, modules = modules.filter { layerOfModule[it] == name })
    }

    return LayerSummary(
        config = config,
        layers = layers,
        matrix = matrixOf(graph, config, layerOfModule, names),
        unassigned = modules.filter { layerOfModule[it] == null && !isExcluded(config, it) },
        unanalysedHelpers = graph.unanalysedHelpers,
        warnings = warningsOf(config, layers),
    )
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
