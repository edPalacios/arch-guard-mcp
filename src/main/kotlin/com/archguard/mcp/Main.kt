package com.archguard.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private val prettyJson = Json { prettyPrint = true }

fun main() {
    // stdout is the JSON-RPC channel. Keep a handle to it for the transport and send every
    // other System.out write (e.g. kotlin-logging's startup println) to stderr instead.
    val protocolOut = System.out
    System.setOut(System.err)
    System.setProperty("kotlin-logging.logStartupMessage", "false")

    val server = Server(
        serverInfo = Implementation(name = "arch-guard-mcp", version = "0.1.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
        ),
    )
    registerListModules(server)

    val transport = StdioServerTransport(
        input = System.`in`.asSource().buffered(),
        output = protocolOut.asSink().buffered(),
    )

    runBlocking {
        val session = server.createSession(transport)
        val done = Job()
        session.onClose { done.complete() }
        done.join()
    }
}

private fun registerListModules(server: Server) {
    server.addTool(
        name = "list_modules",
        title = "List Gradle modules",
        description = "Reads settings.gradle.kts of a Gradle project and returns the modules " +
            "declared with include(...). Static analysis only: Gradle is not executed.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("projectPath") {
                    put("type", "string")
                    put("description", "Absolute path to the root directory of the Gradle project")
                }
            },
            required = listOf("projectPath"),
        ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
    ) { request ->
        val rawPath = request.arguments?.get("projectPath")?.jsonPrimitive?.contentOrNull
            ?: return@addTool error("Missing required argument 'projectPath'")

        val settingsFile = resolveSettingsFile(Paths.get(rawPath))
            ?: return@addTool error("No settings.gradle.kts found in '$rawPath'")

        val info = SettingsParser.parse(Files.readString(settingsFile))
        val payload = buildJsonObject {
            put("settingsFile", settingsFile.toString())
            put("rootProjectName", JsonPrimitive(info.rootProjectName))
            putJsonArray("modules") { info.modules.forEach { add(it) } }
            putJsonArray("unresolved") { info.unresolved.forEach { add(it) } }
        }
        CallToolResult(
            content = listOf(TextContent(prettyJson.encodeToString(payload))),
            structuredContent = payload,
        )
    }
}

private fun resolveSettingsFile(projectPath: Path): Path? {
    val path = projectPath.toAbsolutePath().normalize()
    return when {
        Files.isRegularFile(path) && path.fileName.toString() == "settings.gradle.kts" -> path
        Files.isDirectory(path) -> path.resolve("settings.gradle.kts").takeIf { Files.isRegularFile(it) }
        else -> null
    }
}

private fun error(message: String) = CallToolResult(content = listOf(TextContent(message)), isError = true)
