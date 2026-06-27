package org.tatrman.kantheon.argos.roles

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.tatrman.plan.v1.PipelineContext
import java.util.concurrent.atomic.AtomicInteger

class RoleSourceSpec :
    StringSpec({

        fun ctx(
            userId: String,
            vararg roles: String,
        ): PipelineContext =
            PipelineContext
                .newBuilder()
                .setUserId(userId)
                .addAllAuthRoles(roles.toList())
                .build()

        "BearerRoleSource returns exactly the forwarded bearer roles" {
            val roles = runBlocking { BearerRoleSource().resolveRoles(ctx("u1", "analyst", "viewer")) }
            roles shouldContainExactlyInAnyOrder listOf("analyst", "viewer")
        }

        "WhoisRoleSource unions the bearer floor with the enrichment roles (distinct)" {
            val source = WhoisRoleSource(lookup = staticLookup("approver", "viewer"))
            val roles = runBlocking { source.resolveRoles(ctx("u1", "analyst", "viewer")) }
            roles shouldContainExactlyInAnyOrder listOf("analyst", "viewer", "approver")
        }

        "WhoisRoleSource caches per user — the lookup is hit at most once per TTL" {
            val calls = AtomicInteger(0)
            val counting =
                object : WhoisRoleLookup {
                    override suspend fun rolesFor(keycloakUserId: String): List<String> {
                        calls.incrementAndGet()
                        return listOf("approver")
                    }
                }
            val source = WhoisRoleSource(lookup = counting, cacheTtlSeconds = 300)
            runBlocking {
                source.resolveRoles(ctx("u1", "analyst"))
                source.resolveRoles(ctx("u1", "analyst"))
            }
            calls.get() shouldBe 1
        }

        "a blank user_id keeps the bearer floor and never calls the lookup" {
            val calls = AtomicInteger(0)
            val counting =
                object : WhoisRoleLookup {
                    override suspend fun rolesFor(keycloakUserId: String): List<String> {
                        calls.incrementAndGet()
                        return listOf("approver")
                    }
                }
            val roles = runBlocking { WhoisRoleSource(counting).resolveRoles(ctx("", "analyst")) }
            roles shouldContainExactlyInAnyOrder listOf("analyst")
            calls.get() shouldBe 0
        }

        "an unknown user (empty enrichment) leaves the bearer floor unchanged" {
            val roles = runBlocking { WhoisRoleSource(staticLookup()).resolveRoles(ctx("u1", "analyst")) }
            roles shouldContainExactlyInAnyOrder listOf("analyst")
        }

        "an unavailable lookup propagates RoleSourceUnavailableException (fail closed)" {
            val down =
                object : WhoisRoleLookup {
                    override suspend fun rolesFor(keycloakUserId: String): List<String> =
                        throw RoleSourceUnavailableException("whois down")
                }
            shouldThrow<RoleSourceUnavailableException> {
                runBlocking { WhoisRoleSource(down).resolveRoles(ctx("u1", "analyst")) }
            }
        }
    })

private fun staticLookup(vararg roles: String): WhoisRoleLookup =
    object : WhoisRoleLookup {
        override suspend fun rolesFor(keycloakUserId: String): List<String> = roles.toList()
    }
