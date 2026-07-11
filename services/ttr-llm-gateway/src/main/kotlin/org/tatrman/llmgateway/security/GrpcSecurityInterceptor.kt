package org.tatrman.llmgateway.security

import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class GrpcSecurityInterceptor(
    @Value("\${security.enabled:false}") private val securityEnabled: Boolean,
    @Value("\${security.api-keys:}") private val apiKeysString: String,
) : ServerInterceptor {
    private val logger = LoggerFactory.getLogger(GrpcSecurityInterceptor::class.java)

    override fun <ReqT : Any, RespT : Any> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        if (!securityEnabled) return next.startCall(call, headers)

        val validApiKeys = apiKeysString.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        val userIdKey = Metadata.Key.of("X-User-ID", Metadata.ASCII_STRING_MARSHALLER)
        val apiKeyMetadataKey = Metadata.Key.of("X-API-Key", Metadata.ASCII_STRING_MARSHALLER)
        val authMetadataKey = Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER)

        val userId = headers.get(userIdKey)
        val apiKey = headers.get(apiKeyMetadataKey)
        val authHeader = headers.get(authMetadataKey)

        logger.info(
            "gRPC request method={} uri={} userId={}",
            call.methodDescriptor.fullMethodName,
            "/",
            userId ?: "none",
        )

        val hasValidApiKey = apiKey != null && validApiKeys.contains(apiKey)
        val hasValidAuth = authHeader != null && authHeader.startsWith("Bearer ")

        if (!hasValidApiKey && !hasValidAuth) {
            call.close(
                Status.UNAUTHENTICATED.withDescription("Missing or invalid API Key / Authorization"),
                headers,
            )
            return object : ServerCall.Listener<ReqT>() {}
        }

        return next.startCall(call, headers)
    }
}
