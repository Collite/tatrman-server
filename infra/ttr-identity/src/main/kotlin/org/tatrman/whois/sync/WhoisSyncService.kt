package org.tatrman.whois.sync

import com.typesafe.config.Config
import org.tatrman.whois.client.GenericUser
import org.tatrman.whois.client.UserSourceClient
import org.tatrman.whois.domain.UserIdRecord
import org.tatrman.whois.domain.UserSource
import org.tatrman.whois.repository.UserRepositoryDb
import org.slf4j.LoggerFactory

class WhoisSyncService(
    private val clients: Map<UserSource, UserSourceClient>,
    private val userRepository: UserRepositoryDb,
    private val syncConfig: Map<UserSource, SyncConfig>,
) {
    private val logger = LoggerFactory.getLogger("WhoisSyncService")

    suspend fun sync(source: UserSource) {
        val client = clients[source]
        val config = syncConfig[source]

        if (client == null) {
            logger.warn("No client configured for source: {}", source)
            return
        }

        if (config == null || !config.enabled) {
            logger.info("Sync disabled for source: {}", source)
            return
        }

        logger.info("Starting sync for source: {}", source)

        try {
            // Phase 1: Sync users first (other tables have foreign keys to users)
            // Fetch users once and reuse for both user upsert and identity sync
            val fetchedUsers = if (config.syncUsers) client.fetchUsers() else emptyList()
            val emailToInternalId =
                if (config.syncUsers) {
                    syncUsersBatch(fetchedUsers, source)
                } else {
                    emptyMap()
                }

            // Phase 2: Sync user identities (depends on users)
            if (config.syncUsers) {
                syncIdentitiesBatch(fetchedUsers, source, emailToInternalId)
            }

            // Phase 3: Sync roles
            if (config.syncRoles) {
                syncRolesBatch(client, source)
            }

            // Phase 4: Sync role hierarchy
            if (config.syncRoleHierarchy) {
                syncRoleHierarchyBatch(client, source)
            }

            // Phase 5: Sync user-role mappings (depends on users and roles)
            if (config.syncUserRoleMappings) {
                syncUserRoleMappingsBatch(client, source)
            }

            logger.info("Sync completed for source: {}", source)
        } catch (e: Exception) {
            logger.error("Sync failed for source {}: {}", source, e.message, e)
        }
    }

    /**
     * Upserts all users in a single batch transaction.
     * Returns a map from email (lowercased) to internal user id.
     */
    private suspend fun syncUsersBatch(
        users: List<GenericUser>,
        source: UserSource,
    ): Map<String, Long> {
        logger.info("Syncing users (batch) for source: {}, count: {}", source, users.size)

        val userRecords = users.map { it.toUserRecord(source) }
        val emailToId = userRepository.syncUsersBatch(userRecords)

        logger.info("Users batch sync completed for source: {}, upserted {} users", source, emailToId.size)
        return emailToId
    }

    /**
     * Deletes all identities for the source, then inserts new ones in a single batch transaction.
     */
    private suspend fun syncIdentitiesBatch(
        users: List<GenericUser>,
        source: UserSource,
        emailToInternalId: Map<String, Long>,
    ) {
        logger.info("Syncing identities (batch) for source: {}", source)

        val identities =
            users
                .mapNotNull { genericUser ->
                    val email = (genericUser.email ?: "").lowercase()
                    val internalId = emailToInternalId[email]
                    if (internalId == null) {
                        logger.warn("No internal id found for user email={}, skipping identity", genericUser.email)
                        null
                    } else {
                        val identity =
                            UserIdRecord(
                                userId = genericUser.id,
                                userName = genericUser.username,
                                active = genericUser.enabled,
                                userRoles = emptyList(),
                            )
                        Triple(internalId, source, identity)
                    }
                }.distinctBy { it.first }

        userRepository.syncIdentitiesBatch(source, identities)
        logger.info("Identities batch sync completed for source: {}, synced {} identities", source, identities.size)
    }

    /**
     * Deletes all roles for the source, then inserts new ones in a single batch transaction.
     */
    private suspend fun syncRolesBatch(
        client: UserSourceClient,
        source: UserSource,
    ) {
        logger.info("Syncing roles (batch) for source: {}", source)
        val roles = client.fetchRoles()
        logger.info("Found {} roles from {}", roles.size, source)

        userRepository.syncRolesBatch(source, roles)
        logger.info("Roles batch sync completed for source: {}", source)
    }

    /**
     * Deletes all role hierarchy for the source, then inserts new ones in a single batch transaction.
     */
    private suspend fun syncRoleHierarchyBatch(
        client: UserSourceClient,
        source: UserSource,
    ) {
        logger.info("Syncing role hierarchy (batch) for source: {}", source)
        val hierarchyPairs = client.fetchRoleHierarchy()
        logger.info("Found {} role hierarchy entries from {}", hierarchyPairs.size, source)

        val hierarchyMap = hierarchyPairs.groupBy({ it.first }) { it.second }
        userRepository.syncRoleHierarchyBatch(source, hierarchyMap)
        logger.info("Role hierarchy batch sync completed for source: {}", source)
    }

    /**
     * Deletes all user-role mappings for the source, then inserts new ones in a single batch transaction.
     */
    private suspend fun syncUserRoleMappingsBatch(
        client: UserSourceClient,
        source: UserSource,
    ) {
        logger.info("Syncing user-role mappings (batch) for source: {}", source)
        val mappings = client.fetchUserRoleMappings()
        logger.info("Found {} user-role mappings from {}", mappings.size, source)

        val rolesByUser = mappings.groupBy { it.first }.mapValues { it.value.map { pair -> pair.second } }

        // Resolve internal user ids for all external user ids
        val batchMappings = mutableListOf<Triple<Long, String, List<String>>>()
        for ((externalUserId, roleIds) in rolesByUser) {
            val existingUser = userRepository.findByUserId(source, externalUserId)
            if (existingUser != null) {
                batchMappings.add(Triple(existingUser.internalId, externalUserId, roleIds))
            } else {
                logger.warn("User {} not found in whois database, cannot set roles", externalUserId)
            }
        }

        userRepository.syncUserRolesBatch(source, batchMappings)
        logger.info(
            "User-role mappings batch sync completed for source: {}, synced {} users",
            source,
            batchMappings.size,
        )
    }

    companion object {
        private val logger = LoggerFactory.getLogger("WhoisSyncService")

        fun loadSyncConfig(config: Config): Map<UserSource, SyncConfig> {
            val result = mutableMapOf<UserSource, SyncConfig>()

            for (source in UserSource.entries) {
                val sourcePath = source.name.lowercase()
                try {
                    val sourceConfig = config.getConfig("whois.sync.$sourcePath")
                    result[source] =
                        SyncConfig(
                            enabled = sourceConfig.getBoolean("enabled"),
                            syncUsers = sourceConfig.getBoolean("sync-users"),
                            syncRoles = sourceConfig.getBoolean("sync-roles"),
                            syncRoleHierarchy = sourceConfig.getBoolean("sync-role-hierarchy"),
                            syncUserRoleMappings = sourceConfig.getBoolean("sync-user-role-mappings"),
                        )
                } catch (e: Exception) {
                    logger.warn("Could not load sync config for source {}: {}", source, e.message)
                    result[source] =
                        SyncConfig(
                            enabled = false,
                            syncUsers = false,
                            syncRoles = false,
                            syncRoleHierarchy = false,
                            syncUserRoleMappings = false,
                        )
                }
            }
            return result
        }
    }
}

data class SyncConfig(
    val enabled: Boolean = false,
    val syncUsers: Boolean = false,
    val syncRoles: Boolean = false,
    val syncRoleHierarchy: Boolean = false,
    val syncUserRoleMappings: Boolean = false,
)
