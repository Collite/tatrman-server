package org.tatrman.identity.sync

import io.kotest.core.spec.style.StringSpec
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.tatrman.identity.client.GenericRole
import org.tatrman.identity.client.GenericUser
import org.tatrman.identity.client.UserSourceClient
import org.tatrman.identity.domain.UserRecord
import org.tatrman.identity.domain.UserSource
import org.tatrman.identity.repository.UserRepositoryDb

/**
 * Unit spec: the sync orchestration. A mocked UserSourceClient (the upstream) and a mocked
 * UserRepositoryDb (the sink) — asserts the five-phase pipeline calls the batch writes when the
 * source's config flags are on, and skips them when off. No DB, no network.
 */
class WhoisSyncServiceSpec :
    StringSpec({

        val source = UserSource.KEYCLOAK

        fun client(): UserSourceClient =
            mockk<UserSourceClient> {
                coEvery { fetchUsers() } returns
                    listOf(GenericUser(id = "kc-1", username = "ada", email = "ada@x.cz", enabled = true))
                coEvery { fetchRoles() } returns
                    listOf(GenericRole(id = "r-1", code = "admin", description = null, source = source))
                coEvery { fetchRoleHierarchy() } returns listOf("admin" to "viewer")
                coEvery { fetchUserRoleMappings() } returns listOf("kc-1" to "r-1")
            }

        fun repo(): UserRepositoryDb =
            mockk<UserRepositoryDb>(relaxed = true) {
                coEvery { syncUsersBatch(any()) } returns mapOf("ada@x.cz" to 1L)
                coEvery { findByUserId(any(), any()) } returns UserRecord(internalId = 1, email = "ada@x.cz")
            }

        "a fully-enabled source drives all five sync phases" {
            val repo = repo()
            val service =
                WhoisSyncService(
                    clients = mapOf(source to client()),
                    userRepository = repo,
                    syncConfig =
                        mapOf(
                            source to
                                SyncConfig(
                                    enabled = true,
                                    syncUsers = true,
                                    syncRoles = true,
                                    syncRoleHierarchy = true,
                                    syncUserRoleMappings = true,
                                ),
                        ),
                )

            runBlocking { service.sync(source) }

            coVerify(exactly = 1) { repo.syncUsersBatch(any()) }
            coVerify(exactly = 1) { repo.syncIdentitiesBatch(eq(source), any()) }
            coVerify(exactly = 1) { repo.syncRolesBatch(eq(source), any()) }
            coVerify(exactly = 1) { repo.syncRoleHierarchyBatch(eq(source), any()) }
            coVerify(exactly = 1) { repo.syncUserRolesBatch(eq(source), any()) }
        }

        "a disabled source is a no-op" {
            val repo = repo()
            val service =
                WhoisSyncService(
                    clients = mapOf(source to client()),
                    userRepository = repo,
                    syncConfig = mapOf(source to SyncConfig(enabled = false)),
                )

            runBlocking { service.sync(source) }

            coVerify(exactly = 0) { repo.syncUsersBatch(any()) }
            coVerify(exactly = 0) { repo.syncRolesBatch(any(), any()) }
        }

        "roles-only config skips the user and hierarchy phases" {
            val repo = repo()
            val service =
                WhoisSyncService(
                    clients = mapOf(source to client()),
                    userRepository = repo,
                    syncConfig =
                        mapOf(
                            source to
                                SyncConfig(
                                    enabled = true,
                                    syncUsers = false,
                                    syncRoles = true,
                                    syncRoleHierarchy = false,
                                    syncUserRoleMappings = false,
                                ),
                        ),
                )

            runBlocking { service.sync(source) }

            coVerify(exactly = 0) { repo.syncUsersBatch(any()) }
            coVerify(exactly = 1) { repo.syncRolesBatch(eq(source), any()) }
            coVerify(exactly = 0) { repo.syncRoleHierarchyBatch(any(), any()) }
        }
    })
