package org.tatrman.whois.db

import org.jetbrains.exposed.v1.core.Table

object UserIdentitiesTable : Table("user_identities") {
    val internalUserId = integer("internal_user_id").references(UsersTable.id)
    val userIdType = varchar("user_id_type", 50)
    val userId = varchar("user_id", 255)
    val userName = varchar("user_name", 255).nullable()
    val active = bool("active").default(true)

    override val primaryKey = PrimaryKey(internalUserId, userIdType)
}
