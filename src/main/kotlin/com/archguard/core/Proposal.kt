package com.archguard.core

/**
 * Proposing an architecture config for a project that has none: a pure function of the build
 * already read. The top-level segment of each module path becomes a layer, the official Android
 * modularization guidance writes the rules between the conventional layers, and the build itself
 * writes the rules the guidance says nothing about.
 */

/** The layers whose meaning the official Android guidance fixes, in its top-down order. */
private const val APP = "app"
private const val FEATURE = "feature"
private const val CORE = "core"
private val CONVENTIONAL_LAYERS = setOf(APP, FEATURE, CORE)

/** What the official Android modularization guidance lets each conventional layer depend on. */
private val GUIDANCE: Map<String, List<String>> = mapOf(
    APP to listOf(FEATURE, CORE),
    FEATURE to listOf(CORE),
    CORE to listOf(CORE),
)

/** Top-level segments that hold no production code, so the proposal excludes them. */
private val EXCLUDED_SEGMENTS = setOf("sample", "samples", "benchmark", "benchmarks")

/**
 * A proposed config and what the proposal would not decide on its own.
 *
 * @property config the config inferred from the build.
 * @property reviewMarks the observed layer → layer edges [config] leaves forbidden.
 */
internal data class Proposal(
    val config: ArchitectureConfig,
    val reviewMarks: List<ReviewMark>,
)

/** The config [graph]'s build suggests, and the edges it declares that the config forbids. */
internal fun propose(graph: ModuleDependencies): Proposal {
    val modulesBySegment = graph.modules.map { it.path }.groupBy(::topSegmentOf)
    val (excluded, layers) = modulesBySegment.keys.partition(::isExcludedSegment)
    val names = layers.sortedWith(compareBy({ layerRank(it) }, { it }))
    val skeleton = ArchitectureConfig(
        version = SUPPORTED_VERSION,
        layers = names.map { LayerPattern(patternOf(it, modulesBySegment.getValue(it)), it) },
        rules = emptyList(),
        excluded = excluded.sorted().map { patternOf(it, modulesBySegment.getValue(it)) },
    )

    // The skeleton has no rules yet, so only the from, to and count of these edges mean anything.
    val edges = layerEdges(graph, skeleton)
    val observed = edges.map { it.from to it.to }.toSet()

    return Proposal(
        config = skeleton.copy(
            rules = names.map { from ->
                LayerRule(from, names.filter { to -> allows(from, to, (from to to) in observed) })
            },
        ),
        reviewMarks = edges
            .filterNot { allows(it.from, it.to, observed = true) }
            .map { ReviewMark(it.from, it.to, it.count, reasonOf(it.from, it.to)) },
    )
}

/**
 * Whether the proposal lets the modules of layer [from] depend on those of layer [to]: between
 * two conventional layers the guidance decides, whatever the build does; where a project layer
 * is involved the guidance says nothing, so the build decides and an [observed] edge is allowed.
 * Nothing may depend on the app layer, which nothing in the guidance does either.
 */
private fun allows(from: String, to: String, observed: Boolean): Boolean = when {
    to in GUIDANCE[from].orEmpty() -> true
    to == APP -> false
    from in CONVENTIONAL_LAYERS && to in CONVENTIONAL_LAYERS -> false
    else -> observed
}

/** Why an observed edge is a review mark instead of a rule. */
private fun reasonOf(from: String, to: String): String =
    "the official Android guidance has no $from → $to dependency: allow it in \"rules\" " +
        "only if this project means it, otherwise the dependency is what to change"

/** The first segment of [module]'s path, the one the proposal makes a layer of. */
private fun topSegmentOf(module: String): String = module.removePrefix(":").substringBefore(':')

/**
 * Whether [segment] holds nothing to layer: samples and benchmarks are not production code, and
 * an architecture module (see [ruleSources]) states the rules instead of living by them.
 */
private fun isExcludedSegment(segment: String): Boolean =
    segment in EXCLUDED_SEGMENTS || ARCHITECTURE in segment.lowercase()

/**
 * Where a layer sits in the proposal's order: the guidance's top-down order, with the project
 * layers between feature and core, where a project's own layers usually sit. One pattern per
 * top-level segment, so no two patterns overlap and this order is for the reader only, not for
 * the config's first-match rule.
 */
private fun layerRank(layer: String): Int = when (layer) {
    APP -> 0
    FEATURE -> 1
    CORE -> 3
    else -> 2
}

/**
 * The pattern covering [segment]'s [modules]: the module path itself when the segment is one
 * module, every module under it otherwise.
 */
private fun patternOf(segment: String, modules: List<String>): String =
    if (modules == listOf(":$segment")) ":$segment" else ":$segment:$ALL_MODULES"
