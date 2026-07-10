package org.tatrman.llmgateway.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.context.SecurityContextHolderFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import org.springframework.web.filter.OncePerRequestFilter

class ApiKeyAuthenticationToken(
    val apiKey: String,
) : AbstractAuthenticationToken(AuthorityUtils.createAuthorityList("ROLE_API_CLIENT")) {
    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any = apiKey

    override fun getPrincipal(): Any = "api-client"
}

@Configuration
@EnableWebSecurity
class SecurityConfig(
    @Value("\${security.enabled:false}") private val securityEnabled: Boolean,
    @Value("\${security.api-keys:}") private val apiKeysString: String,
    @Value("\${security.service.name:}") private val serviceName: String,
    @Value("\${security.cors.allowed-hosts:}") private val corsAllowedHosts: String,
) {
    private val logger = LoggerFactory.getLogger(SecurityConfig::class.java)

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val configuration = CorsConfiguration()
        val allowedOrigins =
            corsAllowedHosts
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .flatMap { host ->
                    listOf("https://$host", "http://$host")
                }

        if (allowedOrigins.isNotEmpty()) {
            logger.info("CORS allowed origins: {}", allowedOrigins)
            configuration.allowedOrigins = allowedOrigins
        } else {
            logger.warn("No CORS allowed hosts configured, allowing all origins")
            configuration.allowedOriginPatterns = listOf("*")
        }

        configuration.allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")
        configuration.allowedHeaders = listOf("*")
        configuration.allowCredentials = true

        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", configuration)
        return source
    }

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http.addFilterBefore(
            RequestAuthLoggingFilter(logger),
            SecurityContextHolderFilter::class.java,
        )

        http.cors { it.configurationSource(corsConfigurationSource()) }

        if (!securityEnabled) {
            logger.info("Security is disabled")
            http.authorizeHttpRequests { it.anyRequest().permitAll() }.csrf { it.disable() }
        } else {
            val validApiKeys = apiKeysString.split(",").map { it.trim() }.filter { it.isNotEmpty() }

            http.addFilterBefore(
                object : OncePerRequestFilter() {
                    override fun doFilterInternal(
                        request: HttpServletRequest,
                        response: HttpServletResponse,
                        filterChain: FilterChain,
                    ) {
                        val userId = request.getHeader("X-User-ID")
                        if (userId != null) {
                            request.setAttribute("userId", userId)
                        }

                        val apiKey = request.getHeader("X-API-Key")
                        val authHeader = request.getHeader("Authorization")

                        val hasValidApiKey = apiKey != null && validApiKeys.contains(apiKey)
                        val hasValidAuth = authHeader != null && authHeader.startsWith("Bearer ")

                        if (hasValidApiKey) {
                            SecurityContextHolder.getContext().authentication = ApiKeyAuthenticationToken(apiKey)
                        } else if (!hasValidAuth) {
                            response.status = HttpServletResponse.SC_UNAUTHORIZED
                            response.writer.write("Missing or invalid API Key / Authorization")
                            return
                        }

                        filterChain.doFilter(request, response)
                    }
                },
                UsernamePasswordAuthenticationFilter::class.java,
            )

            http
                .authorizeHttpRequests {
                    it.requestMatchers("/actuator/health").permitAll()
                    it.requestMatchers("/v3/api-docs/**").permitAll()
                    it.requestMatchers("/v3/api-docs.json").permitAll()
                    it.requestMatchers("/swagger-ui/**").permitAll()
                    it.requestMatchers("/error").permitAll()
                    it.anyRequest().authenticated()
                }.csrf { it.disable() }
        }

        return http.build()
    }
}

private class RequestAuthLoggingFilter(
    private val logger: org.slf4j.Logger,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val userId = request.getHeader("X-User-ID")
        request.setAttribute("userId", userId)

        logger.info(
            "request method={} uri={} remoteAddr={} userId={}",
            request.method,
            request.requestURI,
            request.remoteAddr,
            userId ?: "none",
        )

        filterChain.doFilter(request, response)

        val authentication = SecurityContextHolder.getContext().authentication
        if (authentication == null) {
            logger.info("Auth handling: no authentication found for uri={}", request.requestURI)
        } else {
            logger.info(
                "Auth handling: uri={} principal={} authenticated={} authorities={}",
                request.requestURI,
                authentication.name,
                authentication.isAuthenticated,
                authentication.authorities.joinToString(",") { it.authority ?: "" },
            )
        }
    }
}
