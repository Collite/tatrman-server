// SPDX-License-Identifier: Apache-2.0
package org.tatrman.identity.client

import org.tatrman.identity.domain.UserSource
import org.slf4j.LoggerFactory
import shared.libs.db.common.DatabaseConnection

class ErpClient(
    private val dbConnection: DatabaseConnection,
) : UserSourceClient {
    private val logger = LoggerFactory.getLogger("ErpClient")

    override val source: UserSource = UserSource.ERP

    override suspend fun fetchUsers(): List<GenericUser> =
        dbConnection.query {
            dbConnection.getConnection().use { conn ->
                conn
                    .prepareStatement(
                        """
                        SELECT IDUZIVATEL as user_id,
                               KOD_UZIV as username,
                               JMENO_UZIV as fullname,
                               UZIV_EMAIL as email
                        FROM QUZIVATEL_DF
                        WHERE VLZAKAZPRISTUP=0
                        """,
                    ).use { statement ->
                        val resultSet = statement.executeQuery()
                        val users = mutableListOf<GenericUser>()
                        while (resultSet.next()) {
                            val fullname = resultSet.getString("fullname") ?: ""
                            val (first, last) =
                                fullname.split(" ", limit = 2).let {
                                    if (it.size == 2) it[0] to it[1] else it[0] to ""
                                }
                            var email = resultSet.getString("email") ?: ""
                            if (email.isBlank()) {
                                email = "${resultSet.getString("username")}@dfparter.cz"
                            }
                            users.add(
                                GenericUser(
                                    id = resultSet.getString("user_id"),
                                    username = resultSet.getString("username"),
                                    firstName = first,
                                    lastName = last,
                                    email = email,
                                ),
                            )
                        }
                        logger.info("Fetched {} users from ERP", users.size)
                        users
                    }
            }
        }

    override suspend fun fetchRoles(): List<GenericRole> =
        dbConnection.query {
            dbConnection.getConnection().use { conn ->
                conn
                    .prepareStatement(
                        """
                        SELECT IDSKUPUZIV as id,
                               KOD_SKUP_UZIV as code,
                               NAZEV_SKUP_UZIV as description
                        FROM QSKUPUZIV
                        """,
                    ).use { statement ->
                        val resultSet = statement.executeQuery()
                        val roles = mutableListOf<GenericRole>()
                        while (resultSet.next()) {
                            roles.add(
                                GenericRole(
                                    id = resultSet.getString("id"),
                                    code = resultSet.getString("code"),
                                    description = resultSet.getString("description"),
                                    source = UserSource.ERP,
                                ),
                            )
                        }
                        logger.info("Fetched {} roles from ERP", roles.size)
                        roles
                    }
            }
        }

    override suspend fun fetchUserRoleMappings(): List<Pair<String, String>> =
        dbConnection.query {
            dbConnection.getConnection().use { conn ->
                conn
                    .prepareStatement(
                        "SELECT IDUZIVATEL as user_id, IDSKUPUZIV as role_id FROM QUZVESKUP",
                    ).use { statement ->
                        val resultSet = statement.executeQuery()
                        val mappings = mutableListOf<Pair<String, String>>()
                        while (resultSet.next()) {
                            val userId = resultSet.getString("user_id")
                            val roleId = resultSet.getString("role_id")
                            if (userId != null && roleId != null) {
                                mappings.add(userId to roleId)
                            }
                        }
                        logger.info("Fetched {} role mappings from ERP", mappings.size)
                        mappings
                    }
            }
        }

    override suspend fun fetchRoleHierarchy(): List<Pair<String, String>> =
        dbConnection.query {
            dbConnection.getConnection().use { conn ->
                conn
                    .prepareStatement(
                        "SELECT IDSKUPUZIV_R as parent_role, IDSKUPUZIV as child_role FROM QSKUPUZIV",
                    ).use { statement ->
                        val resultSet = statement.executeQuery()
                        val mappings = mutableListOf<Pair<String, String>>()
                        while (resultSet.next()) {
                            var parentRole = resultSet.getString("parent_role") ?: ""
                            val childRole = resultSet.getString("child_role")
                            if (parentRole == childRole) {
                                parentRole = ""
                            }
                            if (childRole != null) {
                                mappings.add(parentRole to childRole)
                            }
                        }
                        logger.info("Fetched {} role hierarchy entries from ERP", mappings.size)
                        mappings
                    }
            }
        }

    companion object {
        fun fromConfig(
            config: com.typesafe.config.Config,
            path: String = "erp-database",
        ): ErpClient {
            val dbConnection = DatabaseConnection.fromConfig(config, path)
            dbConnection.init()
            return ErpClient(dbConnection)
        }
    }
}
