package com.archguard.core

import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir

class ConfigMissingTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `app, feature and core are conventional layers and every other segment a project layer`() {
        val answer = configMissing(layeredProject())

        assertEquals(
            listOf(
                LayerPattern(":app", "app"),
                LayerPattern(":feature:**", "feature"),
                LayerPattern(":component:**", "component"),
                LayerPattern(":shared-component:**", "shared-component"),
                LayerPattern(":core:**", "core"),
            ),
            answer.proposedConfig.layers,
        )
    }

    @Test
    fun `the conventional layers follow the official Android guidance`() {
        val answer = configMissing(
            project(
                settings = """
                include(":app")
                include(":feature:orders")
                include(":core:ui")
                """,
                builds = emptyMap(),
            ),
        )

        assertEquals(
            listOf(
                LayerRule("app", listOf("feature", "core")),
                LayerRule("feature", listOf("core")),
                LayerRule("core", listOf("core")),
            ),
            answer.proposedConfig.rules,
        )
    }

    @Test
    fun `an edge touching a project layer is a rule when the build declares it`() {
        val answer = configMissing(layeredProject())

        assertEquals(
            listOf(
                LayerRule("app", listOf("feature", "component", "shared-component", "core")),
                LayerRule("feature", listOf("component", "shared-component", "core")),
                LayerRule("component", listOf("shared-component", "core")),
                LayerRule("shared-component", listOf("core")),
                LayerRule("core", listOf("core")),
            ),
            answer.proposedConfig.rules,
        )
    }

    @Test
    fun `an edge contradicting the guidance is a review mark instead of a rule`() {
        val answer = configMissing(layeredProject())

        assertEquals(
            listOf("feature" to "feature", "core" to "feature"),
            answer.reviewMarks.map { it.from to it.to },
        )
    }

    @Test
    fun `a review mark counts the module dependencies behind it and says why`() {
        val answer = configMissing(layeredProject())

        assertEquals(
            ReviewMark(
                from = "feature",
                to = "feature",
                count = 1,
                reason = "the official Android guidance has no feature → feature dependency: " +
                    "allow it in \"rules\" only if this project means it, otherwise the " +
                    "dependency is what to change",
            ),
            answer.reviewMarks.first(),
        )
    }

    @Test
    fun `a dependency on the app layer is a review mark, project layer or not`() {
        val answer = configMissing(
            project(
                settings = """
                include(":app")
                include(":component:auth")
                """,
                builds = mapOf(
                    ":component:auth" to """dependencies { implementation(project(":app")) }""",
                ),
            ),
        )

        assertEquals(listOf("component" to "app"), answer.reviewMarks.map { it.from to it.to })
        assertEquals(
            listOf(LayerRule("app", emptyList()), LayerRule("component", emptyList())),
            answer.proposedConfig.rules,
        )
    }

    @Test
    fun `samples, benchmarks and architecture modules are excluded instead of layered`() {
        val answer = configMissing(layeredProject())

        assertEquals(
            listOf(":architecture-tests", ":benchmark", ":sample:**"),
            answer.proposedConfig.excluded,
        )
    }

    @Test
    fun `the proposed config is an archguard json the guard reads`() {
        val projectDir = layeredProject()
        val proposal = configMissing(projectDir).proposedConfig

        projectDir.resolve(CONFIG_FILE).writeText(Json.encodeToString(proposal))

        assertIs<ArchitectureReport>(ArchGuard.checkArchitecture(projectDir))
    }

    @Test
    fun `the proposed config leaves no module unassigned`() {
        val projectDir = layeredProject()
        val proposal = configMissing(projectDir).proposedConfig

        projectDir.resolve(CONFIG_FILE).writeText(Json.encodeToString(proposal))

        val report = assertIs<ArchitectureReport>(ArchGuard.checkArchitecture(projectDir))
        assertEquals(emptyList<String>(), report.unassigned)
    }

    @Test
    fun `the expected format travels with the proposal`() {
        val answer = configMissing(layeredProject())

        assertEquals(CONFIG_FILE, answer.file)
        assertEquals(EXPECTED_FORMAT, answer.expectedFormat)
    }

    @Test
    fun `an unfiltered module graph proposes the same config as the check`() {
        val projectDir = layeredProject()

        val answer = assertIs<ConfigMissing>(ArchGuard.moduleGraph(projectDir))

        assertEquals(configMissing(projectDir), answer)
    }

    @Test
    fun `a module filter needs no config`() {
        val projectDir = layeredProject()

        val answer = ArchGuard.moduleGraph(projectDir, ":core:**")

        assertIs<ModuleDependencies>(answer)
    }

    /** The [ConfigMissing] a project without an `archguard.json` is answered with. */
    private fun configMissing(projectDir: Path): ConfigMissing =
        assertIs<ConfigMissing>(ArchGuard.checkArchitecture(projectDir))

    private fun project(settings: String, builds: Map<String, String>): Path =
        tempProject(tempDir, settings, config = null, builds = builds)

    /**
     * A project shaped like the `layered` fixture but without its config: the conventional
     * layers, two project layers, a sample, a benchmark and an architecture module, plus the
     * two edges the official guidance contradicts.
     */
    private fun layeredProject(): Path = project(
        settings = """
        include(":app")
        include(":feature:orders:history")
        include(":feature:orders:tracking")
        include(":component:auth")
        include(":shared-component:cart")
        include(":core:ui")
        include(":sample:playground")
        include(":benchmark")
        include(":architecture-tests")
        """,
        builds = mapOf(
            ":app" to """
            dependencies {
                implementation(project(":feature:orders:history"))
                implementation(project(":component:auth"))
                implementation(project(":shared-component:cart"))
                implementation(project(":core:ui"))
            }
            """,
            ":feature:orders:history" to """
            dependencies {
                implementation(project(":component:auth"))
                implementation(project(":shared-component:cart"))
                implementation(project(":core:ui"))
                // Contradicts the guidance: one feature module depending on another.
                implementation(project(":feature:orders:tracking"))
            }
            """,
            ":component:auth" to """
            dependencies {
                implementation(project(":shared-component:cart"))
                implementation(project(":core:ui"))
            }
            """,
            ":shared-component:cart" to """dependencies { api(project(":core:ui")) }""",
            ":core:ui" to """
            dependencies {
                // Contradicts the guidance: a core module depending on a feature module.
                implementation(project(":feature:orders:tracking"))
            }
            """,
            ":sample:playground" to """dependencies { implementation(project(":app")) }""",
        ),
    )
}
