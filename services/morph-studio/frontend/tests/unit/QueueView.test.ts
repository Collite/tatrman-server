// SPDX-License-Identifier: Apache-2.0
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import { createRouter, createWebHistory } from 'vue-router'

import QueueView from '@/views/QueueView.vue'
import { routes } from '@/router'

import { MACHINE, calls, entry, fails, queueItem, reset, serve } from './helpers'

afterEach(reset)

function queue(items: unknown[] = [queueItem()]) {
  return { world: 'dfp', items, total: items.length }
}

async function view() {
  const router = createRouter({ history: createWebHistory(), routes })
  await router.push({ name: 'queue' })
  await router.isReady()
  const wrapper = mount(QueueView, { global: { plugins: [router] } })
  await flushPromises()
  await flushPromises()
  return wrapper
}

describe('FI-7 surfaces 3+4: the queue', () => {
  it('is one world’s — there is no world selector', async () => {
    // LM-5/S-4: this instance serves exactly one world, and asking for another
    // is a 400. A dropdown here would be an invitation to receive one.
    serve({ 'GET /v1/machine': MACHINE, 'GET /v1/queue': queue() })
    const wrapper = await view()
    expect(wrapper.text()).toContain('dfp')
    expect(wrapper.find('[data-test="world-selector"]').exists()).toBe(false)
  })

  it('shows the token, how often the front saw it, and what the cascade proposed', async () => {
    serve({ 'GET /v1/machine': MACHINE, 'GET /v1/queue': queue() })
    const wrapper = await view()

    const row = wrapper.get('[data-test="queue-row"]')
    expect(row.text()).toContain('Kauflandu')
    expect(wrapper.get('[data-test="queue-count"]').text()).toBe('12×')
    expect(wrapper.get('[data-test="proposal-lemma"]').text()).toBe('Kaufland')
    expect(wrapper.get('[data-test="proposal-source"]').text()).toContain('guesser')
    expect(wrapper.get('[data-test="proposal-source"]').text()).toContain('0.85')
  })

  it('says plainly when the cascade proposed nothing', async () => {
    serve({
      'GET /v1/machine': MACHINE,
      'GET /v1/queue': queue([
        queueItem({ cascade: { proposals: [], tier: 'human', agreed: false, notes: [] } }),
      ]),
    })
    const wrapper = await view()
    expect(wrapper.get('[data-test="proposal-none"]').text()).toContain('a person has to author')
  })

  it('draws status chips from the machine and nothing else', async () => {
    serve({ 'GET /v1/machine': MACHINE, 'GET /v1/queue': queue() })
    const wrapper = await view()
    const chip = wrapper.get('[data-test="status-chip"]')
    expect(chip.text()).toBe('auto-validated')
    expect(chip.classes()).not.toContain('chip--unknown')
  })

  it('marks an auto-validated world item as live under Q-7', async () => {
    serve({ 'GET /v1/machine': MACHINE, 'GET /v1/queue': queue() })
    const wrapper = await view()
    expect(wrapper.get('[data-test="runtime-provenance"]').text()).toBe('provisional')
  })

  it('shows LM-10’s routing and whether a person overrode it', async () => {
    serve({
      'GET /v1/machine': MACHINE,
      'GET /v1/queue': queue([queueItem({ routed_by: 'human', layer: 'core' })]),
    })
    const wrapper = await view()
    expect(wrapper.get('[data-test="routed-by"]').text()).toBe('human')
  })
})

describe('the three verdicts', () => {
  it('sends verify and reports what it did to the overlay', async () => {
    serve({
      'GET /v1/machine': MACHINE,
      'GET /v1/queue': queue(),
      'POST /v1/queue/7/verdict': {
        item: queueItem({ status: 'verified' }),
        entry: entry({ status: 'verified', provisional: false }),
        overlay_emitted: true,
        reload: 'reloaded 1 pack',
      },
    })
    const wrapper = await view()
    await wrapper.get('[data-test="verdict-verify"]').trigger('click')
    await flushPromises()

    expect(calls.find((call) => call.path === '/v1/queue/7/verdict')?.body).toMatchObject({
      action: 'verify',
    })
    const notice = wrapper.get('[data-test="queue-notice"]').text()
    expect(notice).toContain('Verified')
    expect(notice).toContain('overlay was re-emitted')
    expect(notice).toContain('reloaded 1 pack')
  })

  it('sends reject and says it is terminal', async () => {
    serve({
      'GET /v1/machine': MACHINE,
      'GET /v1/queue': queue(),
      'POST /v1/queue/7/verdict': { item: queueItem({ status: 'rejected' }), entry: null },
    })
    const wrapper = await view()
    await wrapper.get('[data-test="verdict-reject"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-test="queue-notice"]').text()).toContain('terminal')
  })

  it('offers the routing override, with the current layer not on offer', async () => {
    serve({
      'GET /v1/machine': MACHINE,
      'GET /v1/queue': queue(),
      'POST /v1/queue/7/verdict': { item: queueItem({ layer: 'core' }), entry: null },
    })
    const wrapper = await view()
    await wrapper.get('[data-test="verdict-route"]').trigger('click')

    expect(wrapper.get('[data-test="route-to-world"]').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-test="route-to-core"]').trigger('click')
    await flushPromises()

    expect(calls.find((call) => call.path === '/v1/queue/7/verdict')?.body).toMatchObject({
      action: 'route',
      layer: 'core',
    })
  })

  it('offers no verdict at all on a terminal item', async () => {
    // `rejected` is where one verdict becomes a permanent answer. A Verify
    // button here would be a 409 the reviewer has no way to read.
    serve({
      'GET /v1/machine': MACHINE,
      'GET /v1/queue': queue([queueItem({ status: 'rejected' })]),
    })
    const wrapper = await view()
    expect(wrapper.find('[data-test="verdict-verify"]').exists()).toBe(false)
    expect(wrapper.get('[data-test="verdict-done"]').text()).toBe('answered')
  })

  it('shows the service’s own sentence when it refuses', async () => {
    serve({
      'GET /v1/machine': MACHINE,
      'GET /v1/queue': queue(),
      'POST /v1/queue/7/verdict': fails(
        409,
        "the entry's paradigm does not contain 'Kauflandu' — verifying it would publish a pattern that cannot make the word this queue item is about (LM-14)",
      ),
    })
    const wrapper = await view()
    await wrapper.get('[data-test="verdict-verify"]').trigger('click')
    await flushPromises()

    const error = wrapper.get('[data-test="queue-error"]').text()
    expect(error).toContain('cannot make the word')
    expect(error).toContain('LM-14')
  })
})
