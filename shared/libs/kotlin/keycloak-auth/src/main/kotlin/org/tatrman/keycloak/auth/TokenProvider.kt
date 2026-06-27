package org.tatrman.keycloak.auth

interface TokenProvider {
    suspend fun getToken(): String
}
