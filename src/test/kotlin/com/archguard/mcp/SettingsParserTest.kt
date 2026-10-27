package com.archguard.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsParserTest {

    @Test
    fun `parses a typical modularised Android settings file`() {
        val source = """
            pluginManagement {
                repositories {
                    google()
                    maven("https://plugins.gradle.org/m2/") // a URL must not be treated as a comment
                }
            }
            rootProject.name = "MyApp"

            include(":app")
            include(
                ":core:domain",
                ":core:data",   // trailing comment
                ":feature:login",
            )
            /* include(":legacy") */
            // include(":disabled")
            includeBuild("build-logic")
            include("feature:profile")
        """.trimIndent()

        val info = SettingsParser.parse(source)

        assertEquals("MyApp", info.rootProjectName)
        assertEquals(
            listOf(":app", ":core:domain", ":core:data", ":feature:login", ":feature:profile"),
            info.modules,
        )
        assertTrue(info.unresolved.isEmpty())
    }

    @Test
    fun `deduplicates while keeping declaration order`() {
        val info = SettingsParser.parse("""include(":b", ":a")
include(":b")""")

        assertEquals(listOf(":b", ":a"), info.modules)
    }

    @Test
    fun `reports non literal arguments as unresolved`() {
        val source = """
            val name = "x"
            include(":core", ":feature:${'$'}name", featureModule)
        """.trimIndent()

        val info = SettingsParser.parse(source)

        assertEquals(listOf(":core"), info.modules)
        assertEquals(listOf("\":feature:${'$'}name\"", "featureModule"), info.unresolved)
        assertNull(info.rootProjectName)
    }

    @Test
    fun `returns empty result for a single-module project`() {
        val info = SettingsParser.parse("""rootProject.name = "solo"""")

        assertEquals("solo", info.rootProjectName)
        assertTrue(info.modules.isEmpty())
    }
}
