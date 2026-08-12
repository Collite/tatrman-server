// SPDX-License-Identifier: Apache-2.0
// The typed client (NLS-P9.3 T1).
//
// `schema.d.ts` is GENERATED from `openapi.json` — `npm run api`, and
// `tests/test_openapi.py` in the backend fails if that document is stale. So
// every field a component reads is the field the service returns, checked at
// build time rather than discovered in a browser.
//
// This file is the hand-written half, and it is deliberately thin: paths and
// parameter names come from the generated `paths` type, so a renamed endpoint
// is a compile error here rather than a 404 at runtime. What it adds is one
// place where an error becomes an `ApiError` — the alternative is every view
// re-deciding what a 409 means, and the reviewer who double-clicks Verify
// seeing "[object Object]".

import createClient from 'openapi-fetch'

import type { components, paths } from './schema'

type Schemas = components['schemas']

export type Entry = Schemas['EntryModel']
/**
 * A form as the service SENDS it — every field present.
 *
 * `FormModel-Input` is the same model as a request body, where `feats` and
 * `corrected` may be omitted. The split is FastAPI's and it is the right one:
 * sending a corrected table without repeating `corrected: false` on every
 * untouched row is exactly what a request should allow.
 */
export type Form = Schemas['FormModel-Output']
export type FormInput = Schemas['FormModel-Input']
export type Proposal = Schemas['ProposalModel-Output']
export type Cascade = Schemas['CascadeModel']
export type QueueItem = Schemas['QueueItemModel']
export type QueueResponse = Schemas['QueueResponse']
export type LookupResponse = Schemas['LookupResponse']
export type Paradigm = Schemas['ParadigmModel']
export type Machine = Schemas['MachineResponse']
export type Vzory = Schemas['VzoryResponse']
export type Vzor = Schemas['VzorModel']
export type Status = Schemas['StatusResponse']
export type VerdictResponse = Schemas['VerdictResponse']
export type ExportResponse = Schemas['ExportResponse']
export type NewEntry = Schemas['NewEntry']

/** The three verdicts of FI-7 surface 3. Widened from the served list at runtime. */
export type VerdictAction = 'verify' | 'reject' | 'route'

/**
 * A request the service refused, carrying what it said and why that matters.
 *
 * The status codes are the backend's own translation of the status machine
 * (`api.py`): 409 means the caller is out of date and a refresh may make the
 * same click legal; 400 means it never was. Views act on that difference —
 * a 409 offers "reload", a 400 does not.
 */
export class ApiError extends Error {
  readonly status: number
  readonly stale: boolean

  constructor(status: number, detail: string) {
    super(detail)
    this.name = 'ApiError'
    this.status = status
    this.stale = status === 409
  }
}

/**
 * The API's origin.
 *
 * In production the backend serves this bundle, so the API is the page's own
 * origin and there is nothing to configure — which is also why there is no CORS
 * policy anywhere in this service. In development vite proxies `/v1` to the
 * backend, so the same origin-relative paths work unchanged; that they are
 * identical in both modes is what stops a dev-only base URL from shipping.
 *
 * ⚑ Absolute rather than `'/'`: `openapi-fetch` parses with `new URL`, which
 * refuses a relative base outside a browser — so a bare `/` passes in the app
 * and throws in every test.
 */
const ORIGIN =
  import.meta.env.VITE_API_BASE ||
  new URL('/', globalThis.location?.href ?? 'http://morph-studio.invalid/').toString()

const client = createClient<paths>({
  baseUrl: ORIGIN,
  // ⚑ Resolved per call, not captured at module load. `openapi-fetch` defaults
  // this to `globalThis.fetch` when the client is CREATED, which means anything
  // that replaces `fetch` afterwards — the test suite's stub, and any future
  // instrumentation wrapper — is silently bypassed and the tests reach for a
  // real socket.
  fetch: (request) => globalThis.fetch(request),
})

/** FastAPI's error body, in both of the shapes it produces. */
function detailOf(body: unknown, status: number): string {
  const detail = (body as { detail?: unknown } | null)?.detail
  if (typeof detail === 'string') return detail
  if (Array.isArray(detail)) {
    // A 422 from pydantic: a list of {loc, msg}. Readable beats complete.
    return detail
      .map((item: { loc?: unknown[]; msg?: string }) =>
        [item.loc?.slice(1).join('.'), item.msg].filter(Boolean).join(': '),
      )
      .join('; ')
  }
  return `request failed (HTTP ${status})`
}

function unwrap<T>(result: { data?: T; error?: unknown; response: Response }): T {
  if (result.error !== undefined || result.data === undefined) {
    throw new ApiError(result.response.status, detailOf(result.error, result.response.status))
  }
  return result.data
}

export const api = {
  /** The machine and the inventory — see `useMachine` / `useVzory` for the cached reads. */
  async machine(): Promise<Machine> {
    return unwrap(await client.GET('/v1/machine', {}))
  },

  async vzory(): Promise<Vzory> {
    return unwrap(await client.GET('/v1/vzory', {}))
  },

  async status(): Promise<Status> {
    return unwrap(await client.GET('/v1/status', {}))
  },

  // ── FI-7 surface 1 ────────────────────────────────────────────────────────

  async lookup(form: string): Promise<LookupResponse> {
    return unwrap(await client.GET('/v1/lookup/{form}', { params: { path: { form } } }))
  },

  // ── FI-7 surface 2 ────────────────────────────────────────────────────────

  async entry(id: number): Promise<Entry> {
    return unwrap(await client.GET('/v1/entries/{entry_id}', { params: { path: { entry_id: id } } }))
  },

  async entries(query: { status?: string; layer?: string; limit?: number } = {}): Promise<Entry[]> {
    return unwrap(await client.GET('/v1/entries', { params: { query } }))
  },

  async createEntry(body: NewEntry): Promise<Entry> {
    return unwrap(await client.POST('/v1/entries', { body }))
  },

  async tryPattern(
    id: number,
    body: { vzor: string; flags?: string[]; apply?: boolean },
  ): Promise<Paradigm> {
    return unwrap(
      await client.POST('/v1/entries/{entry_id}/try-pattern', {
        params: { path: { entry_id: id } },
        body: { flags: [], apply: false, ...body },
      }),
    )
  },

  async askLlm(id: number): Promise<Paradigm> {
    return unwrap(
      await client.POST('/v1/entries/{entry_id}/ask-llm', { params: { path: { entry_id: id } } }),
    )
  },

  async correctForms(id: number, forms: Form[]): Promise<Entry> {
    return unwrap(
      await client.POST('/v1/entries/{entry_id}/forms', {
        params: { path: { entry_id: id } },
        body: { forms, actor: 'human' },
      }),
    )
  },

  async setStatus(id: number, status: string, reason = ''): Promise<Entry> {
    return unwrap(
      await client.POST('/v1/entries/{entry_id}/status', {
        params: { path: { entry_id: id } },
        body: { status, reason, actor: 'human' },
      }),
    )
  },

  // ── FI-7 surfaces 3+4 ─────────────────────────────────────────────────────

  async queue(query: { status?: string; limit?: number } = {}): Promise<QueueResponse> {
    return unwrap(await client.GET('/v1/queue', { params: { query } }))
  },

  async verdict(
    id: number,
    action: VerdictAction,
    extra: { layer?: string; reason?: string } = {},
  ): Promise<VerdictResponse> {
    return unwrap(
      await client.POST('/v1/queue/{item_id}/verdict', {
        params: { path: { item_id: id } },
        body: { action, actor: 'human', reason: '', ...extra },
      }),
    )
  },

  async exportLayers(write = false): Promise<ExportResponse> {
    return unwrap(await client.POST('/v1/export', { body: { write } }))
  },
}

export type Api = typeof api
