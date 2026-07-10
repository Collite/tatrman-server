package org.tatrman.identity.client

import org.tatrman.identity.domain.UserSource

interface UserSourceClient {
    val source: UserSource

    suspend fun fetchUsers(): List<GenericUser>

    suspend fun fetchRoles(): List<GenericRole>

    suspend fun fetchRoleHierarchy(): List<Pair<String, String>>

    suspend fun fetchUserRoleMappings(): List<Pair<String, String>>
}
