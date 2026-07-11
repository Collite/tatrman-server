package org.tatrman.identity.client

import org.tatrman.identity.domain.UserSource
import kotlinx.serialization.Serializable

@Serializable
data class GenericRole(
    val id: String,
    val code: String,
    val description: String? = null,
    val source: UserSource,
)
