package org.tatrman.whois.db

import org.jetbrains.exposed.v1.core.Table

object RoleHierarchyTable : Table("role_hierarchy") {
    val parentRole = varchar("parent_role", 255)
    val childRole = varchar("child_role", 255)
    val sourceType = varchar("source_type", 50)

    override val primaryKey = PrimaryKey(parentRole, childRole, sourceType)
}
