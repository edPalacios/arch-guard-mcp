package com.archguard.mcp

import com.archguard.core.ArchGuard
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Path
import java.nio.file.Paths

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
    registerGetModuleGraph(server)

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
        respond(ArchGuard.listModules(request.projectDir()))
    }
}

private fun registerGetModuleGraph(server: Server) {
    server.addTool(
        name = "get_module_graph",
        title = "Get module dependencies",
        description = "Reads the build.gradle.kts files of a Gradle project and returns, for " +
            "each module, the other modules it depends on directly: target module, " +
            "configuration, file and line of the declaration. Only implementation, api and " +
            "compileOnly with project(\":x\") or projects.x.y count; test configurations and " +
            "transitive dependencies are out of scope. Use 'module' to focus on one module " +
            "(\":feature:orders:history\") or on a group of them with a pattern, where '*' " +
            "matches one path segment and '**' any number (\":feature:**\"); omit it for every " +
            "module. Declarations that only Gradle could resolve (variables, string templates) " +
            "come back under 'unresolved', and calls to the project's own dependency helpers " +
            "are counted by name under 'unanalysedHelpers': the modules those add are a blind " +
            "spot, so read the helper to learn what it declares. Static analysis only: Gradle " +
            "is not executed.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("projectPath") {
                    put("type", "string")
                    put("description", "Absolute path to the root directory of the Gradle project")
                }
                putJsonObject("module") {
                    put("type", "string")
                    put(
                        "description",
                        "Module path or pattern to report on, such as ':core:ui' or " +
                            "':feature:**'. Omit for every module of the build.",
                    )
                }
            },
            required = listOf("projectPath"),
        ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
    ) { request ->
        respond(ArchGuard.moduleGraph(request.projectDir(), request.module()))
    }
}

/** The analysed project's root directory, from the required `projectPath` argument. */
private fun CallToolRequest.projectDir(): Path {
    val raw = arguments?.get("projectPath")?.jsonPrimitive?.contentOrNull
    require(!raw.isNullOrBlank()) { "missing required argument 'projectPath'" }
    return Paths.get(raw)
}

/** The module path or pattern to report on, from the optional `module` argument. */
private fun CallToolRequest.module(): String? =
    arguments?.get("module")?.jsonPrimitive?.contentOrNull
