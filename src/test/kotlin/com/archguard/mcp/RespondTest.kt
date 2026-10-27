package com.archguard.mcp

import com.archguard.core.ModuleList
import com.archguard.core.ProjectNotFound
import com.archguard.core.UnresolvedDeclaration
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class RespondTest {

    private val moduleList = ModuleList(
        rootProjectName = "golden",
        modules = listOf(":app", ":core:ui"),
        unresolved = listOf(UnresolvedDeclaration("featureModule", "settings.gradle.kts", 7)),
    )

    private val projectNotFound = ProjectNotFound(path = "/tmp/nowhere", reason = "not a directory")

    @Test
    fun `the module list wire format`() {
        val result = respond(moduleList)

        assertEquals(
            """
            {
                "type": "ModuleList",
                "rootProjectName": "golden",
                "modules": [
                    ":app",
                    ":core:ui"
                ],
                "unresolved": [
                    {
                        "declaration": "featureModule",
                        "file": "settings.gradle.kts",
                        "line": 7
                    }
                ]
            }
            """.trimIndent(),
            text(result),
        )
    }

    @Test
    fun `text content and structured content carry the same JSON`() {
        val result = respond(moduleList)

        assertEquals(result.structuredContent, Json.parseToJsonElement(text(result)).jsonObject)
    }

    @Test
    fun `the project not found wire format`() {
        val result = respond(projectNotFound)

        assertEquals(
            """
            {
                "type": "ProjectNotFound",
                "path": "/tmp/nowhere",
                "reason": "not a directory"
            }
            """.trimIndent(),
            text(result),
        )
    }

    @Test
    fun `a failure is an error result`() {
        val result = respond(projectNotFound)

        assertEquals(true, result.isError)
    }

    @Test
    fun `a success is not an error result`() {
        val result = respond(moduleList)

        assertEquals(false, result.isError)
    }

    private fun text(result: CallToolResult): String =
        assertIs<TextContent>(result.content.single()).text
}
