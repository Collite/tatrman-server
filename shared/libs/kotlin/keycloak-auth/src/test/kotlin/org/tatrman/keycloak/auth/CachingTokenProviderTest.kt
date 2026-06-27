package org.tatrman.keycloak.auth

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking

class CachingTokenProviderTest :
    StringSpec({

        "CachingTokenProvider should return cached token on subsequent calls" {
            val mockProvider = mockk<TokenProvider>()
            coEvery { mockProvider.getToken() } returns "test-token-123"

            val cachingProvider =
                CachingTokenProvider.create(
                    delegate = mockProvider,
                    bufferSeconds = 30,
                    expiresInSeconds = 300,
                )

            val token1 = runBlocking { cachingProvider.getToken() }
            val token2 = runBlocking { cachingProvider.getToken() }

            token1 shouldBe "test-token-123"
            token2 shouldBe "test-token-123"
        }

        "CachingTokenProvider should fetch new token when cache is empty" {
            var callCount = 0
            val mockProvider = mockk<TokenProvider>()
            coEvery { mockProvider.getToken() } answers {
                callCount++
                "token-$callCount"
            }

            val cachingProvider =
                CachingTokenProvider.create(
                    delegate = mockProvider,
                    bufferSeconds = 30,
                    expiresInSeconds = 300,
                )

            val token1 = runBlocking { cachingProvider.getToken() }
            val token2 = runBlocking { cachingProvider.getToken() }

            token1 shouldBe "token-1"
            token2 shouldBe "token-1"
        }

        "CachingTokenProvider should use default buffer and expires values" {
            val mockProvider = mockk<TokenProvider>()
            coEvery { mockProvider.getToken() } returns "default-token"

            val cachingProvider =
                CachingTokenProvider.create(
                    delegate = mockProvider,
                )

            val token = runBlocking { cachingProvider.getToken() }

            token shouldBe "default-token"
        }

        "CachingTokenProvider should handle different buffer and expires values" {
            val mockProvider = mockk<TokenProvider>()
            coEvery { mockProvider.getToken() } returns "custom-token"

            val cachingProvider =
                CachingTokenProvider.create(
                    delegate = mockProvider,
                    bufferSeconds = 60,
                    expiresInSeconds = 600,
                )

            val token = runBlocking { cachingProvider.getToken() }

            token shouldBe "custom-token"
        }

        "CachingTokenProvider should throw when delegate throws" {
            val mockProvider = mockk<TokenProvider>()
            coEvery { mockProvider.getToken() } throws RuntimeException("Token fetch failed")

            val cachingProvider =
                CachingTokenProvider.create(
                    delegate = mockProvider,
                )

            runBlocking {
                try {
                    cachingProvider.getToken()
                    throw AssertionError("Expected exception to be thrown")
                } catch (e: Exception) {
                    e shouldNotBe null
                    e.message shouldBe "Token fetch failed"
                }
            }
        }
    })
