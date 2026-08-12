// SPDX-License-Identifier: Apache-2.0
// A stand-in for the service, and the fixtures the suite judges against.
//
// The client talks to `fetch`, so the seam is `fetch` — not a hand-rolled mock
// of the client module. That way the generated paths, the parameter names and
// the error translation in `client.ts` are all exercised by every component
// test rather than stubbed past, and a renamed endpoint fails here too.

import { vi } from 'vitest'

import { resetMachine } from '@/composables/useMachine'
import { resetVzory } from '@/composables/useVzory'

export type Route = (request: { url: URL; method: string; body: unknown }) => unknown

export interface Recorded {
  method: string
  path: string
  body: unknown
}

export const calls: Recorded[] = []

/**
 * Install a fetch that answers from `routes`, keyed `"METHOD /path"`.
 *
 * A path with no route is a *test* failure, not a 404: it means a component
 * called something the test did not expect, which is exactly the thing worth
 * hearing about.
 */
export function serve(routes: Record<string, unknown | Route>): void {
  calls.length = 0
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = input instanceof Request ? input : new Request(String(input), init)
      const url = new URL(request.url, 'http://studio.test')
      const method = request.method.toUpperCase()
      const raw = init?.body ?? (request.bodyUsed ? undefined : await safeText(request))
      const body = typeof raw === 'string' && raw ? JSON.parse(raw) : undefined
      calls.push({ method, path: decodeURIComponent(url.pathname), body })

      const key = `${method} ${decodeURIComponent(url.pathname)}`
      if (!(key in routes)) {
        throw new Error(
          `the component called ${key}, which this test did not stub. Known: ${Object.keys(routes).join(', ')}`,
        )
      }
      const route = routes[key]
      const value = typeof route === 'function' ? (route as Route)({ url, method, body }) : route
      if (value instanceof Failure) {
        return new Response(JSON.stringify({ detail: value.detail }), {
          status: value.status,
          headers: { 'content-type': 'application/json' },
        })
      }
      return new Response(JSON.stringify(value), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      })
    }),
  )
}

async function safeText(request: Request): Promise<string> {
  try {
    return await request.text()
  } catch {
    return ''
  }
}

/**
 * An error response, so a test can say what the service refused and why.
 *
 * `detail` is deliberately `unknown`: FastAPI writes a string for the errors
 * this service raises and a list of `{loc, msg}` for pydantic's, and the client
 * has to read both.
 */
export class Failure {
  constructor(
    readonly status: number,
    readonly detail: unknown,
  ) {}
}

export function fails(status: number, detail: unknown): Failure {
  return new Failure(status, detail)
}

/** Module-scoped caches are the point of the composables; tests start clean. */
export function reset(): void {
  resetMachine()
  resetVzory()
  calls.length = 0
  vi.unstubAllGlobals()
}

// ── fixtures ─────────────────────────────────────────────────────────────────

/** The machine, exactly as `GET /v1/machine` serves it. */
export const MACHINE = {
  statuses: ['proposed', 'auto-validated', 'verified', 'published', 'rejected', 'shadowed'],
  transitions: {
    proposed: ['auto-validated', 'rejected', 'verified'],
    'auto-validated': ['rejected', 'verified'],
    verified: ['published', 'rejected', 'shadowed'],
    published: ['rejected', 'shadowed'],
    shadowed: ['published', 'rejected'],
    rejected: [],
  },
  exportable: ['published', 'shadowed', 'verified'],
  terminal: ['rejected'],
  actions: ['verify', 'reject', 'route'],
  layers: ['core', 'world'],
}

export const VZORY = {
  language: 'cs',
  flags: ['fleeting-e', 'no-vocative'],
  vzory: [
    {
      name: 'hrad',
      upos: 'NOUN',
      parent: null,
      implied_flags: [],
      hints: { lemma_pattern: '[bcdfghjklmnprstvzčřšž]$' },
    },
    {
      name: 'hrad-proper',
      upos: 'PROPN',
      parent: 'hrad',
      implied_flags: [],
      hints: { capitalized: true, lemma_pattern: '[bcdfghjklmnprstvzčřšž]$' },
    },
    { name: 'žena', upos: 'NOUN', parent: null, implied_flags: [], hints: { lemma_pattern: 'a$' } },
  ],
}

/** Kaufland through `hrad-proper` — the paradigm of detailed-design §9. */
export const KAUFLAND_FORMS = [
  { form: 'Kaufland', feats: 'Animacy=Inan|Case=Nom|Gender=Masc|Number=Sing', corrected: false },
  { form: 'Kauflandu', feats: 'Animacy=Inan|Case=Gen|Gender=Masc|Number=Sing', corrected: false },
  { form: 'Kauflandu', feats: 'Animacy=Inan|Case=Dat|Gender=Masc|Number=Sing', corrected: false },
  { form: 'Kaufland', feats: 'Animacy=Inan|Case=Acc|Gender=Masc|Number=Sing', corrected: false },
  { form: 'Kauflandem', feats: 'Animacy=Inan|Case=Ins|Gender=Masc|Number=Sing', corrected: false },
  { form: 'Kauflandy', feats: 'Animacy=Inan|Case=Nom|Gender=Masc|Number=Plur', corrected: false },
]

export function entry(overrides: Record<string, unknown> = {}) {
  return {
    id: 1,
    lemma: 'Kaufland',
    upos: 'PROPN',
    layer: 'world',
    vzor: 'hrad-proper',
    flags: [],
    status: 'auto-validated',
    provenance: 'manual',
    source: 'guesser',
    confidence: 0.85,
    provisional: true,
    forms: KAUFLAND_FORMS,
    ...overrides,
  }
}

export function queueItem(overrides: Record<string, unknown> = {}) {
  return {
    id: 7,
    world: 'dfp',
    token: 'Kauflandu',
    verdict: 'miss',
    count: 12,
    context_span: '',
    status: 'auto-validated',
    layer: 'world',
    routed_by: 'auto',
    entry_id: 1,
    cascade: {
      proposals: [
        {
          lemma: 'Kaufland',
          upos: 'PROPN',
          vzor: 'hrad-proper',
          flags: [],
          confidence: 0.85,
          source: 'guesser',
        },
      ],
      tier: 'guesser',
      agreed: false,
      notes: [],
    },
    ...overrides,
  }
}
