// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.config

import kotlinx.serialization.json.Json
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebConfig : WebMvcConfigurer {
    @Bean
    @Primary
    fun kotlinxSerializationJson(): Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

    override fun addCorsMappings(registry: CorsRegistry) {
        // Read allowed origins from env var (comma-separated) with localhost fallback
        val corsHosts =
            System
                .getenv("LLM_GATEWAY_CORS_ALLOWED_HOSTS")
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: listOf("localhost:5173", "localhost:7010")

        val allowedOriginPatterns =
            corsHosts.flatMap { host ->
                listOf("http://$host", "https://$host")
            }

        registry
            .addMapping("/**")
            .allowedMethods(
                "GET",
                "POST",
                "PUT",
                "DELETE",
                "PATCH",
                "OPTIONS",
            ).allowedHeaders(
                "*",
            ).allowCredentials(true)
            .allowedOriginPatterns(*allowedOriginPatterns.toTypedArray())
    }
}
