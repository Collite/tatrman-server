package org.tatrman.whois.client

import org.tatrman.whois.domain.UserIdRecord
import org.tatrman.whois.domain.UserRecord
import org.tatrman.whois.domain.UserSource
import kotlinx.serialization.Serializable

@Serializable
data class GenericUser(
    val id: String,
    val username: String,
    val email: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val enabled: Boolean = true,
) {
    fun toUserRecord(userType: UserSource): UserRecord =
        UserRecord(
            email = this.email,
            firstName = this.firstName,
            lastName = this.lastName,
            identities =
                mapOf(
                    userType to
                        UserIdRecord(
                            userId = this.id,
                            userName = this.username,
                            active = this.enabled,
                            userRoles = emptyList(),
                        ),
                ),
        )
}
