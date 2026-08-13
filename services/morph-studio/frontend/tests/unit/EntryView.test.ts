// SPDX-License-Identifier: Apache-2.0
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import { createRouter, createWebHistory } from 'vue-router'

import EntryView from '@/views/EntryView.vue'
import { routes } from '@/router'

import { KAUFLAND_FORMS, MACHINE, VZORY, calls, entry, fails, reset, serve } from './helpers'

afterEach(reset)

const PARADIGM = {
  vzor: 'hrad-proper',
  flags: [],
  forms: KAUFLAND_FORMS,
  validates: true,
}

async function view(routes_: Record<string, unknown> = {}) {
  serve({
    'GET /v1/machine': MACHINE,
    'GET /v1/vzory': VZORY,
    'GET /v1/entries/1': entry(),
    ...routes_,
  })
  const router = createRouter({ history: createWebHistory(), routes })
  await router.push({ name: 'entry', params: { id: '1' } })
  await router.isReady()
  const wrapper = mount(EntryView, { global: { plugins: [router] } })
  await flushPromises()
  await flushPromises()
  return wrapper
}

describe('FI-7 surface 2: the entry editor', () => {
  it('shows what the entry is and how it got here', async () => {
    const wrapper = await view()
    expect(wrapper.text()).toContain('Kaufland')
    expect(wrapper.get('[data-test="status-chip"]').text()).toBe('auto-validated')
    expect(wrapper.get('[data-test="entry-source"]').text()).toContain('guesser')
  })

  it('says out loud when an entry is live and unverified', async () => {
    const wrapper = await view()
    const banner = wrapper.get('[data-test="entry-provisional"]').text()
    expect(banner).toContain('Live now, unverified')
    expect(banner).toContain('Verify')
    expect(banner).toContain('retracts')
  })
})

describe('try a pattern', () => {
  it('previews the generated table without changing the entry', async () => {
    const wrapper = await view({ 'POST /v1/entries/1/try-pattern': PARADIGM })
    await wrapper.get('[data-test="vzor-picker"]').setValue('hrad-proper')
    await wrapper.get('[data-test="try-pattern"]').trigger('click')
    await flushPromises()

    expect(calls.at(-1)?.body).toMatchObject({ apply: false })
    expect(wrapper.get('[data-test="paradigm-table"]').text()).toContain('Kauflandem')
    expect(wrapper.find('[data-test="preview-validates"]').exists()).toBe(true)
  })

  it('says when a pattern does not produce the observed word', async () => {
    // A NOUN entry, because the picker narrows to the entry's part of speech —
    // `žena` is not on offer for a PROPN and the Try button stays disabled.
    const wrapper = await view({
      'GET /v1/entries/1': entry({ upos: 'NOUN', vzor: 'hrad', layer: 'core' }),
      'POST /v1/entries/1/try-pattern': { ...PARADIGM, validates: false },
    })
    await wrapper.get('[data-test="vzor-picker"]').setValue('žena')
    await wrapper.get('[data-test="try-pattern"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-test="preview-invalidates"]').text()).toContain(
      'does not produce the observed form',
    )
  })

  it('keeps it only on a second, separate click', async () => {
    const wrapper = await view({ 'POST /v1/entries/1/try-pattern': PARADIGM })
    await wrapper.get('[data-test="vzor-picker"]').setValue('hrad-proper')

    expect(wrapper.get('[data-test="use-pattern"]').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-test="try-pattern"]').trigger('click')
    await flushPromises()

    await wrapper.get('[data-test="use-pattern"]').trigger('click')
    await flushPromises()
    expect(
      calls.filter((call) => call.path === '/v1/entries/1/try-pattern').at(-1)?.body,
    ).toMatchObject({ apply: true })
  })

  it('drops a preview when the pattern under it changes', async () => {
    // ⚑ Otherwise "Use this" applies a pattern the analyst is no longer
    // looking at: the table on screen belongs to the previous choice.
    const wrapper = await view({ 'POST /v1/entries/1/try-pattern': PARADIGM })
    await wrapper.get('[data-test="vzor-picker"]').setValue('hrad-proper')
    await wrapper.get('[data-test="try-pattern"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="preview-validates"]').exists()).toBe(true)

    await wrapper.get('[data-test="vzor-picker"]').setValue('žena')
    await flushPromises()
    expect(wrapper.find('[data-test="preview-validates"]').exists()).toBe(false)
    expect(wrapper.get('[data-test="use-pattern"]').attributes('disabled')).toBeDefined()
  })

  it('shows the engine’s refusal rather than a status code', async () => {
    const wrapper = await view({
      'GET /v1/entries/1': entry({ upos: 'NOUN', vzor: 'hrad', layer: 'core' }),
      'POST /v1/entries/1/try-pattern': fails(
        400,
        "'Kaufland' cannot take vzor 'žena' — the pattern expects a different citation form",
      ),
    })
    await wrapper.get('[data-test="vzor-picker"]').setValue('žena')
    await wrapper.get('[data-test="try-pattern"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-test="entry-error"]').text()).toContain('different citation form')
  })
})

describe('ask the LLM', () => {
  it('shows the proposal as a diff and saves nothing', async () => {
    const wrapper = await view({ 'POST /v1/entries/1/ask-llm': { ...PARADIGM, vzor: 'hrad' } })
    await wrapper.get('[data-test="ask-llm"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="proposal-diff"]').exists()).toBe(true)
    expect(calls.some((call) => call.path.endsWith('/try-pattern'))).toBe(false)
  })

  it('applies it only when a person accepts', async () => {
    const wrapper = await view({
      'POST /v1/entries/1/ask-llm': { ...PARADIGM, vzor: 'hrad' },
      'POST /v1/entries/1/try-pattern': { ...PARADIGM, vzor: 'hrad' },
    })
    await wrapper.get('[data-test="ask-llm"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="accept-proposal"]').trigger('click')
    await flushPromises()

    const applied = calls.filter((call) => call.path === '/v1/entries/1/try-pattern').at(-1)
    expect(applied?.body).toMatchObject({ vzor: 'hrad', apply: true })
  })

  it('reports an air-gapped deployment as an arrangement, not a fault', async () => {
    const wrapper = await view({
      'POST /v1/entries/1/ask-llm': fails(
        503,
        'no LLM leg is configured (MORPH_LLM_URL) — this deployment runs guesser → human, which is a supported arrangement and not a fault',
      ),
    })
    await wrapper.get('[data-test="ask-llm"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-test="entry-error"]').text()).toContain('supported arrangement')
  })
})

describe('type the forms by hand', () => {
  it('saves a corrected table and says what that makes the entry', async () => {
    const wrapper = await view({
      'POST /v1/entries/1/forms': entry({
        forms: [{ ...KAUFLAND_FORMS[0], form: 'Kauflande', corrected: true }],
      }),
    })
    expect(wrapper.get('[data-test="save-forms"]').attributes('disabled')).toBeDefined()

    await wrapper.findAll('input.cell')[0].setValue('Kauflande')
    await flushPromises()
    await wrapper.get('[data-test="save-forms"]').trigger('click')
    await flushPromises()

    const body = calls.find((call) => call.path === '/v1/entries/1/forms')?.body as {
      forms: { corrected?: boolean }[]
    }
    expect(body.forms.some((form) => form.corrected)).toBe(true)
    expect(wrapper.get('[data-test="entry-notice"]').text()).toContain('full-form entry')
    expect(wrapper.find('[data-test="is-full-form"]').exists()).toBe(true)
  })
})

describe('the status buttons are the machine’s edges', () => {
  it('offers exactly the transitions out of where the entry is', async () => {
    const wrapper = await view()
    // auto-validated -> verified | rejected. Not published: that edge does not
    // exist, and its absence IS the export gate.
    expect(wrapper.find('[data-test="to-verified"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="to-rejected"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="to-published"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="to-auto-validated"]').exists()).toBe(false)
  })

  it('offers nothing on a terminal entry', async () => {
    const wrapper = await view({ 'GET /v1/entries/1': entry({ status: 'rejected' }) })
    expect(wrapper.get('[data-test="no-transitions"]').text()).toContain('terminal')
    expect(wrapper.find('[data-test="to-verified"]').exists()).toBe(false)
  })

  it('sends the transition it drew', async () => {
    const wrapper = await view({
      'POST /v1/entries/1/status': entry({ status: 'verified', provisional: false }),
    })
    await wrapper.get('[data-test="to-verified"]').trigger('click')
    await flushPromises()

    expect(calls.find((call) => call.path === '/v1/entries/1/status')?.body).toMatchObject({
      status: 'verified',
    })
    expect(wrapper.find('[data-test="entry-provisional"]').exists()).toBe(false)
  })

  it('tells a stale page to reload rather than repeating a sentence it cannot act on', async () => {
    const wrapper = await view({
      'POST /v1/entries/1/status': fails(409, "'rejected' cannot become 'verified'"),
    })
    await wrapper.get('[data-test="to-verified"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-test="entry-error"]').text()).toContain('reload and try again')
  })
})
