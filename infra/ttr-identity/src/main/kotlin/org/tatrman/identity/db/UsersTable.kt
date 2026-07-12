// SPDX-License-Identifier: Apache-2.0
package org.tatrman.identity.db

import org.jetbrains.exposed.v1.core.Table

object UsersTable : Table("users") {
    val id = integer("id").autoIncrement()
    val email = varchar("email", 255).nullable()
    val firstName = varchar("first_name", 255).nullable()
    val lastName = varchar("last_name", 255).nullable()

    override val primaryKey = PrimaryKey(id)
}
