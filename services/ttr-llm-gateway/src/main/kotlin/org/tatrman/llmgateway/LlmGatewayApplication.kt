package org.tatrman.llmgateway

import io.grpc.Status
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.grpc.server.exception.GrpcExceptionHandler
import org.springframework.scheduling.annotation.EnableAsync

@SpringBootApplication @EnableAsync
class LlmGatewayApplication {
    @Bean
    open fun globalInterceptor(): GrpcExceptionHandler =
        GrpcExceptionHandler { exception ->
            when (exception) {
                is IllegalArgumentException -> Status.INVALID_ARGUMENT.withDescription(exception.message).asException()
                else -> null
            }
        }
}

fun main(args: Array<String>) {
    val endpoint = System.getenv("OTEL_EXPORTER_OTLP_HOST") ?: "localhost"
    val protocol = System.getenv("LLM_GATEWAY_OTEL_PROTOCOL")?.lowercase() ?: "grpc"
    val port =
        when (protocol) {
            "https" -> System.getenv("OTEL_EXPORTER_OTLP_HTTPS_PORT") ?: "4319"
            "http" -> System.getenv("OTEL_EXPORTER_OTLP_HTTP_PORT") ?: "4318"
            else -> System.getenv("OTEL_EXPORTER_OTLP_GRPC_PORT") ?: "4317"
        }
    val scheme =
        when (protocol) {
            "https", "grpcs" -> "https"
            else -> "http"
        }
    val otelUrl = "$scheme://$endpoint:$port"
    System.setProperty("otel.exporter.otlp.endpoint", otelUrl)

    runApplication<LlmGatewayApplication>(*args)
}
