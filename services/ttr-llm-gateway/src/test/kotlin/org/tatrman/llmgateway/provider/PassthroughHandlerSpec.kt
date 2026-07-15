// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.provider

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.llmgateway.wire.ChatRequest

/**
 * LG-P2·S1·T1 (a–d, deterministic via MockEngine): URL construction + auth-header FORM per flavor +
 * the request body reaching the upstream with `model` rewritten to `upstream`, an unknown future param
 * preserved, and the gateway-only `model_tags` stripped. Key is passed per call (C-5) — proven by
 * injecting distinct values.
 */
class PassthroughHandlerSpec :
    StringSpec({

        val okBody =
            """{"id":"cmpl-x","choices":[{"index":0,"finish_reason":"stop","message":{"content":"ok"}}],"usage":{"prompt_tokens":3,"completion_tokens":2}}"""

        fun capturing(onRequest: (HttpRequestData) -> Unit): PassthroughHandler {
            val engine =
                MockEngine { request ->
                    onRequest(request)
                    respond(
                        okBody,
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            return PassthroughHandler(HttpClient(engine))
        }

        fun bodyOf(req: HttpRequestData) = (req.body as TextContent).text

        "azure: deployment URL rewrite + api-key header; model→upstream, unknown param kept, model_tags stripped" {
            var url = ""
            var apiKey: String? = null
            var body = ""
            val handler =
                capturing { r ->
                    url = r.url.toString()
                    apiKey = r.headers["api-key"]
                    body = bodyOf(r)
                }
            val azure =
                UpstreamTarget(
                    "azure",
                    "openai-wire",
                    "https://azure.test",
                    "tatrman-gpt-4.1",
                    "/openai/deployments/{upstream}/{path}?api-version={apiVersion}",
                    "2024-10-21",
                    "api-key",
                    null,
                )

            val result =
                runBlocking {
                    handler.complete(
                        ChatRequest.parse(
                            """{"model":"gpt-4.1","model_tags":["smart"],"verbosity":"high","messages":[{"role":"user","content":"hi"}]}""",
                        ),
                        azure,
                        Key("azure-secret"),
                    )
                }

            url shouldBe "https://azure.test/openai/deployments/tatrman-gpt-4.1/chat/completions?api-version=2024-10-21"
            apiKey shouldBe "azure-secret" // api-key header form (no scheme)

            val sent = Json.parseToJsonElement(body).jsonObject
            sent["model"]!!.jsonPrimitive.content shouldBe "tatrman-gpt-4.1" // rewritten to upstream
            sent["verbosity"]!!.jsonPrimitive.content shouldBe "high" // unknown future param forwarded
            sent.containsKey("model_tags") shouldBe false // gateway-only hint stripped

            result.status shouldBe 200
            result.usage shouldBe UpstreamUsage(3, 2)
            result.finishReason shouldBe "stop"
        }

        "openai: /v1 path + Authorization: Bearer header form" {
            var url = ""
            var auth: String? = null
            val handler =
                capturing { r ->
                    url = r.url.toString()
                    auth = r.headers[HttpHeaders.Authorization]
                }
            val openai =
                UpstreamTarget(
                    "openai",
                    "openai-wire",
                    "https://api.openai.test",
                    "gpt-4o",
                    "/v1/{path}",
                    null,
                    "Authorization",
                    "Bearer",
                )

            runBlocking {
                handler.complete(ChatRequest.parse("""{"model":"gpt-4o","messages":[]}"""), openai, Key("sk-123"))
            }

            url shouldBe "https://api.openai.test/v1/chat/completions"
            auth shouldBe "Bearer sk-123" // scheme + key
        }
    })
