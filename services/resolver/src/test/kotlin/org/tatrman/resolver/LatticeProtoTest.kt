// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.tatrman.resolver.v1.Binding
import org.tatrman.resolver.v1.Disposition
import org.tatrman.resolver.v1.EvidenceClass
import org.tatrman.resolver.v1.FrameRole
import org.tatrman.resolver.v1.GapKind
import org.tatrman.resolver.v1.GapRecord
import org.tatrman.resolver.v1.MatchMethod
import org.tatrman.resolver.v1.Mention
import org.tatrman.resolver.v1.ResolutionState
import org.tatrman.resolver.v1.ResolveResponse
import org.tatrman.resolver.v1.SourceTag
import org.tatrman.resolver.v1.Span
import org.tatrman.resolver.v1.TargetClass

/**
 * RV-P2.1.T3 — the lattice messages themselves (contracts §1). Not a round-trip smoke test:
 * each case pins a decision that a plausible alternative shape would have lost.
 */
class LatticeProtoTest :
    StringSpec({

        "a mention carries TWO frame roles at once — the measure-as-subject class (Q-15 blocking finding)" {
            val mention =
                Mention
                    .newBuilder()
                    .setId("m2")
                    .setSpan(
                        Span
                            .newBuilder()
                            .setStart(7)
                            .setEnd(14)
                            .setText("náklady"),
                    ).setLemma("náklad")
                    .addFrameRoles(FrameRole.FRAME_ROLE_SUBJECT)
                    .addFrameRoles(FrameRole.FRAME_ROLE_MEASURE)
                    .build()

            val roundTripped = Mention.parseFrom(mention.toByteArray())
            roundTripped.frameRolesList shouldContainExactly
                listOf(FrameRole.FRAME_ROLE_SUBJECT, FrameRole.FRAME_ROLE_MEASURE)
        }

        "a gap carries the roles of what it sits on — RV-15 asks on the SUBJECT gap, not on any gap" {
            val gap =
                GapRecord
                    .newBuilder()
                    .setSpan(
                        Span
                            .newBuilder()
                            .setStart(18)
                            .setEnd(34)
                            .setText("čerpacích stanic"),
                    ).setKind(GapKind.GAP_KIND_G1_UNBOUND)
                    .addFrameRoles(FrameRole.FRAME_ROLE_SUBJECT)
                    .setMentionId("m3")
                    .setDisposition(Disposition.DISPOSITION_UNRESOLVED)
                    .build()

            gap.frameRolesList shouldContainExactly listOf(FrameRole.FRAME_ROLE_SUBJECT)
            gap.mentionId shouldBe "m3"
            gap.valueId shouldBe "" // a mention gap names no value — the two slots are exclusive
        }

        "zero bindings is expressible — the whole reason RV-P3 waited for this field" {
            val mention =
                Mention
                    .newBuilder()
                    .setId("m3")
                    .setLemma("stanice")
                    .build()
            mention.bindingsCount shouldBe 0
            // ...and it survives the wire, which a `Binding binding = 5` singular could not have
            // said at all: an unset singular and a bound-to-nothing mention would look identical.
            Mention.parseFrom(mention.toByteArray()).bindingsCount shouldBe 0
        }

        "auto_bindable distinguishes 'do not bind' from 'no decision applies'" {
            val undecided = Binding.newBuilder().setRef("md.measure.cost").build()
            undecided.hasAutoBindable().shouldBeFalse()

            val refused =
                Binding
                    .newBuilder()
                    .setRef("md.measure.cost")
                    .setAutoBindable(false)
                    .build()
            val wire = Binding.parseFrom(refused.toByteArray())
            wire.hasAutoBindable().shouldBeTrue()
            wire.autoBindable.shouldBeFalse()
        }

        "TYPOS carries its distance and TOKENS its margin — the RV-32 parameters survive the enum" {
            val typos =
                Binding
                    .newBuilder()
                    .setMethod(MatchMethod.MATCH_METHOD_TYPOS)
                    .setMaxDistance(1)
                    .build()
            val tokens =
                Binding
                    .newBuilder()
                    .setMethod(MatchMethod.MATCH_METHOD_TOKENS)
                    .setUniquenessMargin(0.25)
                    .setAutoBindable(true)
                    .build()

            Binding.parseFrom(typos.toByteArray()).maxDistance shouldBe 1
            Binding.parseFrom(tokens.toByteArray()).uniquenessMargin shouldBe 0.25
        }

        "evidence classes are ranked by field number, strongest first (RV-14 lexicographic order)" {
            listOf(
                EvidenceClass.EVIDENCE_CLASS_EXACT,
                EvidenceClass.EVIDENCE_CLASS_DECLARED_ALIAS,
                EvidenceClass.EVIDENCE_CLASS_LEARNED_ALIAS,
                EvidenceClass.EVIDENCE_CLASS_ANCHORED_FUZZY_STRONG,
                EvidenceClass.EVIDENCE_CLASS_UNANCHORED_FUZZY_STRONG,
                EvidenceClass.EVIDENCE_CLASS_WEAK,
            ).zipWithNext { stronger, weaker -> stronger.number shouldBe weaker.number - 1 }
        }

        "the lattice rides the response additively — field 7, nothing above it moved" {
            val response =
                ResolveResponse
                    .newBuilder()
                    .setResolutionState(
                        ResolutionState
                            .newBuilder()
                            .addMentions(
                                Mention
                                    .newBuilder()
                                    .setId("m1")
                                    .addBindings(
                                        Binding
                                            .newBuilder()
                                            .setRef("op:show")
                                            .setTargetClass(TargetClass.TARGET_CLASS_OPERATOR)
                                            .setEvidenceClass(EvidenceClass.EVIDENCE_CLASS_DECLARED_ALIAS)
                                            .setSource(SourceTag.SOURCE_TAG_DECLARED),
                                    ),
                            ),
                    ).build()

            val descriptor = ResolveResponse.getDescriptor().findFieldByName("resolution_state")
            descriptor.number shouldBe 7
            ResolveResponse
                .parseFrom(response.toByteArray())
                .resolutionState.mentionsList
                .single()
                .bindingsList
                .single()
                .ref shouldBe "op:show"
        }
    })
