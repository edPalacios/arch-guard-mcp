package com.archguard.core

import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ListModulesTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `lists the modules of a modularised Android project`() {
        val answer = moduleList(fixture("layered"))

        assertEquals("layered", answer.rootProjectName)
        assertEquals(
            listOf(
                ":app",
                ":component:auth",
                ":component:orders:history",
                ":core:network",
                ":core:ui",
                ":feature:account:profile",
                ":feature:orders:history",
                ":feature:orders:tracking",
                ":sample:playground",
                ":shared-component:cart",
                ":shared-component:pricing",
            ),
            answer.modules,
        )
        assertTrue(answer.unresolved.isEmpty())
    }

    @Test
    fun `reads includes spread over several lines, past comments and repository URLs`() {
        val projectDir = project(
            """
            pluginManagement {
                repositories {
                    maven("https://plugins.gradle.org/m2/") // a URL is not a comment
                }
            }
            include(
                ":core:domain",
                ":core:data",   // trailing comment
            )
            /* include(":legacy") */
            // include(":disabled")
            includeBuild("build-logic")
            """,
        )

        val answer = moduleList(projectDir)

        assertEquals(listOf(":core:data", ":core:domain"), answer.modules)
    }

    @Test
    fun `deduplicates repeated includes`() {
        val projectDir = project("""include(":b", ":a")${'\n'}include(":b")""")

        val answer = moduleList(projectDir)

        assertEquals(listOf(":a", ":b"), answer.modules)
    }

    @Test
    fun `a module path without a leading colon is normalised`() {
        val projectDir = project("""include("feature:profile")""")

        val answer = moduleList(projectDir)

        assertEquals(listOf(":feature:profile"), answer.modules)
    }

    @Test
    fun `an include argument that is not a plain literal is an unresolved declaration`() {
        val projectDir = project(
            """
            val name = "x"
            include(":core", ":feature:${'$'}name", featureModule)
            """,
        )

        val answer = moduleList(projectDir)

        assertEquals(listOf(":core"), answer.modules)
        assertEquals(
            listOf(
                UnresolvedDeclaration("\":feature:${'$'}name\"", "settings.gradle.kts", 2),
                UnresolvedDeclaration("featureModule", "settings.gradle.kts", 2),
            ),
            answer.unresolved,
        )
        assertNull(answer.rootProjectName)
    }

    @Test
    fun `a single-module project has no modules`() {
        val projectDir = project("""rootProject.name = "solo"""")

        val answer = moduleList(projectDir)

        assertEquals("solo", answer.rootProjectName)
        assertTrue(answer.modules.isEmpty())
    }

    @Test
    fun `declaring a function named include is not an include call`() {
        val projectDir = project(
            """
            fun include(path: String) {
                settings.include(path)
            }
            include(":app")
            """,
        )

        val answer = moduleList(projectDir)

        assertEquals(listOf(":app"), answer.modules)
        assertEquals(
            listOf(UnresolvedDeclaration("path", "settings.gradle.kts", 2)),
            answer.unresolved,
        )
    }

    @Test
    fun `a missing directory answers ProjectNotFound`() {
        val missing = tempDir.resolve("nowhere")

        val answer = ArchGuard.listModules(missing)

        assertEquals(ProjectNotFound(missing.toString(), "not a directory"), answer)
    }

    @Test
    fun `a directory without a settings file answers ProjectNotFound`() {
        val answer = ArchGuard.listModules(tempDir)

        assertEquals(
            ProjectNotFound(tempDir.toString(), "no settings.gradle.kts in this directory"),
            answer,
        )
    }

    private fun moduleList(projectDir: Path): ModuleList =
        assertIs<ModuleList>(ArchGuard.listModules(projectDir))

    /** A project whose only file is a `settings.gradle.kts` holding [settings]. */
    private fun project(settings: String): Path {
        val projectDir = tempDir.resolve("project").createDirectory()
        projectDir.resolve(SETTINGS_FILE).writeText(settings.trimIndent())
        return projectDir
    }
}

/** The fixture project of that name under `src/test/resources/fixtures`. */
internal fun fixture(name: String): Path {
    val url = checkNotNull(ListModulesTest::class.java.getResource("/fixtures/$name")) {
        "no fixture project '$name'"
    }
    return Paths.get(url.toURI())
}
