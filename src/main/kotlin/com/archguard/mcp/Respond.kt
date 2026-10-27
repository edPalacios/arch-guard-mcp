package com.archguard.mcp

import com.archguard.core.Answer
import com.archguard.core.Failure
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

private val answerJson = Json { prettyPrint = true }

/**
 * Turns an [Answer] into the tool result: the same JSON as text and as structured content, so
 * clients that read either see one wire format, and a [Failure] marks the result as an error.
 */
internal fun respond(answer: Answer): CallToolResult {
    val payload = answerJson.encodeToJsonElement<Answer>(answer).jsonObject
    return CallToolResult(
        content = listOf(TextContent(answerJson.encodeToString(payload))),
        structuredContent = payload,
        isError = answer is Failure,
    )
}
