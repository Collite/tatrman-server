package org.tatrman.kantheon.echo.mcp

import io.kotest.core.spec.style.StringSpec
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.fuzzy.common.FuzzyMatch
import org.fuzzy.common.FuzzyMatchResponse
import org.tatrman.kantheon.echo.mcp.client.EchoClient

class ToolsTest :
    StringSpec({
        val mockClient = mockk<EchoClient>()
        val tools = Tools(mockClient, mockk())

        "matchCallback returns error when name is missing" {
            val request = mockk<io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest>()
            coEvery { request.arguments } returns null

            val result = tools.matchCallback(request)

            assert(result.isError == true) { "Expected isError=true but was ${result.isError}" }
            assert(result.content.isNotEmpty()) { "Expected content to be non-empty" }
            val textContent = result.content[0] as io.modelcontextprotocol.kotlin.sdk.types.TextContent
            assert(textContent.text == "Missing required argument: name") {
                "Expected 'Missing required argument: name' but got '${textContent.text}'"
            }
        }

        "matchCallback returns error when name is blank" {
            val request = mockk<io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest>()
            coEvery { request.arguments } returns buildJsonObject { }

            val result = tools.matchCallback(request)

            assert(result.isError == true) { "Expected isError=true but was ${result.isError}" }
            assert(result.content.isNotEmpty()) { "Expected content to be non-empty" }
            val textContent = result.content[0] as io.modelcontextprotocol.kotlin.sdk.types.TextContent
            assert(textContent.text == "Missing required argument: name") {
                "Expected 'Missing required argument: name' but got '${textContent.text}'"
            }
        }

        "matchCallback returns successful result with matches" {
            val request = mockk<io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest>()
            coEvery { request.arguments } returns
                buildJsonObject {
                    put("name", JsonPrimitive("test"))
                }
            coEvery { mockClient.match(any(), any(), any(), any()) } returns
                FuzzyMatchResponse(
                    matches =
                        listOf(
                            FuzzyMatch(candidateId = "1", candidate = "test", score = 0.95, category = "customer"),
                        ),
                    isError = false,
                )

            val result = tools.matchCallback(request)

            assert(result.isError != true) { "Expected isError != true but was ${result.isError}" }
            assert(result.content.isNotEmpty()) { "Expected content to be non-empty" }
            val textContent = result.content[0] as? io.modelcontextprotocol.kotlin.sdk.types.TextContent
            assert(textContent != null) { "Expected TextContent but was null" }
            val text = textContent?.text ?: ""
            assert(text.contains("Match 1")) { "Expected text to contain 'Match 1' but was '$text'" }
        }

        "matchCallback returns error response when client returns error" {
            val request = mockk<io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest>()
            coEvery { request.arguments } returns
                buildJsonObject {
                    put("name", JsonPrimitive("test"))
                }
            coEvery { mockClient.match(any(), any(), any(), any()) } returns
                FuzzyMatchResponse(
                    isError = true,
                    error = "Downstream error",
                )

            val result = tools.matchCallback(request)

            assert(result.isError == true) { "Expected isError=true but was ${result.isError}" }
            assert(result.content.isNotEmpty()) { "Expected content to be non-empty" }
            val textContent = result.content[0] as? io.modelcontextprotocol.kotlin.sdk.types.TextContent
            assert(textContent != null) { "Expected TextContent but was null" }
            val text = textContent?.text ?: ""
            assert(text.contains("Error")) { "Expected text to contain 'Error' but was '$text'" }
        }

        "matchCallback returns not-wired error when client is null" {
            // Stage 2.2 R2.3 — when echo.client.host is blank (local no-
            // backend mode), Application passes `null` to Tools; the tool
            // must surface a clear error, not crash on the null deref.
            val unwired = Tools(null, mockk())
            val request = mockk<io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest>()
            coEvery { request.arguments } returns
                buildJsonObject {
                    put("name", JsonPrimitive("anything"))
                }

            val result = unwired.matchCallback(request)

            assert(result.isError == true) { "Expected isError=true but was ${result.isError}" }
            val textContent = result.content[0] as io.modelcontextprotocol.kotlin.sdk.types.TextContent
            assert(textContent.text.contains("not wired")) {
                "Expected 'not wired' but got '${textContent.text}'"
            }
        }
    })
