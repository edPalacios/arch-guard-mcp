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

/** The analysed project's root directory, from the required `projectPath` argument. */
private fun CallToolRequest.projectDir(): Path {
    val raw = arguments?.get("projectPath")?.jsonPrimitive?.contentOrNull
    require(!raw.isNullOrBlank()) { "missing required argument 'projectPath'" }
    return Paths.get(raw)
}
