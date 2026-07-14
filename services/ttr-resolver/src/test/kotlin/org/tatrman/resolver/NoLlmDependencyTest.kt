// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * RG-P5 — the phase's ONE rule made mechanical (RS-23): the resolver module has
 * ZERO LLM. The deterministic core never calls an LLM; the escalation ladder is
 * the kantheon Resolving Agent's job.
 *
 * This is the RUNTIME backstop; the primary guard is the `verifyNoLlmDependency`
 * Gradle task (wired into `check`), which fails the build if the resolver's
 * runtimeClasspath resolves any LLM client artifact. Here we assert the same at
 * class-load time: no llm-gateway CLIENT and no known external LLM SDK is on the
 * classpath, and no forbidden jar is present.
 *
 * KNOWN RESIDUAL: the generated `org.tatrman.llm.v1.*` gRPC stub (a callable
 * client) still rides in via the monolithic `:shared:proto`. We cannot forbid it
 * without splitting the llm service into its own proto module (the real fix). So
 * this test guards the *dependency* dimension (no caller module / SDK); the stub's
 * presence is a structural gap called out in the module's build file.
 */
class NoLlmDependencyTest :
    StringSpec({

        // In-house llm-gateway caller classes + external LLM SDK entrypoints. None
        // of these may be loadable from the resolver's classpath.
        val forbiddenClasses =
            listOf(
                "org.tatrman.llm.client.LlmGatewayClient",
                "org.tatrman.llm.client.LlmGatewayPromptExecutor",
                "com.openai.client.OpenAIClient",
                "com.theokanning.openai.service.OpenAiService",
                "com.anthropic.client.AnthropicClient",
                "dev.langchain4j.model.chat.ChatLanguageModel",
            )

        // Jar / classpath-entry name fragments that betray an LLM client dependency.
        val forbiddenJarFragments =
            listOf("ttr-llm-client", "openai", "anthropic", "langchain4j", "langchain", "theokanning")

        "no llm-gateway client or external LLM SDK class is loadable" {
            val loadable =
                forbiddenClasses.filter { cls ->
                    runCatching { Class.forName(cls, false, this::class.java.classLoader) }.isSuccess
                }
            loadable shouldBe emptyList()
        }

        "no LLM client jar is on the classpath" {
            val entries = System.getProperty("java.class.path").orEmpty().split(java.io.File.pathSeparator)
            val hits =
                entries.filter { entry ->
                    val name = entry.substringAfterLast(java.io.File.separatorChar).lowercase()
                    forbiddenJarFragments.any { name.contains(it) }
                }
            hits shouldBe emptyList()
        }
    })
