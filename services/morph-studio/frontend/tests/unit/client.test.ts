// SPDX-License-Identifier: Apache-2.0
import { afterEach, describe, expect, it } from 'vitest'

import { ApiError, api } from '@/api/client'

import { calls, entry, fails, reset, serve } from './helpers'

afterEach(reset)

describe('the generated client', () => {
  it('builds paths from the OpenAPI document, not from string concatenation', async () => {
    serve({ 'GET /v1/lookup/Kauflandu': { form: 'Kauflandu', matched_via: 'exact', entries: [] } })
    await api.lookup('Kauflandu')
    expect(calls[0]).toMatchObject({ method: 'GET', path: '/v1/lookup/Kauflandu' })
  })

  it('sends the verdict body the service expects', async () => {
    serve({ 'POST /v1/queue/7/verdict': { item: {}, entry: null } })
    await api.verdict(7, 'route', { layer: 'core' })
    expect(calls[0].body).toEqual({ action: 'route', actor: 'human', reason: '', layer: 'core' })
  })

  it('defaults try-pattern to a preview — "try" must not change the entry', async () => {
    serve({ 'POST /v1/entries/1/try-pattern': { vzor: 'hrad', flags: [], forms: [], validates: false } })
    await api.tryPattern(1, { vzor: 'hrad' })
    expect(calls[0].body).toEqual({ vzor: 'hrad', flags: [], apply: false })
  })
})

describe('what the service refused', () => {
  it('carries the backend’s sentence, not a status code', async () => {
    serve({
      'POST /v1/queue/7/verdict': fails(
        409,
        "the entry's paradigm does not contain 'Kauflandu'",
      ),
    })
    await expect(api.verdict(7, 'verify')).rejects.toThrowError(
      /paradigm does not contain 'Kauflandu'/,
    )
  })

  it('marks a 409 stale and a 400 not', async () => {
    serve({ 'POST /v1/entries/1/status': fails(409, 'out of date') })
    const stale = await api.setStatus(1, 'published').catch((error: ApiError) => error)
    expect((stale as ApiError).stale).toBe(true)

    serve({ 'POST /v1/entries/1/status': fails(400, "'approved' is not a status") })
    const wrong = await api.setStatus(1, 'approved').catch((error: ApiError) => error)
    expect((wrong as ApiError).stale).toBe(false)
  })

  it('flattens pydantic’s validation list into something readable', async () => {
    // FastAPI answers a malformed body with a LIST, not a sentence. Rendered
    // as-is that reaches a reviewer as "[object Object]".
    serve({
      'POST /v1/entries': fails(422, [
        { loc: ['body', 'upos'], msg: 'Field required' },
        { loc: ['body', 'lemma'], msg: 'Input should be a valid string' },
      ]),
    })
    const error = await api
      .createEntry({ lemma: '', upos: '' })
      .catch((caught: ApiError) => caught)
    expect((error as ApiError).message).toBe(
      'upos: Field required; lemma: Input should be a valid string',
    )
  })

  it('reads the entry the service returned', async () => {
    serve({ 'GET /v1/entries/1': entry() })
    const loaded = await api.entry(1)
    expect(loaded.lemma).toBe('Kaufland')
    expect(loaded.provisional).toBe(true)
  })
})
