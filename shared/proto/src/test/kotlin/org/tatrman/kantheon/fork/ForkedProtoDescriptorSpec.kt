package org.tatrman.kantheon.fork

import com.google.protobuf.Descriptors
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotStartWith
import org.tatrman.argos.v1.ArgosProto
import org.tatrman.ariadne.v1.Ariadne
import org.tatrman.dfdsl.v1.Dfdsl
import org.tatrman.echo.v1.EchoProto
import org.tatrman.kadmos.v1.KadmosProto
import org.tatrman.kantheon.themis.v1.Themis
import org.tatrman.kyklop.v1.KyklopProto
import org.tatrman.plan.v1.Context
import org.tatrman.plan.v1.Parameters
import org.tatrman.plan.v1.Plan
import org.tatrman.prometheus.v1.PrometheusProto
import org.tatrman.proteus.v1.ProteusProto
import org.tatrman.security.v1.SecurityProto
import org.tatrman.theseus.v1.TheseusProto
import org.tatrman.transdsl.v1.Transdsl
import org.tatrman.worker.v1.Worker

/**
 * Fork Stage 1.2 T1 — descriptor-level invariants for the forked pipeline protos.
 *
 * This is the JVM gate the stage originally called for. The earlier worry that an
 * in-module `FileDescriptor` walk was "brittle" because of Kotlin/Java source-set
 * ordering was a stale-build artifact (cleared by `:shared:proto:clean`), not a real
 * wiring problem — the descriptor API resolves cleanly from the same module's test
 * source set. `scripts/verify-forked-proto-layout.sh` stays as a cheap textual
 * pre-check; this spec is the authoritative wire-contract assertion.
 */
class ForkedProtoDescriptorSpec :
    StringSpec({

        // Each forked .proto generates one Java outer class exposing the FileDescriptor,
        // paired here with the org.tatrman.* package the fork renamed it into.
        val forked: List<Pair<Descriptors.FileDescriptor, String>> =
            listOf(
                Plan.getDescriptor() to "org.tatrman.plan.v1",
                Context.getDescriptor() to "org.tatrman.plan.v1",
                Parameters.getDescriptor() to "org.tatrman.plan.v1",
                Worker.getDescriptor() to "org.tatrman.worker.v1",
                Transdsl.getDescriptor() to "org.tatrman.transdsl.v1",
                Dfdsl.getDescriptor() to "org.tatrman.dfdsl.v1",
                Ariadne.getDescriptor() to "org.tatrman.ariadne.v1",
                EchoProto.getDescriptor() to "org.tatrman.echo.v1",
                KadmosProto.getDescriptor() to "org.tatrman.kadmos.v1",
                ProteusProto.getDescriptor() to "org.tatrman.proteus.v1",
                PrometheusProto.getDescriptor() to "org.tatrman.prometheus.v1",
                ArgosProto.getDescriptor() to "org.tatrman.argos.v1",
                SecurityProto.getDescriptor() to "org.tatrman.security.v1",
                KyklopProto.getDescriptor() to "org.tatrman.kyklop.v1",
                TheseusProto.getDescriptor() to "org.tatrman.theseus.v1",
                // Stage 2.6 switch-over: Themis now imports only in-repo protos
                // (kadmos.v1 + common.v1) — included here so the cz/dfpartner-free
                // invariant is asserted repo-wide, not just over the forked set.
                Themis.getDescriptor() to "org.tatrman.kantheon.themis.v1",
            )

        val responseMessageFqn = "org.tatrman.kantheon.common.v1.ResponseMessage"

        // (a) every forked descriptor declares its target org.tatrman.* package.
        forked.forEach { (fd, expectedPackage) ->
            "${fd.name} declares package $expectedPackage" {
                fd.`package` shouldBe expectedPackage
            }
        }

        // (b) no forked descriptor (transitively) depends on a cz/dfpartner/* file.
        forked.forEach { (fd, _) ->
            "${fd.name} has no cz/dfpartner dependency" {
                transitiveDeps(fd).forEach { dep ->
                    dep.name shouldNotStartWith "cz/dfpartner/"
                }
            }
        }

        // (c) every message that declares field number 99 types it as the kantheon
        // ResponseMessage stand-in (Rule 6). Walks nested message types too.
        forked.forEach { (fd, _) ->
            "${fd.name} types field 99 as $responseMessageFqn" {
                allMessages(fd.messageTypes).forEach { msg ->
                    val field99 = msg.findFieldByNumber(99) ?: return@forEach
                    withClue(msg, field99) {
                        field99.type shouldBe Descriptors.FieldDescriptor.Type.MESSAGE
                        field99.messageType.fullName shouldBe responseMessageFqn
                    }
                }
            }
        }

        // Sanity: the descriptor set is non-empty so an accidental no-op (e.g. all
        // getDescriptor() calls returning empty trees) can't make the suite pass vacuously.
        "forked descriptor set is populated" {
            forked.shouldNotBeEmpty()
            allMessages(forked.flatMap { it.first.messageTypes }).shouldNotBeEmpty()
        }
    })

/** Depth-first flatten of a message type and all its nested message types. */
private fun allMessages(roots: List<Descriptors.Descriptor>): List<Descriptors.Descriptor> =
    roots.flatMap { listOf(it) + allMessages(it.nestedTypes) }

/** All FileDescriptors reachable via the dependency graph, excluding the root itself. */
private fun transitiveDeps(fd: Descriptors.FileDescriptor): Set<Descriptors.FileDescriptor> {
    val seen = mutableSetOf<Descriptors.FileDescriptor>()

    fun visit(node: Descriptors.FileDescriptor) {
        node.dependencies.forEach { dep ->
            if (seen.add(dep)) visit(dep)
        }
    }
    visit(fd)
    return seen
}

private fun withClue(
    msg: Descriptors.Descriptor,
    field: Descriptors.FieldDescriptor,
    assertion: () -> Unit,
) = io.kotest.assertions.withClue("${msg.fullName}.${field.name} (field 99)", assertion)
