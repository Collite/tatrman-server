// SPDX-License-Identifier: Apache-2.0
package org.tatrman.health.api

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.tatrman.health.status.StatusPage
import org.tatrman.health.status.StatusService

/**
 * FO-P5.S2.T1 (FO-28) — the open-tier status surface. `GET /` renders the minimal human page; `GET
 * /status` is its JSON twin. GET-only: the open Server has no management app, so no mutating verb is
 * served here (asserted in StatusPageSpec).
 */
fun Application.statusRoutes(status: StatusService) {
    routing {
        get("/") {
            call.respondText(StatusPage.render(status.current()), ContentType.Text.Html)
        }
        get("/status") {
            call.respond(status.current())
        }
    }
}
