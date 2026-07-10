package org.tatrman.whois.domain

import kotlinx.serialization.Serializable

@Serializable
data class UserIdRecord(
    val userId: String,
    val userName: String? = null,
    val active: Boolean = true,
    val userRoles: List<String> = emptyList(),
)
