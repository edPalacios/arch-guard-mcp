package com.archguard.core

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class CheckArchitectureTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `the report holds exactly the dependencies no layer rule allows, by file then line`() {
        val answer = report(fixture("layered"))

        assertEquals(
            listOf(
                Violation(
                    source = ":core:ui",
                    sourceLayer = "core",
                    target = ":feature:account:profile",
                    targetLayer = "feature",
                    file = "core/ui/build.gradle.kts",
                    line = 13,
                    rule = LayerRule("core", listOf("core")),
                ),
                Violation(
                    source = ":feature:orders:history",
                    sourceLayer = "feature",
                    target = ":feature:orders:tracking",
                    targetLayer = "feature",
                    file = "feature/orders/history/build.gradle.kts",
                    line = 16,
                    rule = LayerRule("feature", listOf("component", "shared-component", "core")),
                ),
            ),
            answer.violations,
        )
    }

    @Test
    fun `an excluded module is in no violation and in no unassigned module`() {
        val answer = report(fixture("layered"))

        assertTrue(answer.violations.none { ":sample:playground" in listOf(it.source, it.target) })
        assertEquals(emptyList<String>(), answer.unassigned)
    }

    @Test
    fun `a module matching no layer is unassigned, not a violation`() {
        val projectDir = project(
            settings = """include(":app", ":legacy")""",
            config = """
            {
              "version": 1,
              "layers": [{ "pattern": ":app", "name": "app" }],
              "rules": []
            }
            """,
            builds = mapOf(":app" to """dependencies { implementation(project(":legacy")) }"""),
        )

        val answer = report(projectDir)

        assertEquals(listOf(":legacy"), answer.unassigned)
        assertEquals(emptyList<Violation>(), answer.violations)
    }

    @Test
    fun `a layer without a rule may depend on nothing`() {
        val projectDir = project(
            settings = """include(":app", ":core:ui")""",
            config = """
            {
              "version": 1,
              "layers": [
                { "pattern": ":app", "name": "app" },
                { "pattern": ":core:**", "name": "core" }
              ],
              "rules": []
            }
            """,
            builds = mapOf(":app" to """dependencies { implementation(project(":core:ui")) }"""),
        )

        val answer = report(projectDir)

        assertEquals(
            Violation(":app", "app", ":core:ui", "core", "app/build.gradle.kts", 1, rule = null),
            answer.violations.single(),
        )
    }

    @Test
    fun `the same dependency in two configurations is one violation`() {
        val projectDir = project(
            settings = """include(":core:ui", ":feature:home")""",
            config = """
            {
              "version": 1,
              "layers": [
                { "pattern": ":core:**", "name": "core" },
                { "pattern": ":feature:**", "name": "feature" }
              ],
              "rules": [{ "layer": "core", "mayDependOn": ["core"] }]
            }
            """,
            builds = mapOf(
                ":core:ui" to """
                dependencies {
                    api(project(":feature:home"))
                    implementation(project(":feature:home"))
                }
                """,
            ),
        )

        val answer = report(projectDir)

        assertEquals(2, answer.violations.single().line)
        assertEquals(1, answer.counts.violations)
    }

    @Test
    fun `a dependency on a module the build does not include is dangling, not a violation`() {
        val answer = report(fixture("layered"))

        assertEquals(
            listOf(
                DanglingDependency(
                    source = ":core:ui",
                    target = ":feature:account:favorites",
                    configuration = "implementation",
                    file = "core/ui/build.gradle.kts",
                    line = 16,
                ),
            ),
            answer.danglingDependencies,
        )
        assertTrue(answer.violations.none { it.target == ":feature:account:favorites" })
    }

    @Test
    fun `a dangling accessor is reported as the accessor it is written as`() {
        val projectDir = project(
            settings = """include(":app")""",
            config = """
            {
              "version": 1,
              "layers": [{ "pattern": ":app", "name": "app" }],
              "rules": []
            }
            """,
            builds = mapOf(":app" to """dependencies { api(projects.feature.favorites) }"""),
        )

        val answer = report(projectDir)

        assertEquals(
            DanglingDependency(
                source = ":app",
                target = "projects.feature.favorites",
                configuration = "api",
                file = "app/build.gradle.kts",
                line = 1,
            ),
            answer.danglingDependencies.single(),
        )
        assertEquals(emptyList<UnresolvedDeclaration>(), answer.unresolved)
    }

    @Test
    fun `a dangling dependency of an excluded module is reported too`() {
        val projectDir = project(
            settings = """include(":app", ":sample:playground")""",
            config = """
            {
              "version": 1,
              "layers": [{ "pattern": ":app", "name": "app" }],
              "rules": [],
              "excluded": [":sample:**"]
            }
            """,
            builds = mapOf(
                ":sample:playground" to """dependencies { implementation(project(":gone")) }""",
            ),
        )

        val answer = report(projectDir)

        assertEquals(
            DanglingDependency(
                source = ":sample:playground",
                target = ":gone",
                configuration = "implementation",
                file = "sample/playground/build.gradle.kts",
                line = 1,
            ),
            answer.danglingDependencies.single(),
        )
    }

    @Test
    fun `dangling dependencies are sorted by file then line`() {
        val projectDir = project(
            settings = """include(":app", ":core:ui")""",
            config = """
            {
              "version": 1,
              "layers": [
                { "pattern": ":app", "name": "app" },
                { "pattern": ":core:**", "name": "core" }
              ],
              "rules": []
            }
            """,
            builds = mapOf(
                ":core:ui" to """
                dependencies {
                    implementation(project(":gone"))
                }
                """,
                ":app" to """
                dependencies {
                    implementation(project(":also-gone"))
                    implementation(project(":gone"))
                }
                """,
            ),
        )

        val answer = report(projectDir)

        assertEquals(
            listOf(
                Triple("app/build.gradle.kts", 2, ":also-gone"),
                Triple("app/build.gradle.kts", 3, ":gone"),
                Triple("core/ui/build.gradle.kts", 2, ":gone"),
            ),
            answer.danglingDependencies.map { Triple(it.file, it.line, it.target) },
        )
    }

    @Test
    fun `a declaration only Gradle could resolve is reported, not judged`() {
        val answer = report(fixture("layered"))

        assertEquals(
            listOf(
                UnresolvedDeclaration(
                    declaration = "serializationModule",
                    file = "core/network/build.gradle.kts",
                    line = 10,
                ),
            ),
            answer.unresolved,
        )
    }

    @Test
    fun `an include only Gradle could resolve is reported beside the dependency declarations`() {
        val projectDir = project(
            settings = """
            val extra = "legacy"
            include(":app", ":feature:${'$'}extra")
            """,
            config = """
            {
              "version": 1,
              "layers": [{ "pattern": ":app", "name": "app" }],
              "rules": []
            }
            """,
            builds = mapOf(
                ":app" to """
                dependencies {
                    implementation(project(featureModule))
                }
                """,
            ),
        )

        val answer = report(projectDir)

        assertEquals(
            listOf(
                UnresolvedDeclaration("featureModule", "app/build.gradle.kts", 2),
                UnresolvedDeclaration("\":feature:${'$'}extra\"", SETTINGS_FILE, 2),
            ),
            answer.unresolved,
        )
    }

    @Test
    fun `dependency helpers are counted by name in the report`() {
        val answer = report(fixture("layered"))

        assertEquals(mapOf("commonLibs" to 1), answer.unanalysedHelpers)
    }

    @Test
    fun `the counts size up what was checked`() {
        val answer = report(fixture("layered"))

        assertEquals(
            ReportCounts(
                modules = 11,
                dependencies = 29,
                violations = 2,
                danglingDependencies = 1,
            ),
            answer.counts,
        )
    }

    @Test
    fun `what the config claims and the build does not bear out is a warning`() {
        val projectDir = project(
            settings = """include(":app")""",
            config = """
            {
              "version": 1,
              "layers": [
                { "pattern": ":app", "name": "app" },
                { "pattern": ":feature:**", "name": "feature" }
              ],
              "rules": [{ "layer": "app", "mayDependOn": ["feature"] }]
            }
            """,
            builds = emptyMap(),
        )

        val answer = report(projectDir)

        assertEquals(listOf("layer 'feature' matches no module"), answer.warnings)
    }

    @Test
    fun `a project without archguard json answers ConfigMissing`() {
        val projectDir = project(settings = """include(":app")""", config = null)

        val answer = ArchGuard.checkArchitecture(projectDir)

        assertIs<ConfigMissing>(answer)
        assertEquals(CONFIG_FILE, answer.file)
        assertEquals(EXPECTED_FORMAT, answer.expectedFormat)
    }

    @Test
    fun `an unusable archguard json answers ConfigInvalid`() {
        val projectDir = project(settings = """include(":app")""", config = "{ not json")

        val answer = ArchGuard.checkArchitecture(projectDir)

        assertIs<ConfigInvalid>(answer)
        assertEquals(CONFIG_FILE, answer.file)
    }

    @Test
    fun `a directory without a settings file answers ProjectNotFound`() {
        val answer = ArchGuard.checkArchitecture(tempDir)

        assertEquals(
            ProjectNotFound(tempDir.toString(), "no settings.gradle.kts in this directory"),
            answer,
        )
    }

    private fun report(projectDir: Path): ArchitectureReport =
        assertIs<ArchitectureReport>(ArchGuard.checkArchitecture(projectDir))

    /** A project with those [settings], that `archguard.json` [config] and those build scripts. */
    private fun project(
        settings: String,
        config: String?,
        builds: Map<String, String> = emptyMap(),
    ): Path = tempProject(tempDir, settings, config, builds)
}
