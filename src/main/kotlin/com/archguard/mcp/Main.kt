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
import kotlinx.serialization.json.JsonObjectBuilder
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
    registerCheckArchitecture(server)

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
                putProjectPath()
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
        description = "Reads the build.gradle.kts files of a Gradle project and returns how its " +
            "modules depend on each other. Only implementation, api and compileOnly with " +
            "project(\":x\") or projects.x.y count; test configurations and transitive " +
            "dependencies are out of scope. Without 'module': the per-layer summary of the " +
            "whole build, which needs archguard.json in the project root — the modules of each " +
            "layer, the layer-to-layer matrix with a count and an 'allowed' flag per pair, the " +
            "modules in no layer, and the config itself, so hypothetical questions about the " +
            "layers can be answered from this one answer. With 'module': the detailed " +
            "dependencies of the selected modules (target module, configuration, file and " +
            "line), which needs no config, plus the declarations only Gradle could resolve " +
            "(variables, string templates) under 'unresolved'. Either way, calls to the " +
            "project's own dependency helpers are counted by name under 'unanalysedHelpers': " +
            "the modules those add are a blind spot, so read the helper to learn what it " +
            "declares. Static analysis only: Gradle is not executed.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putProjectPath()
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

private fun registerCheckArchitecture(server: Server) {
    server.addTool(
        name = "check_architecture",
        title = "Check the module dependencies against the architecture rules",
        description = "Checks every module dependency of a Gradle project against the layers " +
            "and rules of archguard.json in its root, and returns the dependencies no rule " +
            "allows under 'violations': source and target module, the layer of each, the file " +
            "and line that declares it, and the source layer's rule, so the fix is visible " +
            "without reading the config again. Also what the check could not cover: 'unassigned' " +
            "modules match no layer and are nobody's violation (give them a layer or exclude " +
            "them in the config), 'unresolved' declarations are module inclusions or " +
            "dependencies only Gradle could resolve (variables, string templates), so the " +
            "modules behind them went unjudged, and 'unanalysedHelpers' counts calls " +
            "to the project's own dependency helpers by name — the modules those add are a " +
            "blind spot, so read the helper to learn what it declares. 'warnings' reports what " +
            "the config claims and the build does not bear out, such as a layer matching no " +
            "module; 'counts' sizes up the modules and dependencies checked. An empty " +
            "'violations' list with empty 'unresolved' and 'unanalysedHelpers' means the whole " +
            "build was checked and holds. Without archguard.json the call is an error carrying " +
            "the expected format and a proposed config to review. Use get_module_graph for the " +
            "layer summary or for one module's dependencies. Static analysis only: Gradle is " +
            "not executed.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putProjectPath()
            },
            required = listOf("projectPath"),
        ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
    ) { request ->
        respond(ArchGuard.checkArchitecture(request.projectDir()))
    }
}

/** The `projectPath` property every tool's input schema requires, described once. */
private fun JsonObjectBuilder.putProjectPath() = putJsonObject("projectPath") {
    put("type", "string")
    put("description", "Absolute path to the root directory of the Gradle project")
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
