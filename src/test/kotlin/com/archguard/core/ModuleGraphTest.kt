package com.archguard.core

import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ModuleGraphTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `a module dependency carries target, configuration, file and line`() {
        val answer = moduleGraph(fixture("layered"), ":feature:orders:history")

        val detail = answer.modules.single()
        val file = "feature/orders/history/build.gradle.kts"
        assertEquals(":feature:orders:history", detail.path)
        assertEquals(file, detail.buildFile)
        assertEquals(
            listOf(
                ModuleDependency(":component:orders:history", "implementation", file, 11),
                ModuleDependency(":core:network", "implementation", file, 14),
                ModuleDependency(":core:ui", "implementation", file, 13),
                ModuleDependency(":feature:orders:tracking", "implementation", file, 16),
                ModuleDependency(":shared-component:cart", "implementation", file, 12),
            ),
            detail.dependencies,
        )
        assertTrue(detail.unresolved.isEmpty())
    }

    @Test
    fun `a pattern selects every module under a path`() {
        val answer = moduleGraph(fixture("layered"), ":feature:**")

        assertEquals(
            listOf(":feature:account:profile", ":feature:orders:history", ":feature:orders:tracking"),
            answer.modules.map { it.path },
        )
    }

    @Test
    fun `the pattern of every module covers the whole build`() {
        val answer = moduleGraph(fixture("layered"), "**")

        assertEquals("**", answer.filter)
        assertEquals(11, answer.modules.size)
    }

    @Test
    fun `type-safe project accessors name the same modules as project paths`() {
        val answer = moduleGraph(fixture("layered"), ":feature:account:profile")

        val file = "feature/account/profile/build.gradle.kts"
        assertEquals(
            listOf(
                ModuleDependency(":component:auth", "implementation", file, 12),
                ModuleDependency(":core:ui", "compileOnly", file, 14),
                ModuleDependency(":shared-component:pricing", "implementation", file, 13),
            ),
            answer.modules.single().dependencies,
        )
    }

    @Test
    fun `api declares a module dependency`() {
        val answer = moduleGraph(fixture("layered"), ":component:auth")

        val file = "component/auth/build.gradle.kts"
        assertEquals(
            listOf(
                ModuleDependency(":core:network", "implementation", file, 12),
                ModuleDependency(":shared-component:pricing", "api", file, 11),
            ),
            answer.modules.single().dependencies,
        )
    }

    @Test
    fun `a test configuration does not declare a module dependency`() {
        val answer = moduleGraph(fixture("layered"), ":app")

        assertEquals(
            listOf(
                ":component:auth",
                ":core:ui",
                ":feature:account:profile",
                ":feature:orders:history",
                ":feature:orders:tracking",
                ":shared-component:cart",
            ),
            answer.modules.single().dependencies.map { it.target },
        )
    }

    @Test
    fun `the same module dependency declared twice is one edge`() {
        val answer = moduleGraph(fixture("layered"), ":feature:orders:tracking")

        val file = "feature/orders/tracking/build.gradle.kts"
        assertEquals(
            listOf(ModuleDependency(":core:network", "implementation", file, 13)),
            answer.modules.single().dependencies.filter { it.target == ":core:network" },
        )
    }

    @Test
    fun `a project path that is not a plain literal is an unresolved declaration`() {
        val answer = moduleGraph(fixture("layered"), ":core:network")

        val detail = answer.modules.single()
        assertTrue(detail.dependencies.isEmpty())
        assertEquals(
            listOf(
                UnresolvedDeclaration(
                    "serializationModule",
                    "core/network/build.gradle.kts",
                    10,
                ),
            ),
            detail.unresolved,
        )
    }

    @Test
    fun `dependency helpers are counted by name and reported nowhere else`() {
        val answer = moduleGraph(fixture("layered"), "**")

        assertEquals(mapOf("commonLibs" to 1), answer.unanalysedHelpers)
        assertTrue(answer.modules.none { detail -> detail.dependencies.any { "commonLibs" in it.target } })
        assertTrue(answer.modules.none { detail -> detail.unresolved.any { "commonLibs" in it.declaration } })
    }

    @Test
    fun `a call that does not receive project is not a dependency helper`() {
        val projectDir = project(
            """
            dependencies {
                debugImplementation(libs.leakcanary)
                detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.8")
            }
            """,
        )

        val answer = moduleGraph(projectDir, "**")

        assertTrue(answer.unanalysedHelpers.isEmpty())
    }

    @Test
    fun `a module without a build script has no dependencies`() {
        val projectDir = project(null)

        val answer = moduleGraph(projectDir, ":a")

        assertEquals(ModuleDetail(":a", null, emptyList(), emptyList()), answer.modules.single())
    }

    @Test
    fun `a filter matching no module is a warning`() {
        val answer = moduleGraph(fixture("layered"), ":feature:*")

        assertTrue(answer.modules.isEmpty())
        assertEquals(listOf("no module matches ':feature:*'"), answer.warnings)
    }

    @Test
    fun `a directory without a settings file answers ProjectNotFound`() {
        val answer = ArchGuard.moduleGraph(tempDir, ":app")

        assertEquals(
            ProjectNotFound(tempDir.toString(), "no settings.gradle.kts in this directory"),
            answer,
        )
    }

    private fun moduleGraph(projectDir: Path, module: String?): ModuleDependencies =
        assertIs<ModuleDependencies>(ArchGuard.moduleGraph(projectDir, module))

    /** A project with a single module `:a`, whose build script holds [buildScript] when given. */
    private fun project(buildScript: String?): Path {
        val projectDir = tempDir.resolve("project").createDirectory()
        projectDir.resolve(SETTINGS_FILE).writeText("""include(":a")""")
        if (buildScript != null) {
            projectDir.resolve("a").createDirectory()
                .resolve(BUILD_FILE).writeText(buildScript.trimIndent())
        }
        return projectDir
    }
}
