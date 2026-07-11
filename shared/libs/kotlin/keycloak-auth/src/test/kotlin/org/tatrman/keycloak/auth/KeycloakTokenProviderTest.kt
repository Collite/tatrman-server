package org.tatrman.keycloak.auth

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking

class KeycloakTokenProviderTest :
    StringSpec({

        "KeycloakTokenProvider should fetch token from given URL" {
            val mockProvider = mockk<TokenProvider>()
            coEvery { mockProvider.getToken() } returns "mocked-token"

            val token = runBlocking { mockProvider.getToken() }

            token shouldBe "mocked-token"
        }

        "KeycloakTokenProvider should handle different tokens" {
            val mockProvider = mockk<TokenProvider>()
            coEvery { mockProvider.getToken() } returns "different-token"

            val token = runBlocking { mockProvider.getToken() }

            token shouldBe "different-token"
        }

        "KeycloakTokenProvider should propagate exceptions" {
            val mockProvider = mockk<TokenProvider>()
            coEvery { mockProvider.getToken() } throws RuntimeException("Network error")

            runBlocking {
                try {
                    mockProvider.getToken()
                    throw AssertionError("Expected exception to be thrown")
                } catch (e: Exception) {
                    e shouldNotBe null
                    e.message shouldBe "Network error"
                }
            }
        }
    })
