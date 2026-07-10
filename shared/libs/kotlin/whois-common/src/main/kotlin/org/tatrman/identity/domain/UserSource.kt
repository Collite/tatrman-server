package org.tatrman.identity.domain

import kotlinx.serialization.Serializable

@Serializable
enum class UserSource {
    KEYCLOAK,
    ERP,
    ENTRAID,
}
