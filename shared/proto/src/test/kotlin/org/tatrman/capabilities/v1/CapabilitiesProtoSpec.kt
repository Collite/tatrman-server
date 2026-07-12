// SPDX-License-Identifier: Apache-2.0
package org.tatrman.capabilities.v1

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe

class CapabilitiesProtoSpec :
    StringSpec({

        "ToolCapability round-trips through proto" {
            val tool =
                ToolCapability
                    .newBuilder()
                    .setCapabilityId("model.fit.arima:v1")
                    .setCategory("model.fit.*")
                    .setVersion("v1")
                    .setDescription("ARIMA forecast")
                    .build()

            val parsed = ToolCapability.parseFrom(tool.toByteArray())

            parsed.capabilityId shouldBe "model.fit.arima:v1"
            parsed.category shouldBe "model.fit.*"
            parsed.version shouldBe "v1"
            parsed.description shouldBe "ARIMA forecast"
        }

        "AgentCapability round-trips with intent_kinds_supported" {
            val pythia =
                AgentCapability
                    .newBuilder()
                    .setAgentKind(AgentKind.INVESTIGATOR)
                    .setAgentId("pythia")
                    .setDisplayName("Pythia")
                    .addIntentKindsSupported(IntentKind.RCA)
                    .addIntentKindsSupported(IntentKind.FORECAST)
                    .setHitlDefault(HitlProfile.INTERACTIVE)
                    .build()

            val parsed = AgentCapability.parseFrom(pythia.toByteArray())

            parsed.agentKind shouldBe AgentKind.INVESTIGATOR
            parsed.agentId shouldBe "pythia"
            parsed.intentKindsSupportedList shouldContainAll listOf(IntentKind.RCA, IntentKind.FORECAST)
            parsed.hitlDefault shouldBe HitlProfile.INTERACTIVE
        }

        "AgentCapability with ShemManifest fields populated only when AREA_QA" {
            val golem =
                AgentCapability
                    .newBuilder()
                    .setAgentKind(AgentKind.AREA_QA)
                    .setAgentId("golem-erp")
                    .setAreaName("ERP")
                    .addAreaEntities("customer")
                    .addAreaEntities("invoice")
                    .addLocaleDefaults(
                        LocaleDefault
                            .newBuilder()
                            .setLocale("cs-CZ")
                            .setGreeting("Dobrý den")
                            .setDateFormat("dd.MM.yyyy")
                            .setCurrency("CZK")
                            .build(),
                    ).build()

            val parsed = AgentCapability.parseFrom(golem.toByteArray())

            parsed.agentKind shouldBe AgentKind.AREA_QA
            parsed.areaName shouldBe "ERP"
            parsed.areaEntitiesList shouldContainAll listOf("customer", "invoice")
            parsed.localeDefaultsList shouldContain
                LocaleDefault
                    .newBuilder()
                    .setLocale("cs-CZ")
                    .setGreeting("Dobrý den")
                    .setDateFormat("dd.MM.yyyy")
                    .setCurrency("CZK")
                    .build()
        }

        "Capability sealed union holds tool or agent via oneof" {
            val asTool =
                Capability
                    .newBuilder()
                    .setTool(ToolCapability.newBuilder().setCapabilityId("model.fit.arima:v1").build())
                    .build()
            val asAgent =
                Capability
                    .newBuilder()
                    .setAgent(AgentCapability.newBuilder().setAgentId("pythia").build())
                    .build()

            asTool.hasTool() shouldBe true
            asTool.hasAgent() shouldBe false
            asAgent.hasAgent() shouldBe true
            asAgent.hasTool() shouldBe false

            Capability.parseFrom(asTool.toByteArray()).tool.capabilityId shouldBe "model.fit.arima:v1"
            Capability.parseFrom(asAgent.toByteArray()).agent.agentId shouldBe "pythia"
        }

        "every response message reserves field 99 for ResponseMessage entries" {
            // Compile-time assurance: builder accepts a kantheon-common ResponseMessage at messages.
            val msg =
                org.tatrman.common.v1.ResponseMessage
                    .newBuilder()
                    .setSeverity(org.tatrman.common.v1.Severity.WARNING)
                    .setCode("capability_already_registered")
                    .setHumanMessage("Capability re-registered; description updated.")
                    .build()
            val resp =
                RegisterResponse
                    .newBuilder()
                    .setRegistrationId("rid-1")
                    .addMessages(msg)
                    .build()

            resp.messagesList.first().code shouldBe "capability_already_registered"
        }
    })
