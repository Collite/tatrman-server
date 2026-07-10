package org.tatrman.nlp.mcp

import io.kotest.core.spec.style.StringSpec
import io.mockk.coEvery
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tatrman.nlp.mcp.client.KadmosAnalyzeResult
import org.tatrman.nlp.mcp.client.KadmosClient
import org.tatrman.nlp.mcp.client.KadmosClientException
import org.tatrman.nlp.mcp.client.KadmosToken

class ToolsTest :
    StringSpec({
        val mockClient = mockk<KadmosClient>()
        val tools = Tools(mockClient, mockk())

        fun analyzeRequest(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): CallToolRequest {
            val request = mockk<CallToolRequest>()
            coEvery { request.arguments } returns buildJsonObject(build)
            return request
        }

        "analyzeCallback returns error when text is missing" {
            val request = mockk<CallToolRequest>()
            coEvery { request.arguments } returns null

            val result = tools.analyzeCallback(request)

            assert(result.isError == true) { "Expected isError=true but was ${result.isError}" }
            val text = (result.content[0] as TextContent).text
            assert(text == "Missing required argument: text") { "got '$text'" }
        }

        "analyzeCallback returns error when ops is missing" {
            val request = analyzeRequest { put("text", JsonPrimitive("Ahoj světe")) }

            val result = tools.analyzeCallback(request)

            assert(result.isError == true) { "Expected isError=true but was ${result.isError}" }
            val text = (result.content[0] as TextContent).text
            assert(text.contains("Missing required argument: ops")) { "got '$text'" }
        }

        "analyzeCallback returns not-wired error when client is null" {
            val unwired = Tools(null, mockk())
            val request =
                analyzeRequest {
                    put("text", JsonPrimitive("Ahoj světe"))
                    put("ops", JsonArray(listOf(JsonPrimitive("LEMMATIZE"))))
                }

            val result = unwired.analyzeCallback(request)

            assert(result.isError == true) { "Expected isError=true but was ${result.isError}" }
            val text = (result.content[0] as TextContent).text
            assert(text.contains("not wired")) { "Expected 'not wired' but got '$text'" }
        }

        "analyzeCallback returns formatted result on success" {
            val request =
                analyzeRequest {
                    put("text", JsonPrimitive("Ahoj světe"))
                    put("ops", JsonArray(listOf(JsonPrimitive("LEMMATIZE"))))
                }
            coEvery { mockClient.analyze(any(), any(), any(), any(), any()) } returns
                KadmosAnalyzeResult(
                    language = "cs",
                    languageConfidence = 0.99,
                    engineUsed = "morphodita",
                    tokens =
                        listOf(
                            KadmosToken(
                                text = "světe",
                                charStart = 5,
                                charEnd = 10,
                                lemma = "svět",
                                upos = "NOUN",
                                xpos = "NNIS5",
                                feats = emptyMap(),
                                depHead = 0,
                                depRelation = "root",
                            ),
                        ),
                    sentences = emptyList(),
                    paragraphs = emptyList(),
                    entities = emptyList(),
                    traceId = "t-1",
                    elapsedMs = 12,
                    messages = emptyList(),
                )

            val result = tools.analyzeCallback(request)

            assert(result.isError != true) { "Expected isError != true but was ${result.isError}" }
            val text = (result.content[0] as TextContent).text
            assert(text.contains("Language: cs")) { "got '$text'" }
            assert(text.contains("lemma=svět")) { "got '$text'" }
        }

        "analyzeCallback returns error response when client throws" {
            val request =
                analyzeRequest {
                    put("text", JsonPrimitive("Ahoj"))
                    put("ops", JsonArray(listOf(JsonPrimitive("LEMMATIZE"))))
                }
            coEvery { mockClient.analyze(any(), any(), any(), any(), any()) } throws
                KadmosClientException("Downstream error")

            val result = tools.analyzeCallback(request)

            assert(result.isError == true) { "Expected isError=true but was ${result.isError}" }
            val text = (result.content[0] as TextContent).text
            assert(text.contains("Error")) { "got '$text'" }
        }
    })
