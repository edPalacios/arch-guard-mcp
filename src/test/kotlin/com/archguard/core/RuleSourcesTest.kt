package com.archguard.core

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.io.TempDir

class RuleSourcesTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `guidelines, agent instructions, architecture tests and detekt config are rule sources`() {
        val projectDir = project(
            modules = listOf(":app", ":architecture-tests"),
            files = listOf(
                "CLAUDE.md",
                "AGENTS.md",
                "CONTRIBUTING.md",
                "docs/architecture.md",
                "docs/nested/layers.md",
                "code_quality/detekt-config.yml",
                "architecture-tests/src/test/kotlin/KonsistLayerTest.kt",
            ),
        )

        val answer = configMissing(projectDir)

        assertEquals(
            listOf(
                RuleSource("AGENTS.md", RuleSourceKind.AGENT_INSTRUCTIONS),
                RuleSource("CLAUDE.md", RuleSourceKind.AGENT_INSTRUCTIONS),
                RuleSource("CONTRIBUTING.md", RuleSourceKind.DOCUMENTATION),
                RuleSource("docs/architecture.md", RuleSourceKind.DOCUMENTATION),
                RuleSource("docs/nested/layers.md", RuleSourceKind.DOCUMENTATION),
                RuleSource(":architecture-tests", RuleSourceKind.ARCHITECTURE_MODULE),
                RuleSource(
                    "architecture-tests/src/test/kotlin/KonsistLayerTest.kt",
                    RuleSourceKind.ARCHITECTURE_TEST,
                ),
                RuleSource("code_quality/detekt-config.yml", RuleSourceKind.DETEKT_CONFIG),
            ),
            answer.ruleSources,
        )
    }

    @Test
    fun `markdown outside the root and docs is nobody's architecture rule`() {
        val projectDir = project(files = listOf("core/ui/README.md", "docs/overview.md"))

        val answer = configMissing(projectDir)

        assertEquals(listOf("docs/overview.md"), answer.ruleSources.map { it.path })
    }

    @Test
    fun `build output and hidden directories are not walked`() {
        val projectDir = project(
            files = listOf("build/reports/detekt-config.yml", ".idea/docs/rules.md", "docs/a.md"),
        )

        val answer = configMissing(projectDir)

        assertEquals(listOf("docs/a.md"), answer.ruleSources.map { it.path })
    }

    @Test
    fun `a detekt baseline states no rule`() {
        val projectDir = project(files = listOf("app/detekt-baseline.xml"))

        val answer = configMissing(projectDir)

        assertEquals(emptyList<RuleSource>(), answer.ruleSources)
    }

    private fun configMissing(projectDir: Path): ConfigMissing =
        assertIs<ConfigMissing>(ArchGuard.checkArchitecture(projectDir))

    /** A project of those [modules], holding those [files]. */
    private fun project(files: List<String>, modules: List<String> = listOf(":app")): Path {
        val projectDir = tempProject(
            tempDir,
            settings = modules.joinToString("\n") { """include("$it")""" },
        )
        for (file in files) {
            val path = projectDir.resolve(file)
            path.parent.createDirectories()
            path.writeText("rules live here\n")
        }
        return projectDir
    }
}
