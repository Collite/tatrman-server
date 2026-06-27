package org.tatrman.kantheon.echo.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

class LemmatizerTest :
    StringSpec({

        "NoopLemmatizer folds surface forms (identity lemma)" {
            NoopLemmatizer.lemmatize(listOf("Zákazník", "ZÁKAZNÍKŮ", "Dodavatel")) shouldBe
                mapOf("Zákazník" to "zakaznik", "ZÁKAZNÍKŮ" to "zakazniku", "Dodavatel" to "dodavatel")
        }

        "NlpLemmatizer maps inflected tokens to their folded lemma" {
            val analyzeResponse =
                """
                {
                  "language": "cs", "languageConfidence": 1.0, "engineUsed": "morphodita",
                  "tokens": [
                    {"text": "zákazníků", "lemma": "zákazník", "upos": "NOUN"},
                    {"text": "objednávkami", "lemma": "objednávka", "upos": "NOUN"},
                    {"text": "Praze", "lemma": "Praha", "upos": "PROPN"}
                  ],
                  "sentences": [], "paragraphs": [], "entities": [], "traceId": "t", "elapsedMs": 1, "messages": []
                }
                """.trimIndent()
            val client =
                HttpClient(
                    MockEngine { request ->
                        request.url.encodedPath shouldBe "/v1/analyze"
                        respond(
                            content = analyzeResponse,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    },
                )
            val lemmatizer = NlpLemmatizer(client, "http://nlp-service:8080", "cs")

            lemmatizer.lemmatize(listOf("Zákazníků", "objednávkami", "Praze", "untouched")) shouldBe
                mapOf(
                    "Zákazníků" to "zakaznik",
                    "objednávkami" to "objednavka",
                    "Praze" to "praha",
                    // not returned by NLP → keeps its folded surface form
                    "untouched" to "untouched",
                )
        }

        "NlpLemmatizer degrades to folded surface forms on a server error" {
            val client = HttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) })
            val lemmatizer = NlpLemmatizer(client, "http://nlp-service:8080", "cs")

            lemmatizer.lemmatize(listOf("Zákazníků", "Dodavatel")) shouldBe
                mapOf("Zákazníků" to "zakazniku", "Dodavatel" to "dodavatel")
        }

        "NlpLemmatizer returns empty for no tokens" {
            val client = HttpClient(MockEngine { respondError(HttpStatusCode.InternalServerError) })
            NlpLemmatizer(client, "http://nlp-service:8080").lemmatize(emptyList()) shouldBe emptyMap()
        }
    })
