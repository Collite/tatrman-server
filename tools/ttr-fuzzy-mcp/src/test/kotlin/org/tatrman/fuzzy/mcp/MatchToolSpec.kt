package org.tatrman.fuzzy.mcp

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.fuzzy.common.FuzzyMatch
import org.fuzzy.common.FuzzyMatchResponse

/**
 * Stage 2.2 T6 — wire-level assertions on the `match` MCP tool. The
 * cascade selection is exposed as tool args (not separate tools per
 * contracts §2), so a single `match` invocation covers the full
 * LEVENSHTEIN / TATRMAN / JARO_WINKLER surface.
 */
class MatchToolSpec :
    StringSpec({

        "match tool has the right name + description" {
            val tools = Tools(mockk(relaxed = true), mockk())
            tools.matchTool.name shouldBe "match"
            tools.matchTool.description shouldNotBe null
        }

        "matchCallback surfaces structured content (matches[]) on success" {
            val mockClient = mockk<org.tatrman.fuzzy.mcp.client.FuzzyClient>()
            coEvery { mockClient.match(any(), any(), any(), any()) } returns
                FuzzyMatchResponse(
                    matches =
                        listOf(
                            FuzzyMatch(
                                candidateId = "P004",
                                candidate = "Bezový sirup",
                                score = 0.92,
                                category = "product",
                            ),
                        ),
                    isError = false,
                )
            val tools = Tools(mockClient, mockk())
            val request = mockk<io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest>()
            coEvery { request.arguments } returns
                buildJsonObject {
                    put("name", JsonPrimitive("Bezov"))
                    put("category", JsonPrimitive("product"))
                }

            val result = tools.matchCallback(request)
            result.isError shouldNotBe true
            val sc = result.structuredContent
            sc shouldNotBe null
            // The structuredContent is the lib's FuzzyMatchResponse
            // serialised — so the top-level key is `matches` (a JSON array).
            val matches = sc!!["matches"]?.jsonArray
            matches shouldNotBe null
            val first = matches!!.firstOrNull()?.jsonObject
            first shouldNotBe null
            first!!["candidateId"]?.jsonPrimitive?.content shouldBe "P004"
            first["candidate"]?.jsonPrimitive?.content shouldBe "Bezový sirup"
            first["category"]?.jsonPrimitive?.content shouldBe "product"
        }

        "matchCallback passes through category / name / algorithm / limit args" {
            // FuzzyClient.match signature: (category, name, algorithm, limit)
            val mockClient = mockk<org.tatrman.fuzzy.mcp.client.FuzzyClient>()
            var capturedCategory = ""
            var capturedName = ""
            var capturedAlgorithm = ""
            var capturedLimit = 0
            coEvery { mockClient.match(any(), any(), any(), any()) } answers {
                capturedCategory = firstArg()
                capturedName = secondArg()
                capturedAlgorithm = thirdArg()
                capturedLimit = arg<Int>(3)
                FuzzyMatchResponse(matches = emptyList(), isError = false)
            }
            val tools = Tools(mockClient, mockk())
            val request = mockk<io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest>()
            coEvery { request.arguments } returns
                buildJsonObject {
                    put("name", JsonPrimitive("Acme"))
                    put("category", JsonPrimitive("customer"))
                    put("algorithm", JsonPrimitive("JARO_WINKLER"))
                    put("limit", JsonPrimitive(5))
                }

            tools.matchCallback(request)
            capturedCategory shouldBe "customer"
            capturedName shouldBe "Acme"
            capturedAlgorithm shouldBe "JARO_WINKLER"
            capturedLimit shouldBe 5
        }
    })
