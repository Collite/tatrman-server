package shared.ktor.mcp

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.serialization.json.jsonObject

class SafeMcpToolTest :
    StringSpec({
        "timeoutFor returns value from map when key exists" {
            val map = mapOf("fuzzy_match" to 15000L, "entity_query" to 60000L)
            map.timeoutFor("fuzzy_match", 60000L) shouldBe 15000L
        }

        "timeoutFor returns default when key not in map" {
            val map = mapOf("fuzzy_match" to 15000L)
            map.timeoutFor("unknown_tool", 60000L) shouldBe 60000L
        }

        "safeMcpTool returns successful result without modification" {
            var called = false
            val inner: suspend (
                io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest,
            ) -> io.modelcontextprotocol.kotlin.sdk.types.CallToolResult = {
                called = true
                io.modelcontextprotocol.kotlin.sdk.types
                    .CallToolResult(isError = false, content = emptyList())
            }
            val safe = safeMcpTool("test_tool", 60_000, inner)

            val request = mockk<io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest>(relaxed = true)
            every { request.params } returns mockk(relaxed = true)
            every { request.params.name } returns "test_tool"

            val result = kotlinx.coroutines.runBlocking { safe(request) }

            called shouldBe true
            result.isError shouldBe false
        }

        "safeMcpTool returns error result when inner throws exception" {
            val inner: suspend (
                io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest,
            ) -> io.modelcontextprotocol.kotlin.sdk.types.CallToolResult = {
                throw RuntimeException("Database error")
            }
            val safe = safeMcpTool("test_tool", 60_000, inner)

            val request = mockk<io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest>(relaxed = true)
            every { request.params } returns mockk(relaxed = true)
            every { request.params.name } returns "test_tool"

            val result = kotlinx.coroutines.runBlocking { safe(request) }

            result.isError shouldBe true
            result.content.size shouldBe 1
            val textContent = result.content[0] as io.modelcontextprotocol.kotlin.sdk.types.TextContent
            textContent.text shouldBe "Tool 'test_tool' failed: Database error"
        }

        "safeMcpTool returns error result with TIMEOUT errorCode on timeout" {
            val inner: suspend (
                io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest,
            ) -> io.modelcontextprotocol.kotlin.sdk.types.CallToolResult = {
                delay(5000)
                io.modelcontextprotocol.kotlin.sdk.types
                    .CallToolResult(isError = false, content = emptyList())
            }
            val safe = safeMcpTool("slow_tool", 100, inner)

            val request = mockk<io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest>(relaxed = true)
            every { request.params } returns mockk(relaxed = true)
            every { request.params.name } returns "slow_tool"

            val result = kotlinx.coroutines.runBlocking { safe(request) }

            result.isError shouldBe true
            result.content.size shouldBe 1
            val textContent = result.content[0] as io.modelcontextprotocol.kotlin.sdk.types.TextContent
            (textContent.text.indexOf("timed out") >= 0) shouldBe true
            result.structuredContent?.get("errorCode")?.toString() shouldBe "\"TIMEOUT\""
            result.structuredContent
                ?.get("extras")
                ?.jsonObject
                ?.get("tool")
                ?.toString() shouldBe "\"slow_tool\""
        }

        "safeMcpTool propagates IllegalStateException as error result" {
            val inner: suspend (
                io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest,
            ) -> io.modelcontextprotocol.kotlin.sdk.types.CallToolResult = {
                throw IllegalStateException("Unexpected state")
            }
            val safe = safeMcpTool("failing_tool", 60_000, inner)

            val request = mockk<io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest>(relaxed = true)
            every { request.params } returns mockk(relaxed = true)
            every { request.params.name } returns "failing_tool"

            val result = kotlinx.coroutines.runBlocking { safe(request) }

            result.isError shouldBe true
            result.structuredContent?.get("errorCode")?.toString() shouldBe "\"EXECUTION_ERROR\""
            result.structuredContent
                ?.get("extras")
                ?.jsonObject
                ?.get("tool")
                ?.toString() shouldBe "\"failing_tool\""
        }
    })
