// SPDX-License-Identifier: Apache-2.0
package shared.ktor

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.forwardedheaders.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json

fun Application.installKtorServerBase(config: KtorServerConfig) {
    install(ContentNegotiation) {
        json(
            Json {
                prettyPrint = config.jsonConfig.prettyPrint
                isLenient = config.jsonConfig.isLenient
                encodeDefaults = config.jsonConfig.encodeDefaults
                ignoreUnknownKeys = config.jsonConfig.ignoreUnknownKeys
            },
        )
    }

    install(CORS) {
        val defaultSchemes = listOf("http", "https")
        config.corsAllowedHosts.forEach {
            val host = it.trimEnd('/')
            allowHost(host, schemes = defaultSchemes)
            allowHost("$host/", schemes = defaultSchemes)
        }
        config.corsConfig.allowedMethods.forEach { allowMethod(it) }
        config.corsConfig.allowedHeaders.forEach { allowHeader(it) }
        allowNonSimpleContentTypes = config.corsConfig.allowNonSimpleContentTypes
        allowCredentials = config.corsConfig.allowCredentials
    }

    config.callLoggingConfig?.let { callLoggingConfig ->
        install(CallLogging) {
            level = callLoggingConfig.level
        }
    }

    if (config.forwardedHeaderEnabled) {
        install(ForwardedHeaders)
    }
}
