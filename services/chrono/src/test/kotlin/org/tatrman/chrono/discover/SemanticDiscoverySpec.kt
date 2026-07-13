package org.tatrman.chrono.discover

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.tatrman.chrono.FakeMetadataClient
import org.tatrman.chrono.recognize.DateTarget

class SemanticDiscoverySpec :
    StringSpec({
        val discovery = FakeMetadataClient.accounting("cnc")

        "periodTable resolves the entity + start/end/code columns + code_format" {
            val pt = discovery.periodTable("cnc").shouldNotBeNull()
            pt.entityName shouldBe "AccountingPeriod"
            pt.start?.columnName shouldBe "start_date"
            pt.end?.columnName shouldBe "end_date"
            pt.code?.columnName shouldBe "period"
            pt.codeFormat shouldBe "yyyyMM"
            pt.start?.entityName shouldBe "AccountingPeriod"
        }

        "periodTable is null for a package with no period_table" {
            FakeMetadataClient(emptyList()).periodTable("cnc").shouldBeNull()
        }

        "anchorColumn defaults to the event_date column" {
            val c = discovery.anchorColumn("cnc", target = null).shouldNotBeNull()
            c.columnName shouldBe "date"
            c.entityName shouldBe "Transaction"
        }

        "anchorColumn honours an explicit DUE target" {
            discovery.anchorColumn("cnc", DateTarget.DUE).shouldNotBeNull().columnName shouldBe "due"
        }

        "anchorColumn falls back to event_date when the targeted role is absent" {
            // no posting_date column in the fixture → falls back to event_date
            discovery.anchorColumn("cnc", DateTarget.POSTING).shouldNotBeNull().columnName shouldBe "date"
        }
    })
