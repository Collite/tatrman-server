package org.tatrman.whois.repository

import org.tatrman.whois.client.GenericRole
import org.tatrman.whois.domain.UserIdRecord
import org.tatrman.whois.domain.UserRecord
import org.tatrman.whois.domain.UserSource

interface UserRepositoryPort {
    suspend fun findByInternalId(id: Long): UserRecord?

    suspend fun findByUserId(
        type: UserSource,
        userId: String,
    ): UserRecord?

    suspend fun findByEmail(email: String): UserRecord?

    suspend fun searchById(id: String): List<UserRecord>

    suspend fun upsertUser(user: UserRecord): Long

    suspend fun setIdentity(
        internalUserId: Long,
        type: UserSource,
        identity: UserIdRecord,
    )

    suspend fun setRoles(
        internalUserId: Long,
        type: UserSource,
        userId: String,
        roles: List<String>,
    )

    suspend fun getAllUsers(): List<UserRecord>

    suspend fun getRoleHierarchy(sourceType: UserSource): Map<String, List<String>>

    suspend fun getUniqueRolesByType(sourceType: UserSource): List<String>

    suspend fun setRoleHierarchy(
        sourceType: UserSource,
        hierarchy: Map<String, List<String>>,
    )

    suspend fun upsertRole(role: GenericRole): Boolean

    suspend fun getRolesBySource(source: UserSource): List<GenericRole>
}
