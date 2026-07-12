// SPDX-License-Identifier: Apache-2.0
package org.tatrman.identity.domain

import kotlinx.serialization.Serializable

@Serializable
data class UserIdRecord(
    val userId: String,
    val userName: String? = null,
    val active: Boolean = true,
    val userRoles: List<String> = emptyList(),
)
