package org.tatrman.identity.db

import org.jetbrains.exposed.v1.core.Table

object UserRolesTable : Table("user_roles") {
    val internalUserId = integer("internal_user_id").references(UsersTable.id)
    val userIdType = varchar("user_id_type", 50)
    val userId = varchar("user_id", 255)
    val role = varchar("role", 255)

    override val primaryKey = PrimaryKey(internalUserId, userIdType, userId, role)
}
