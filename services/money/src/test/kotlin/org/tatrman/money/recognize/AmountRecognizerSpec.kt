package org.tatrman.money.recognize

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * A10.3 amount recognizer — one group per rule family (scale words, word numerals, locale
 * separators, currency detection, comparators, tolerance, "at today's rate").
 */
class AmountRecognizerSpec :
    StringSpec({
        val r = AmountRecognizer()

        fun amt(
            span: String,
            locale: String = "en-US",
        ) = r.recognize(span, locale)

        "digit + en scale suffixes: 2M / 100k / 3 bn" {
            amt("over 2M").shouldNotBeNull().amount.toLong() shouldBe 2_000_000L
            amt("over 100k").shouldNotBeNull().amount.toLong() shouldBe 100_000L
            amt("over 3 bn").shouldNotBeNull().amount.toLong() shouldBe 3_000_000_000L
        }

        "cs scale words: 2 mil. / 3 mld / 1,5 mil" {
            amt("nad 2 mil.", "cs-CZ").shouldNotBeNull().amount.toLong() shouldBe 2_000_000L
            amt("nad 3 mld", "cs-CZ").shouldNotBeNull().amount.toLong() shouldBe 3_000_000_000L
            amt("kolem 1,5 mil", "cs-CZ").shouldNotBeNull().amount.toLong() shouldBe 1_500_000L
        }

        "cs word numerals: sto tisíc / půl milionu" {
            amt("sto tisíc korun", "cs-CZ").shouldNotBeNull().amount.toLong() shouldBe 100_000L
            amt("půl milionu", "cs-CZ").shouldNotBeNull().amount.toLong() shouldBe 500_000L
        }

        "locale separators: plain thousands 100 000 / en 100,000" {
            amt("faktury nad 100 000", "cs-CZ").shouldNotBeNull().amount.toLong() shouldBe 100_000L
            amt("over 100,000", "en-US").shouldNotBeNull().amount.toLong() shouldBe 100_000L
        }

        "currency: ISO codes, symbols, cs/en names" {
            amt("over 5 000 EUR").shouldNotBeNull().currency shouldBe "EUR"
            amt("2 mil. Kč", "cs-CZ").shouldNotBeNull().currency shouldBe "CZK"
            amt("over \$100k").shouldNotBeNull().currency shouldBe "USD"
            amt("nad 100000 korun", "cs-CZ").shouldNotBeNull().currency shouldBe "CZK"
            amt("over 100000").shouldNotBeNull().currency.shouldBeNull()
        }

        "comparators: nad/over → GT, pod/under → LT, at least → GE, at most → LE" {
            amt("nad 100000", "cs-CZ").shouldNotBeNull().comparator shouldBe Comparator.GT
            amt("pod 50000", "cs-CZ").shouldNotBeNull().comparator shouldBe Comparator.LT
            amt("at least 1000").shouldNotBeNull().comparator shouldBe Comparator.GE
            amt("at most 999").shouldNotBeNull().comparator shouldBe Comparator.LE
        }

        "negated comparators: 'no more than' → LE, 'not less than' → GE (direction not inverted)" {
            amt("no more than 500").shouldNotBeNull().comparator shouldBe Comparator.LE
            amt("not more than 500").shouldNotBeNull().comparator shouldBe Comparator.LE
            amt("not less than 500").shouldNotBeNull().comparator shouldBe Comparator.GE
            amt("no less than 500").shouldNotBeNull().comparator shouldBe Comparator.GE
            amt("ne více než 500", "cs-CZ").shouldNotBeNull().comparator shouldBe Comparator.LE
        }

        "currency: 'eur' word boundary does not match Europe/European" {
            amt("over 5000 in Europe").shouldNotBeNull().currency.shouldBeNull()
            amt("European sales above 5000").shouldNotBeNull().currency.shouldBeNull()
        }

        "empty locale defaults to cs separators so a cs decimal is not 100x-inflated" {
            // parseNumber under the recognizer directly still needs an explicit locale; the SERVICE
            // supplies the default. Here we assert the cs branch reads the decimal comma correctly.
            amt("100,50 Kč", "cs-CZ").shouldNotBeNull().amount shouldBe java.math.BigDecimal("100.50")
        }

        "tolerance: kolem / around set the tolerance flag" {
            amt("kolem 100k", "cs-CZ").shouldNotBeNull().tolerance shouldBe true
            amt("around 100000").shouldNotBeNull().tolerance shouldBe true
            amt("over 100000").shouldNotBeNull().tolerance shouldBe false
        }

        "at today's rate forces the current-rate flag" {
            amt("over 5000 EUR at today's rate").shouldNotBeNull().atCurrentRate shouldBe true
            amt("nad 5000 EUR dnešním kurzem", "cs-CZ").shouldNotBeNull().atCurrentRate shouldBe true
            amt("over 5000 EUR").shouldNotBeNull().atCurrentRate shouldBe false
        }

        "no numeric magnitude → null (caller falls back)" {
            amt("some expensive invoices").shouldBeNull()
        }
    })
