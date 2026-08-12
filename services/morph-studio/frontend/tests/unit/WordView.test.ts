// SPDX-License-Identifier: Apache-2.0
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import { createRouter, createWebHistory } from 'vue-router'

import WordView from '@/views/WordView.vue'
import { routes } from '@/router'

import { KAUFLAND_FORMS, MACHINE, entry, reset, serve } from './helpers'

afterEach(reset)

async function view(form = 'Kauflandu') {
  const router = createRouter({ history: createWebHistory(), routes })
  await router.push({ name: 'word', params: { form } })
  await router.isReady()
  const wrapper = mount(WordView, { global: { plugins: [router] } })
  await flushPromises()
  await flushPromises()
  return wrapper
}

const FOUND = {
  form: 'Kauflandu',
  matched_via: 'exact',
  entries: [entry()],
}

describe('FI-7 surface 1: look a word up', () => {
  it('searches by form and lists what is known', async () => {
    serve({ 'GET /v1/machine': MACHINE, 'GET /v1/lookup/Kauflandu': FOUND })
    const wrapper = await view()

    const entries = wrapper.findAll('[data-test="lookup-entry"]')
    expect(entries).toHaveLength(1)
    expect(entries[0].text()).toContain('Kaufland')
    expect(entries[0].text()).toContain('hrad-proper')
  })

  it('says which cell of the paradigm the searched form is', async () => {
    // The whole point of searching by form: the analyst typed an inflected word
    // and wants to know what it is, not just which lemma it belongs to.
    serve({ 'GET /v1/machine': MACHINE, 'GET /v1/lookup/Kauflandu': FOUND })
    const wrapper = await view()
    expect(wrapper.get('[data-test="matched-feats"]').text()).toContain('2. gen.')
  })

  it('flags a folded match, because the stored spelling differs from what was typed', async () => {
    serve({
      'GET /v1/machine': MACHINE,
      'GET /v1/lookup/kauflandu': { ...FOUND, form: 'kauflandu', matched_via: 'folded' },
    })
    const wrapper = await view('kauflandu')
    expect(wrapper.find('[data-test="matched-via-folded"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="matched-via-exact"]').exists()).toBe(false)
  })

  it('says an exact match was exact', async () => {
    serve({ 'GET /v1/machine': MACHINE, 'GET /v1/lookup/Kauflandu': FOUND })
    const wrapper = await view()
    expect(wrapper.find('[data-test="matched-via-exact"]').exists()).toBe(true)
  })

  it('links into the entry editor', async () => {
    serve({ 'GET /v1/machine': MACHINE, 'GET /v1/lookup/Kauflandu': FOUND })
    const wrapper = await view()
    expect(wrapper.get('[data-test="entry-link"]').attributes('href')).toBe('/entry/1')
  })

  it('shows the provenance badges — including provisional, which is Q-7', async () => {
    serve({ 'GET /v1/machine': MACHINE, 'GET /v1/lookup/Kauflandu': FOUND })
    const wrapper = await view()
    expect(wrapper.get('[data-test="runtime-provenance"]').text()).toBe('provisional')
    expect(wrapper.get('[data-test="editorial-provenance"]').text()).toContain('manual')
  })

  it('ranks a verified entry above a proposed one', async () => {
    serve({
      'GET /v1/machine': MACHINE,
      'GET /v1/lookup/Kauflandu': {
        ...FOUND,
        entries: [
          entry({ id: 1, lemma: 'Kauflandu', status: 'proposed', provisional: false }),
          entry({ id: 2, lemma: 'Kaufland', status: 'verified', provisional: false }),
        ],
      },
    })
    const wrapper = await view()
    const links = wrapper.findAll('[data-test="entry-link"]').map((link) => link.text())
    expect(links).toEqual(['Kaufland', 'Kauflandu'])
  })

  it('a miss is an answer, not an error', async () => {
    serve({
      'GET /v1/machine': MACHINE,
      'GET /v1/lookup/tržbami': { form: 'tržbami', matched_via: 'none', entries: [] },
    })
    const wrapper = await view('tržbami')
    expect(wrapper.find('[data-test="lookup-miss"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="lookup-error"]').exists()).toBe(false)
  })

  it('shows the paradigm on request', async () => {
    serve({ 'GET /v1/machine': MACHINE, 'GET /v1/lookup/Kauflandu': FOUND })
    const wrapper = await view()
    expect(wrapper.find('[data-test="paradigm-table"]').exists()).toBe(false)

    await wrapper.get('[data-test="toggle-paradigm"]').trigger('click')
    const table = wrapper.get('[data-test="paradigm-table"]')
    for (const row of KAUFLAND_FORMS) expect(table.text()).toContain(row.form)
  })
})
