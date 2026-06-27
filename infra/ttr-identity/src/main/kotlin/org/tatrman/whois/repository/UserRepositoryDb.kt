package org.tatrman.whois.repository

import org.tatrman.whois.client.GenericRole
import org.tatrman.whois.domain.UserIdRecord
import org.tatrman.whois.domain.UserRecord
import org.tatrman.whois.domain.UserSource
import org.slf4j.LoggerFactory
import shared.libs.db.common.DatabaseConnection

class UserRepositoryDb(
    private val dbConnection: DatabaseConnection,
) : UserRepositoryPort {
    private val logger = LoggerFactory.getLogger("UserRepositoryDb")

    override suspend fun findByInternalId(id: Long): UserRecord? =
        dbConnection.query {
            val conn = dbConnection.getConnection()
            val stmt = conn.prepareStatement("SELECT id, email, first_name, last_name FROM users WHERE id = ?")
            stmt.setLong(1, id)
            val rs = stmt.executeQuery()
            if (rs.next()) {
                val user = buildUserRecord(rs, conn)
                rs.close()
                stmt.close()
                conn.close()
                user
            } else {
                rs.close()
                stmt.close()
                conn.close()
                null
            }
        }

    override suspend fun findByUserId(
        type: UserSource,
        userId: String,
    ): UserRecord? =
        dbConnection.query {
            val conn = dbConnection.getConnection()
            val stmt =
                conn.prepareStatement(
                    """
                    SELECT u.id, u.email, u.first_name, u.last_name
                    FROM users u
                    INNER JOIN user_identities ui ON u.id = ui.internal_user_id
                    WHERE ui.user_id_type = ? AND ui.user_id = ?
                    """.trimIndent(),
                )
            stmt.setString(1, type.name)
            stmt.setString(2, userId)
            val rs = stmt.executeQuery()
            if (rs.next()) {
                val user = buildUserRecord(rs, conn)
                rs.close()
                stmt.close()
                conn.close()
                user
            } else {
                rs.close()
                stmt.close()
                conn.close()
                null
            }
        }

    override suspend fun findByEmail(email: String): UserRecord? =
        dbConnection.query {
            val conn = dbConnection.getConnection()
            val stmt =
                conn.prepareStatement(
                    "SELECT id, email, first_name, last_name FROM users WHERE LOWER(email) = LOWER(?)",
                )
            stmt.setString(1, email)
            val rs = stmt.executeQuery()
            if (rs.next()) {
                val user = buildUserRecord(rs, conn)
                rs.close()
                stmt.close()
                conn.close()
                user
            } else {
                rs.close()
                stmt.close()
                conn.close()
                null
            }
        }

    override suspend fun searchById(id: String): List<UserRecord> =
        dbConnection.query {
            val conn = dbConnection.getConnection()
            val stmt =
                conn.prepareStatement(
                    """
                    SELECT DISTINCT u.id, u.email, u.first_name, u.last_name
                    FROM users u
                    INNER JOIN user_identities ui ON u.id = ui.internal_user_id
                    WHERE ui.user_id = ? OR ui.user_name = ?
                    """.trimIndent(),
                )
            stmt.setString(1, id)
            stmt.setString(2, id)
            val rs = stmt.executeQuery()
            val users = mutableListOf<UserRecord>()
            while (rs.next()) {
                users.add(buildUserRecord(rs, conn))
            }
            rs.close()
            stmt.close()
            conn.close()
            users
        }

    override suspend fun upsertUser(user: UserRecord): Long =
        dbConnection.query {
            val conn = dbConnection.getConnection()
            val existingId =
                if (user.internalId > 0) {
                    user.internalId
                } else {
                    val checkStmt = conn.prepareStatement("SELECT id FROM users WHERE LOWER(email) = LOWER(?)")
                    checkStmt.setString(1, user.email ?: "")
                    val rs = checkStmt.executeQuery()
                    val id = if (rs.next()) rs.getLong("id") else null
                    rs.close()
                    checkStmt.close()
                    id
                }

            val resultId =
                if (existingId != null) {
                    val updateStmt =
                        conn.prepareStatement(
                            "UPDATE users SET email = ?, first_name = ?, last_name = ? WHERE id = ?",
                        )
                    updateStmt.setString(1, user.email ?: "")
                    updateStmt.setString(2, user.firstName ?: "")
                    updateStmt.setString(3, user.lastName ?: "")
                    updateStmt.setLong(4, existingId)
                    updateStmt.executeUpdate()
                    updateStmt.close()
                    existingId
                } else {
                    val insertStmt =
                        conn.prepareStatement(
                            "INSERT INTO users (email, first_name, last_name) VALUES (?, ?, ?)",
                        )
                    insertStmt.setString(1, user.email ?: "")
                    insertStmt.setString(2, user.firstName ?: "")
                    insertStmt.setString(3, user.lastName ?: "")
                    insertStmt.executeUpdate()
                    insertStmt.close()

                    val idStmt = conn.prepareStatement("SELECT LASTVAL() as id")
                    val rs = idStmt.executeQuery()
                    val id = if (rs.next()) rs.getLong("id") else 0L
                    rs.close()
                    idStmt.close()
                    id
                }
            conn.commit()
            conn.close()
            resultId
        }

    override suspend fun setIdentity(
        internalUserId: Long,
        type: UserSource,
        identity: UserIdRecord,
    ) = dbConnection.query {
        val conn = dbConnection.getConnection()
        val checkStmt =
            conn.prepareStatement(
                "SELECT 1 FROM user_identities WHERE internal_user_id = ? AND user_id_type = ?",
            )
        checkStmt.setLong(1, internalUserId)
        checkStmt.setString(2, type.name)
        val rs = checkStmt.executeQuery()
        val exists = rs.next()
        rs.close()
        checkStmt.close()

        if (exists) {
            val updateStmt =
                conn.prepareStatement(
                    "UPDATE user_identities SET user_id = ?, user_name = ?, active = ? WHERE internal_user_id = ? AND user_id_type = ?",
                )
            updateStmt.setString(1, identity.userId)
            updateStmt.setString(2, identity.userName ?: "")
            updateStmt.setBoolean(3, identity.active)
            updateStmt.setLong(4, internalUserId)
            updateStmt.setString(5, type.name)
            updateStmt.executeUpdate()
            updateStmt.close()
        } else {
            val insertStmt =
                conn.prepareStatement(
                    "INSERT INTO user_identities (internal_user_id, user_id_type, user_id, user_name, active) VALUES (?, ?, ?, ?, ?)",
                )
            insertStmt.setLong(1, internalUserId)
            insertStmt.setString(2, type.name)
            insertStmt.setString(3, identity.userId)
            insertStmt.setString(4, identity.userName ?: "")
            insertStmt.setBoolean(5, identity.active)
            insertStmt.executeUpdate()
            insertStmt.close()
        }
        conn.commit()
        conn.close()
    }

    override suspend fun setRoles(
        internalUserId: Long,
        type: UserSource,
        userId: String,
        roles: List<String>,
    ) = dbConnection.query {
        val conn = dbConnection.getConnection()
        conn.autoCommit = true
        val deleteStmt =
            conn.prepareStatement(
                "DELETE FROM user_roles WHERE internal_user_id = ? AND user_id_type = ? AND user_id = ?",
            )
        deleteStmt.setLong(1, internalUserId)
        deleteStmt.setString(2, type.name)
        deleteStmt.setString(3, userId)
        deleteStmt.executeUpdate()
        deleteStmt.close()

        for (role in roles) {
            val insertStmt =
                conn.prepareStatement(
                    "INSERT INTO user_roles (internal_user_id, user_id_type, user_id, role) VALUES (?, ?, ?, ?)",
                )
            insertStmt.setLong(1, internalUserId)
            insertStmt.setString(2, type.name)
            insertStmt.setString(3, userId)
            insertStmt.setString(4, role)
            insertStmt.executeUpdate()
            insertStmt.close()
        }
        conn.close()
    }

    override suspend fun getAllUsers(): List<UserRecord> =
        dbConnection.query {
            val conn = dbConnection.getConnection()
            val stmt = conn.prepareStatement("SELECT id, email, first_name, last_name FROM users")
            val rs = stmt.executeQuery()
            val users = mutableListOf<UserRecord>()
            while (rs.next()) {
                users.add(buildUserRecord(rs, conn))
            }
            rs.close()
            stmt.close()
            conn.close()
            users
        }

    override suspend fun getRoleHierarchy(sourceType: UserSource): Map<String, List<String>> =
        dbConnection.query {
            val conn = dbConnection.getConnection()
            val sql =
                """
SELECT parent.code AS parent_role, child.code AS child_role
FROM role_hierarchy rh
         INNER JOIN roles parent ON parent.id = rh.parent_role
         INNER JOIN roles child ON child.id = rh.child_role
WHERE rh.source_type = ?
UNION ALL
SELECT '' as parent_role, child.code AS child_role
FROM role_hierarchy rh
         INNER JOIN roles child ON child.id = rh.child_role
WHERE rh.source_type = ? and rh.parent_role = ''
                """.trimIndent()
            val stmt =
                conn.prepareStatement(sql)
            stmt.setString(1, sourceType.name)
            stmt.setString(2, sourceType.name)
            val rs = stmt.executeQuery()
            val hierarchy = mutableMapOf<String, MutableList<String>>()
            while (rs.next()) {
                val parent = rs.getString("parent_role")
                val child = rs.getString("child_role")
                hierarchy.getOrPut(parent) { mutableListOf() }.add(child)
                hierarchy.putIfAbsent(child, mutableListOf())
            }
            rs.close()
            stmt.close()
            conn.close()
            hierarchy
        }

    suspend fun getRoleHierarchyIds(sourceType: UserSource): Map<String, List<String>> =
        dbConnection.query {
            val conn = dbConnection.getConnection()
            val stmt =
                conn.prepareStatement(
                    "SELECT parent_role, child_role FROM role_hierarchy WHERE source_type = ?",
                )
            stmt.setString(1, sourceType.name)
            val rs = stmt.executeQuery()
            val hierarchy = mutableMapOf<String, MutableList<String>>()
            while (rs.next()) {
                val parent = rs.getString("parent_role")
                val child = rs.getString("child_role")
                hierarchy.getOrPut(parent) { mutableListOf() }.add(child)
            }
            rs.close()
            stmt.close()
            conn.close()
            hierarchy
        }

    override suspend fun getUniqueRolesByType(sourceType: UserSource): List<String> =
        dbConnection.query {
            val conn = dbConnection.getConnection()
            val stmt =
                conn.prepareStatement(
                    "SELECT DISTINCT role FROM user_roles WHERE user_id_type = ?",
                )
            stmt.setString(1, sourceType.name)
            val rs = stmt.executeQuery()
            val roles = mutableListOf<String>()
            while (rs.next()) {
                roles.add(rs.getString("role"))
            }
            rs.close()
            stmt.close()
            conn.close()
            roles
        }

    override suspend fun setRoleHierarchy(
        sourceType: UserSource,
        hierarchy: Map<String, List<String>>,
    ) = dbConnection.query {
        val conn = dbConnection.getConnection()
        conn.autoCommit = true
        val deleteStmt = conn.prepareStatement("DELETE FROM role_hierarchy WHERE source_type = ?")
        deleteStmt.setString(1, sourceType.name)
        deleteStmt.executeUpdate()
        deleteStmt.close()

        for ((parent, children) in hierarchy) {
            for (child in children) {
                val insertStmt =
                    conn.prepareStatement(
                        "INSERT INTO role_hierarchy (parent_role, child_role, source_type) VALUES (?, ?, ?)",
                    )
                insertStmt.setString(1, parent)
                insertStmt.setString(2, child)
                insertStmt.setString(3, sourceType.name)
                insertStmt.executeUpdate()
                insertStmt.close()
            }
        }
        conn.close()
    }

    override suspend fun upsertRole(role: GenericRole): Boolean =
        dbConnection.query {
            dbConnection.getConnection().use { conn ->
                conn.autoCommit = true
                conn
                    .prepareStatement(
                        """
                        MERGE INTO roles (id, code, description, source)
                        VALUES (?, ?, ?, ?)
                        """,
                    ).use { stmt ->
                        stmt.setString(1, role.id)
                        stmt.setString(2, role.code)
                        stmt.setString(3, role.description ?: "")
                        stmt.setString(4, role.source.name)
                        stmt.executeUpdate()
                        true
                    }
            }
        }

    override suspend fun getRolesBySource(source: UserSource): List<GenericRole> =
        dbConnection.query {
            dbConnection.getConnection().use { conn ->
                conn
                    .prepareStatement(
                        "SELECT id, code, description, source FROM roles WHERE source = ?",
                    ).use { stmt ->
                        stmt.setString(1, source.name)
                        val rs = stmt.executeQuery()
                        val roles = mutableListOf<GenericRole>()
                        while (rs.next()) {
                            val desc = rs.getString("description")
                            roles.add(
                                GenericRole(
                                    id = rs.getString("id"),
                                    code = rs.getString("code"),
                                    description = if (desc.isNullOrBlank()) null else desc,
                                    source = UserSource.valueOf(rs.getString("source")),
                                ),
                            )
                        }
                        roles
                    }
            }
        }

    private fun buildUserRecord(
        rs: java.sql.ResultSet,
        conn: java.sql.Connection,
    ): UserRecord {
        val internalId = rs.getLong("id")
        val identities = loadIdentities(internalId, conn)
        return UserRecord(
            internalId = internalId,
            email = rs.getString("email"),
            firstName = rs.getString("first_name"),
            lastName = rs.getString("last_name"),
            identities = identities,
        )
    }

    private fun loadIdentities(
        internalUserId: Long,
        conn: java.sql.Connection,
    ): Map<UserSource, UserIdRecord> {
        val identities = mutableMapOf<UserSource, UserIdRecord>()

        val identityStmt =
            conn.prepareStatement(
                "SELECT user_id_type, user_id, user_name, active FROM user_identities WHERE internal_user_id = ?",
            )
        identityStmt.setLong(1, internalUserId)
        val identityRs = identityStmt.executeQuery()

        while (identityRs.next()) {
            val type = UserSource.valueOf(identityRs.getString("user_id_type"))
            val userId = identityRs.getString("user_id")
            val roles = loadRoles(internalUserId, type, userId, conn)
            identities[type] =
                UserIdRecord(
                    userId = userId,
                    userName = identityRs.getString("user_name"),
                    active = identityRs.getBoolean("active"),
                    userRoles = roles,
                )
        }
        identityRs.close()
        identityStmt.close()

        return identities
    }

    private fun loadRoles(
        internalUserId: Long,
        type: UserSource,
        userId: String,
        conn: java.sql.Connection,
    ): List<String> {
        val roles = mutableListOf<String>()
        val sql =
            """
            SELECT r.code
            FROM user_roles u
            INNER JOIN roles r ON r.id = u.role
            WHERE internal_user_id = ? AND user_id_type = ? AND user_id = ?
            """.trimIndent()
        val roleStmt = conn.prepareStatement(sql)
        roleStmt.setLong(1, internalUserId)
        roleStmt.setString(2, type.name)
        roleStmt.setString(3, userId)
        val roleRs = roleStmt.executeQuery()
        while (roleRs.next()) {
            roles.add(roleRs.getString("code"))
        }
        roleRs.close()
        roleStmt.close()
        return roles
    }

    companion object {
        private const val BATCH_SIZE = 50
    }

    // ── Batch sync methods ──────────────────────────────────────────────

    /**
     * Upserts a batch of users in a single transaction.
     * Returns a map from email (lowercased) to the internal user id.
     */
    suspend fun syncUsersBatch(users: List<UserRecord>): Map<String, Long> =
        dbConnection.query {
            val result = mutableMapOf<String, Long>()
            dbConnection.getConnection().use { conn ->
                val checkStmt =
                    conn.prepareStatement(
                        "SELECT id FROM users WHERE LOWER(email) = LOWER(?)",
                    )
                val updateStmt =
                    conn.prepareStatement(
                        "UPDATE users SET email = ?, first_name = ?, last_name = ? WHERE id = ?",
                    )
                val insertStmt =
                    conn.prepareStatement(
                        "INSERT INTO users (email, first_name, last_name) VALUES (?, ?, ?)",
                        java.sql.Statement.RETURN_GENERATED_KEYS,
                    )

                for (user in users) {
                    val email = user.email ?: ""
                    // Check if user already exists by internal id or email
                    val existingId =
                        if (user.internalId > 0) {
                            user.internalId
                        } else {
                            checkStmt.setString(1, email)
                            checkStmt.executeQuery().use { rs ->
                                if (rs.next()) rs.getLong("id") else null
                            }
                        }

                    if (existingId != null) {
                        updateStmt.setString(1, email)
                        updateStmt.setString(2, user.firstName ?: "")
                        updateStmt.setString(3, user.lastName ?: "")
                        updateStmt.setLong(4, existingId)
                        updateStmt.executeUpdate()
                        result[email.lowercase()] = existingId
                    } else {
                        insertStmt.setString(1, email)
                        insertStmt.setString(2, user.firstName ?: "")
                        insertStmt.setString(3, user.lastName ?: "")
                        insertStmt.executeUpdate()
                        insertStmt.generatedKeys.use { rs ->
                            if (rs.next()) {
                                result[email.lowercase()] = rs.getLong(1)
                            }
                        }
                    }
                }

                checkStmt.close()
                updateStmt.close()
                insertStmt.close()
                conn.commit()
            }
            logger.debug("syncUsersBatch: upserted {} users", result.size)
            result
        }

    /**
     * Deletes all identities for the given source, then inserts the new ones in batch.
     */
    suspend fun syncIdentitiesBatch(
        source: UserSource,
        identities: List<Triple<Long, UserSource, UserIdRecord>>,
    ) = dbConnection.query {
        dbConnection.getConnection().use { conn ->
            // Delete all identities for this source at once
            conn
                .prepareStatement(
                    "DELETE FROM user_identities WHERE user_id_type = ?",
                ).use { deleteStmt ->
                    deleteStmt.setString(1, source.name)
                    deleteStmt.executeUpdate()
                }

            // Batch insert all identities (max BATCH_SIZE per executeBatch)
            conn
                .prepareStatement(
                    "INSERT INTO user_identities (internal_user_id, user_id_type, user_id, user_name, active) VALUES (?, ?, ?, ?, ?)",
                ).use { insertStmt ->
                    var count = 0
                    for ((internalUserId, type, identity) in identities) {
                        insertStmt.setLong(1, internalUserId)
                        insertStmt.setString(2, type.name)
                        insertStmt.setString(3, identity.userId)
                        insertStmt.setString(4, identity.userName ?: "")
                        insertStmt.setBoolean(5, identity.active)
                        insertStmt.addBatch()
                        count++
                        if (count % BATCH_SIZE == 0) {
                            insertStmt.executeBatch()
                        }
                    }
                    if (count % BATCH_SIZE != 0) {
                        insertStmt.executeBatch()
                    }
                }

            conn.commit()
        }
        logger.debug("syncIdentitiesBatch: synced {} identities for source {}", identities.size, source)
    }

    /**
     * Deletes all roles for the given source, then inserts the new ones in batch.
     */
    suspend fun syncRolesBatch(
        source: UserSource,
        roles: List<GenericRole>,
    ) = dbConnection.query {
        dbConnection.getConnection().use { conn ->
            // Delete all roles for this source at once
            conn
                .prepareStatement(
                    "DELETE FROM roles WHERE source = ?",
                ).use { deleteStmt ->
                    deleteStmt.setString(1, source.name)
                    deleteStmt.executeUpdate()
                }

            // Batch insert all roles (max BATCH_SIZE per executeBatch)
            conn
                .prepareStatement(
                    "INSERT INTO roles (id, code, description, source) VALUES (?, ?, ?, ?)",
                ).use { insertStmt ->
                    var count = 0
                    for (role in roles) {
                        insertStmt.setString(1, role.id)
                        insertStmt.setString(2, role.code)
                        insertStmt.setString(3, role.description ?: "")
                        insertStmt.setString(4, role.source.name)
                        insertStmt.addBatch()
                        count++
                        if (count % BATCH_SIZE == 0) {
                            insertStmt.executeBatch()
                        }
                    }
                    if (count % BATCH_SIZE != 0) {
                        insertStmt.executeBatch()
                    }
                }

            conn.commit()
        }
        logger.debug("syncRolesBatch: synced {} roles for source {}", roles.size, source)
    }

    /**
     * Deletes all role hierarchy entries for the given source, then inserts the new ones in batch.
     */
    suspend fun syncRoleHierarchyBatch(
        sourceType: UserSource,
        hierarchy: Map<String, List<String>>,
    ) = dbConnection.query {
        dbConnection.getConnection().use { conn ->
            // Delete all hierarchy entries for this source at once
            conn
                .prepareStatement(
                    "DELETE FROM role_hierarchy WHERE source_type = ?",
                ).use { deleteStmt ->
                    deleteStmt.setString(1, sourceType.name)
                    deleteStmt.executeUpdate()
                }

            // Batch insert all hierarchy entries (max BATCH_SIZE per executeBatch)
            conn
                .prepareStatement(
                    "INSERT INTO role_hierarchy (parent_role, child_role, source_type) VALUES (?, ?, ?)",
                ).use { insertStmt ->
                    var count = 0
                    for ((parent, children) in hierarchy) {
                        for (child in children) {
                            insertStmt.setString(1, parent)
                            insertStmt.setString(2, child)
                            insertStmt.setString(3, sourceType.name)
                            insertStmt.addBatch()
                            count++
                            if (count % BATCH_SIZE == 0) {
                                insertStmt.executeBatch()
                            }
                        }
                    }
                    if (count % BATCH_SIZE != 0) {
                        insertStmt.executeBatch()
                    }
                }

            conn.commit()
        }
        logger.debug("syncRoleHierarchyBatch: synced hierarchy for source {}", sourceType)
    }

    /**
     * Deletes all user-role mappings for the given source, then inserts the new ones in batch.
     */
    suspend fun syncUserRolesBatch(
        source: UserSource,
        mappings: List<Triple<Long, String, List<String>>>,
    ) = dbConnection.query {
        dbConnection.getConnection().use { conn ->
            // Delete all user-role mappings for this source at once
            conn
                .prepareStatement(
                    "DELETE FROM user_roles WHERE user_id_type = ?",
                ).use { deleteStmt ->
                    deleteStmt.setString(1, source.name)
                    deleteStmt.executeUpdate()
                }

            // Batch insert all user-role mappings (max BATCH_SIZE per executeBatch)
            conn
                .prepareStatement(
                    "INSERT INTO user_roles (internal_user_id, user_id_type, user_id, role) VALUES (?, ?, ?, ?)",
                ).use { insertStmt ->
                    var count = 0
                    for ((internalUserId, externalUserId, roles) in mappings) {
                        for (role in roles) {
                            insertStmt.setLong(1, internalUserId)
                            insertStmt.setString(2, source.name)
                            insertStmt.setString(3, externalUserId)
                            insertStmt.setString(4, role)
                            insertStmt.addBatch()
                            count++
                            if (count % BATCH_SIZE == 0) {
                                insertStmt.executeBatch()
                            }
                        }
                    }
                    if (count % BATCH_SIZE != 0) {
                        insertStmt.executeBatch()
                    }
                }

            conn.commit()
        }
        logger.debug("syncUserRolesBatch: synced user-role mappings for source {}", source)
    }
}
