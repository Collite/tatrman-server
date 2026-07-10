package org.tatrman.whois.domain

import kotlinx.serialization.Serializable

@Serializable
enum class UserSource {
    KEYCLOAK,
    ERP,
    ENTRAID,
}
