// SPDX-License-Identifier: Apache-2.0
package org.tatrman.health.status

/**
 * FO-P5.S2.T1 (FO-28) — the minimal human status page. Framework-free server-rendered HTML (no JS, no
 * external assets); shows the Server version, the model fingerprint and the per-service rollup. The
 * open Server ships NO management app: the page carries no operator write affordance — its own copy
 * states the read-only stance (operator tasks are Server config + the CLI, per the ops guide).
 */
object StatusPage {
    fun render(status: ServerStatus): String {
        val fingerprint = status.modelFingerprint ?: "unavailable"
        val rows =
            status.services.joinToString("\n") { s ->
                val tone = if (s.status == "healthy") "ok" else "down"
                """      <tr data-service="${esc(s.name)}"><td>${esc(s.name)}</td>""" +
                    """<td class="s $tone">${esc(s.status)}</td></tr>"""
            }
        val allUp = status.summary.unhealthy == 0
        val overallTone = if (allUp) "ok" else "down"
        val overallWord = if (allUp) "healthy" else "degraded"
        return """<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Tatrman Server — status</title>
  <style>
    :root { color-scheme: light dark; }
    body { font: 15px/1.5 system-ui, sans-serif; margin: 2rem auto; max-width: 44rem; padding: 0 1rem; }
    h1 { font-size: 1.4rem; margin: 0 0 .25rem; }
    .meta { color: #888; margin: 0 0 1rem; }
    .meta b { color: inherit; }
    .overall { font-weight: 600; padding: .4rem .6rem; border-radius: .4rem; display: inline-block; }
    .overall.ok { background: #e6f4ea; color: #1b5e20; }
    .overall.down { background: #fdecea; color: #b71c1c; }
    table { border-collapse: collapse; width: 100%; margin: 1rem 0; }
    td { padding: .35rem .6rem; border-bottom: 1px solid #8883; }
    td.s { text-align: right; font-variant: small-caps; }
    td.s.ok { color: #1b5e20; }
    td.s.down { color: #b71c1c; }
    .note { color: #888; font-size: .9rem; margin-top: 1.5rem; }
  </style>
</head>
<body>
  <h1>Tatrman Server</h1>
  <p class="meta">version <b>${esc(status.serverVersion)}</b> &middot; model fingerprint <b>${esc(fingerprint)}</b></p>
  <p class="overall $overallTone">$overallWord &mdash; ${status.summary.healthy}/${status.summary.total} services up</p>
  <table>
    <tbody>
$rows
    </tbody>
  </table>
  <p class="note">Read-only status page. The open Server ships no management app &mdash; operator tasks are
  Server config &amp; the CLI (see the ops guide). Governed cluster-internal verbs (model refresh) are
  not part of this operator surface.</p>
</body>
</html>
"""
    }

    private fun esc(s: String): String =
        s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
