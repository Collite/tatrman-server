// SPDX-License-Identifier: Apache-2.0
package org.tatrman.identity.repository

import com.typesafe.config.Config
import org.tatrman.identity.client.GenericRole
import org.tatrman.identity.domain.UserIdRecord
import org.tatrman.identity.domain.UserRecord
import org.tatrman.identity.domain.UserSource
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

class UserRepositoryJson(
    private val config: Config,
) : UserRepositoryPort {
    private val logger = LoggerFactory.getLogger("UserRepositoryJson")

    private var users: List<UserRecord> = emptyList()

    private val json = Json { ignoreUnknownKeys = true }

    val size: Int
        get() = users.size

    fun load() {
        val filePath = config.getString("whois.jsonFilePath")
        try {
            val content: String =
                when {
                    filePath.isNotBlank() -> {
                        val file = File(filePath)
                        if (file.exists()) {
                            logger.info("Loading whois from file: {}", filePath)
                            file.readText()
                        } else {
                            logger.warn("Whois file not found: {}, falling back to classpath resource", filePath)
                            loadFromClasspath()
                        }
                    }
                    else -> {
                        logger.info("No jsonFilePath configured, loading from classpath resource")
                        loadFromClasspath()
                    }
                }
            users = json.decodeFromString<List<UserRecord>>(content)
            logger.info("Loaded {} users", users.size)
        } catch (e: Exception) {
            logger.error("Error loading whois file: {}", e.message, e)
        }
    }

    private fun loadFromClasspath(): String {
        val resourceUrl =
            javaClass.classLoader.getResource("whois.json")
                ?: throw IllegalStateException("Resource whois.json not found in classpath")
        return resourceUrl.openStream().bufferedReader().use { it.readText() }
    }

    override suspend fun findByInternalId(id: Long): UserRecord? {
        if (users.isEmpty()) load()
        return users.find { it.internalId == id }
    }

    override suspend fun findByUserId(
        type: UserSource,
        userId: String,
    ): UserRecord? {
        if (users.isEmpty()) load()
        return users.find { user -> user.identities[type]?.userId == userId }
    }

    override suspend fun findByEmail(email: String): UserRecord? {
        if (users.isEmpty()) load()
        return users.find { it.email?.lowercase() == email.lowercase() }
    }

    override suspend fun searchById(id: String): List<UserRecord> {
        if (users.isEmpty()) load()
        return users.filter { user ->
            user.identities.values.any { it.userId == id }
        }
    }

    override suspend fun upsertUser(user: UserRecord): Long {
        if (users.isEmpty()) load()
        val existingIndex = users.indexOfFirst { it.internalId == user.internalId }
        if (existingIndex >= 0) {
            users = users.toMutableList().apply { this[existingIndex] = user }
        } else {
            users = users + user
        }
        return user.internalId
    }

    override suspend fun setIdentity(
        internalUserId: Long,
        type: UserSource,
        identity: UserIdRecord,
    ) {
        if (users.isEmpty()) load()
        val userIndex = users.indexOfFirst { it.internalId == internalUserId }
        if (userIndex >= 0) {
            val user = users[userIndex]
            val updatedIdentities = user.identities.toMutableMap().apply { put(type, identity) }
            users =
                users.toMutableList().apply {
                    this[userIndex] = user.copy(identities = updatedIdentities)
                }
        }
    }

    override suspend fun setRoles(
        internalUserId: Long,
        type: UserSource,
        userId: String,
        roles: List<String>,
    ) {
        if (users.isEmpty()) load()
        val userIndex = users.indexOfFirst { it.internalId == internalUserId }
        if (userIndex >= 0) {
            val user = users[userIndex]
            val existingIdentity = user.identities[type]
            if (existingIdentity != null) {
                val updatedIdentity = existingIdentity.copy(userRoles = roles)
                val updatedIdentities = user.identities.toMutableMap().apply { put(type, updatedIdentity) }
                users =
                    users.toMutableList().apply {
                        this[userIndex] = user.copy(identities = updatedIdentities)
                    }
            }
        }
    }

    override suspend fun getAllUsers(): List<UserRecord> {
        if (users.isEmpty()) load()
        return users
    }

    override suspend fun getRoleHierarchy(sourceType: UserSource): Map<String, List<String>> {
        logger.warn("getRoleHierarchy is not supported in JSON repository mode")
        return emptyMap()
    }

    override suspend fun getUniqueRolesByType(sourceType: UserSource): List<String> {
        if (users.isEmpty()) load()
        val roles = users.flatMap { user -> user.identities[sourceType]?.userRoles ?: emptyList() }
        return roles.distinct()
    }

    override suspend fun setRoleHierarchy(
        sourceType: UserSource,
        hierarchy: Map<String, List<String>>,
    ) {
        logger.warn("setRoleHierarchy is not supported in JSON repository mode")
    }

    override suspend fun upsertRole(role: GenericRole): Boolean {
        logger.warn("upsertRole is not supported in JSON repository mode")
        return false
    }

    override suspend fun getRolesBySource(source: UserSource): List<GenericRole> {
        logger.warn("getRolesBySource is not supported in JSON repository mode")
        return emptyList()
    }
}

@Deprecated("Use UserRepositoryJson instead", ReplaceWith("UserRepositoryJson(config)"))
typealias UserRepository = UserRepositoryJson
