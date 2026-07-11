package org.tatrman.identity.domain

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

class UserRecordTest :
    StringSpec({

        val record =
            UserRecord(
                internalId = 42,
                email = "ada@example.com",
                firstName = "Ada",
                lastName = "Lovelace",
                identities =
                    mapOf(
                        UserSource.KEYCLOAK to UserIdRecord(userId = "kc-1", userRoles = listOf("analyst", "viewer")),
                        UserSource.ERP to UserIdRecord(userId = "erp-9", userRoles = listOf("viewer", "approver")),
                    ),
            )

        "keycloakId / erpId resolve from the identities map" {
            record.keycloakId shouldBe "kc-1"
            record.erpId shouldBe "erp-9"
        }

        "allRoles flattens and de-duplicates roles across sources" {
            record.allRoles shouldContainExactlyInAnyOrder listOf("analyst", "viewer", "approver")
        }

        "missing identity sources resolve to null" {
            UserRecord().keycloakId shouldBe null
            UserRecord().erpId shouldBe null
        }

        "UserRecord round-trips through JSON" {
            val json = Json.encodeToString(UserRecord.serializer(), record)
            Json.decodeFromString(UserRecord.serializer(), json) shouldBe record
        }
    })
