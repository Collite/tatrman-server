package org.tatrman.keycloak.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TokenResponse(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("expires_in")
    val expiresIn: Int,
    @SerialName("refresh_expires_in")
    val refreshExpiresIn: Int = 0,
    @SerialName("token_type")
    val tokenType: String = "Bearer",
    @SerialName("not-before-policy")
    val notBeforePolicy: Int = 0,
    val scope: String = "",
)
