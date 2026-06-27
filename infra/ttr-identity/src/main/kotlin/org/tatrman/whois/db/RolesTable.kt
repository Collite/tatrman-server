package org.tatrman.whois.db

import org.jetbrains.exposed.v1.core.Table

object RolesTable : Table("roles") {
    val id = varchar("id", 255)
    val code = varchar("code", 255)
    val description = varchar("description", 500)
    val sourceColumn = varchar("source", 50)

    override val primaryKey = PrimaryKey(id, sourceColumn)
}
