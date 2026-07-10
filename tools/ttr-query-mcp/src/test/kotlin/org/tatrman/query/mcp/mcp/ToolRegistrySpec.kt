package org.tatrman.query.mcp.mcp

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import org.tatrman.query.mcp.identity.UserIdentity

class ToolRegistrySpec :
    StringSpec({

        fun stub(toolName: String): McpTool =
            object : McpTool {
                override val name = toolName
                override val description = "stub-$toolName"
                override val inputSchema = ToolSchema(properties = buildJsonObject { }, required = emptyList())

                override suspend fun execute(
                    request: CallToolRequest,
                    identity: UserIdentity?,
                ): CallToolResult = CallToolResult(content = emptyList(), isError = false)
            }

        "registry holds tools and supports get-by-name" {
            val r = ToolRegistry(listOf(stub("query"), stub("compile")))
            r.all().size shouldBe 2
            r.get("query") shouldNotBe null
            r.get("compile") shouldNotBe null
            r.get("nope") shouldBe null
        }

        "duplicate names are rejected at construction" {
            shouldThrow<IllegalArgumentException> {
                ToolRegistry(listOf(stub("query"), stub("query")))
            }
        }

        "execute round-trip works through the registry" {
            val r = ToolRegistry(listOf(stub("query")))
            runBlocking {
                val req = CallToolRequest(params = CallToolRequestParams(name = "query", arguments = null))
                val out = r.get("query")!!.execute(req, identity = null)
                out.isError shouldBe false
            }
        }
    })
