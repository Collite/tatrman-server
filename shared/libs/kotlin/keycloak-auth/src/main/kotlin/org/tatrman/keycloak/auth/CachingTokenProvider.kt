// SPDX-License-Identifier: Apache-2.0
package org.tatrman.keycloak.auth

import com.github.benmanes.caffeine.cache.Caffeine
import org.slf4j.LoggerFactory
import java.time.Duration

class CachingTokenProvider(
    private val delegate: TokenProvider,
    private val effectiveTtlMillis: Long,
) : TokenProvider {
    private val logger = LoggerFactory.getLogger(CachingTokenProvider::class.java)
    private val cacheInstance =
        Caffeine
            .newBuilder()
            .expireAfterWrite(Duration.ofMillis(effectiveTtlMillis))
            .build<String, CachedToken>()

    override suspend fun getToken(): String {
        val cached = cacheInstance.getIfPresent(CACHE_KEY)
        if (cached != null && !cached.isExpired()) {
            logger.debug("Returning cached token")
            return cached.token
        }
        logger.debug("Token cache miss or expired, fetching new token")
        val token = delegate.getToken()
        cacheInstance.put(CACHE_KEY, CachedToken(token, System.currentTimeMillis() + effectiveTtlMillis))
        return token
    }

    data class CachedToken(
        val token: String,
        val expiresAtMillis: Long,
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() >= expiresAtMillis
    }

    companion object {
        private const val CACHE_KEY = "keycloak-token"
        private const val DEFAULT_EXPIRES_IN_SECONDS = 300L
        private const val DEFAULT_BUFFER_SECONDS = 30L

        @JvmStatic
        fun create(
            delegate: TokenProvider,
            bufferSeconds: Long = DEFAULT_BUFFER_SECONDS,
            expiresInSeconds: Long = DEFAULT_EXPIRES_IN_SECONDS,
        ): CachingTokenProvider {
            val effectiveTtlSeconds = maxOf(1L, expiresInSeconds - bufferSeconds)
            val effectiveTtlMillis = effectiveTtlSeconds * 1000
            return CachingTokenProvider(delegate, effectiveTtlMillis)
        }
    }
}
