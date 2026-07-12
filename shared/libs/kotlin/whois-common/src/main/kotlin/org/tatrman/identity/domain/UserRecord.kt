// SPDX-License-Identifier: Apache-2.0
package org.tatrman.identity.domain

import kotlinx.serialization.Serializable

@Serializable
data class UserRecord(
    val internalId: Long = 0,
    val email: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val identities: Map<UserSource, UserIdRecord> = emptyMap(),
)

val UserRecord.keycloakId: String?
    get() = identities[UserSource.KEYCLOAK]?.userId

val UserRecord.erpId: String?
    get() = identities[UserSource.ERP]?.userId

val UserRecord.allRoles: List<String>
    get() = identities.values.flatMap { it.userRoles }.distinct()
