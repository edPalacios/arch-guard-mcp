package com.archguard.core

import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class LayerSummaryTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `every layer holds the modules its pattern matches`() {
        val answer = layerSummary(fixture("layered"))

        assertEquals(
            listOf(
                LayerModules("app", listOf(":app")),
                LayerModules(
                    "feature",
                    listOf(
                        ":feature:account:profile",
                        ":feature:orders:history",
                        ":feature:orders:tracking",
                    ),
                ),
                LayerModules("component", listOf(":component:auth", ":component:orders:history")),
                LayerModules(
                    "shared-component",
                    listOf(":shared-component:cart", ":shared-component:pricing"),
                ),
                LayerModules("core", listOf(":core:network", ":core:ui")),
            ),
            answer.layers,
        )
    }

    @Test
    fun `the matrix counts the module dependencies crossing each pair of layers`() {
        val answer = layerSummary(fixture("layered"))

        assertEquals(
            listOf(
                LayerEdge("app", "feature", 3, allowed = true),
                LayerEdge("app", "component", 1, allowed = true),
                LayerEdge("app", "shared-component", 1, allowed = true),
                LayerEdge("app", "core", 1, allowed = true),
                LayerEdge("feature", "feature", 1, allowed = false),
                LayerEdge("feature", "component", 2, allowed = true),
                LayerEdge("feature", "shared-component", 3, allowed = true),
                LayerEdge("feature", "core", 5, allowed = true),
                LayerEdge("component", "shared-component", 2, allowed = true),
                LayerEdge("component", "core", 3, allowed = true),
                LayerEdge("shared-component", "core", 3, allowed = true),
                LayerEdge("core", "feature", 1, allowed = false),
                LayerEdge("core", "core", 1, allowed = true),
            ),
            answer.matrix,
        )
    }

    @Test
    fun `a layer may depend on itself only when its rule lists it`() {
        val answer = layerSummary(fixture("layered"))

        val selfEdges = answer.matrix.filter { it.from == it.to }
        assertEquals(
            listOf(
                LayerEdge("feature", "feature", 1, allowed = false),
                LayerEdge("core", "core", 1, allowed = true),
            ),
            selfEdges,
        )
    }

    @Test
    fun `the summary carries the config it was computed with`() {
        val answer = layerSummary(fixture("layered"))

        assertEquals(1, answer.config.version)
        assertEquals(LayerPattern(":feature:**", "feature"), answer.config.layers[1])
        assertEquals(
            LayerRule("shared-component", listOf("core")),
            answer.config.rules.single { it.layer == "shared-component" },
        )
        assertEquals(listOf(":sample:**"), answer.config.excluded)
    }

    @Test
    fun `an excluded module belongs to no layer and is not unassigned`() {
        val answer = layerSummary(fixture("layered"))

        assertTrue(answer.layers.none { ":sample:playground" in it.modules })
        assertTrue(answer.unassigned.isEmpty())
    }

    @Test
    fun `an excluded pattern wins over a layer that also matches`() {
        val projectDir = project(
            settings = """include(":app", ":sample:playground")""",
            config = """
            {
              "version": 1,
              "layers": [
                { "pattern": ":app", "name": "app" },
                { "pattern": ":sample:**", "name": "sample" }
              ],
              "rules": [],
              "excluded": [":sample:**"]
            }
            """,
        )

        val answer = layerSummary(projectDir)

        assertEquals(emptyList<String>(), answer.layers.single { it.layer == "sample" }.modules)
        assertEquals(emptyList<String>(), answer.unassigned)
    }

    @Test
    fun `dependency helpers are counted by name in the summary`() {
        val answer = layerSummary(fixture("layered"))

        assertEquals(mapOf("commonLibs" to 1), answer.unanalysedHelpers)
    }

    @Test
    fun `the first matching pattern names a module's layer`() {
        val projectDir = project(
            settings = """include(":feature:orders:history", ":feature:account:profile")""",
            config = """
            {
              "version": 1,
              "layers": [
                { "pattern": ":feature:orders:**", "name": "orders" },
                { "pattern": ":feature:**", "name": "feature" }
              ],
              "rules": []
            }
            """,
        )

        val answer = layerSummary(projectDir)

        assertEquals(
            listOf(
                LayerModules("orders", listOf(":feature:orders:history")),
                LayerModules("feature", listOf(":feature:account:profile")),
            ),
            answer.layers,
        )
    }

    @Test
    fun `a module matching no layer is unassigned`() {
        val projectDir = project(
            settings = """include(":app", ":legacy", ":benchmark")""",
            config = """
            {
              "version": 1,
              "layers": [{ "pattern": ":app", "name": "app" }],
              "rules": [],
              "excluded": [":benchmark"]
            }
            """,
        )

        val answer = layerSummary(projectDir)

        assertEquals(listOf(":legacy"), answer.unassigned)
    }

    @Test
    fun `a layer matching no module is a warning`() {
        val projectDir = project(
            settings = """include(":app")""",
            config = """
            {
              "version": 1,
              "layers": [
                { "pattern": ":app", "name": "app" },
                { "pattern": ":feature:**", "name": "feature" }
              ],
              "rules": []
            }
            """,
        )

        val answer = layerSummary(projectDir)

        assertEquals(listOf("layer 'feature' matches no module"), answer.warnings)
        assertEquals(listOf(":app"), answer.layers.single { it.layer == "app" }.modules)
    }

    @Test
    fun `a rule naming an undefined layer is a warning`() {
        val projectDir = project(
            settings = """include(":app")""",
            config = """
            {
              "version": 1,
              "layers": [{ "pattern": ":app", "name": "app" }],
              "rules": [
                { "layer": "app", "mayDependOn": ["ghost"] },
                { "layer": "phantom", "mayDependOn": ["app"] }
              ]
            }
            """,
        )

        val answer = layerSummary(projectDir)

        assertEquals(
            listOf(
                "rule for layer 'app' allows undefined layer 'ghost'",
                "rule for undefined layer 'phantom'",
            ),
            answer.warnings,
        )
        assertEquals(listOf(":app"), answer.layers.single { it.layer == "app" }.modules)
    }

    @Test
    fun `an omitted excluded list excludes nothing`() {
        val projectDir = project(
            settings = """include(":app")""",
            config = """
            {
              "version": 1,
              "layers": [{ "pattern": ":app", "name": "app" }],
              "rules": []
            }
            """,
        )

        val answer = layerSummary(projectDir)

        assertEquals(emptyList<String>(), answer.config.excluded)
    }

    @Test
    fun `a project without archguard json answers ConfigMissing`() {
        val projectDir = project(settings = """include(":app")""", config = null)

        val answer = ArchGuard.moduleGraph(projectDir)

        assertEquals(
            ConfigMissing(
                file = "archguard.json",
                expectedFormat = EXPECTED_FORMAT,
                proposedConfig = ArchitectureConfig(1, emptyList(), emptyList()),
            ),
            answer,
        )
    }

    @Test
    fun `malformed JSON answers ConfigInvalid`() {
        val projectDir = project(settings = """include(":app")""", config = "{ not json")

        val answer = configInvalid(projectDir)

        assertEquals("archguard.json", answer.file)
        assertEquals(1, answer.problems.size)
        assertEquals(EXPECTED_FORMAT, answer.expectedFormat)
    }

    @Test
    fun `a missing required field answers ConfigInvalid`() {
        val projectDir = project(
            settings = """include(":app")""",
            config = """{ "version": 1 }""",
        )

        val answer = configInvalid(projectDir)

        assertEquals(
            listOf("missing required field 'layers'", "missing required field 'rules'"),
            answer.problems,
        )
    }

    @Test
    fun `an unknown field answers ConfigInvalid naming the field`() {
        val projectDir = project(
            settings = """include(":app")""",
            config = """{ "version": 1, "layers": [], "rules": [], "excludedd": [] }""",
        )

        val answer = configInvalid(projectDir)

        assertEquals(listOf("unknown field 'excludedd'"), answer.problems)
    }

    @Test
    fun `a version other than 1 answers ConfigInvalid`() {
        val projectDir = project(
            settings = """include(":app")""",
            config = """{ "version": 2, "layers": [], "rules": [] }""",
        )

        val answer = configInvalid(projectDir)

        assertEquals(listOf("unsupported version 2: expected 1"), answer.problems)
    }

    private fun layerSummary(projectDir: Path): LayerSummary =
        assertIs<LayerSummary>(ArchGuard.moduleGraph(projectDir))

    private fun configInvalid(projectDir: Path): ConfigInvalid =
        assertIs<ConfigInvalid>(ArchGuard.moduleGraph(projectDir))

    /** A project with those [settings] and, when given, that `archguard.json` [config]. */
    private fun project(settings: String, config: String?): Path {
        val projectDir = tempDir.resolve("project").createDirectory()
        projectDir.resolve(SETTINGS_FILE).writeText(settings)
        if (config != null) projectDir.resolve(CONFIG_FILE).writeText(config.trimIndent())
        return projectDir
    }
}
