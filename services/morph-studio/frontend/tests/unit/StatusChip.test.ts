// SPDX-License-Identifier: Apache-2.0
import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'

import StatusChip from '@/components/StatusChip.vue'
import { useMachine } from '@/composables/useMachine'

import { MACHINE, reset, serve } from './helpers'

beforeEach(async () => {
  serve({ 'GET /v1/machine': MACHINE })
  await useMachine().load()
})

afterEach(reset)

function chip(status: string) {
  return mount(StatusChip, { props: { status } })
}

describe('status chips are the machine, not a copy of it', () => {
  it('renders every status the service serves', () => {
    for (const status of MACHINE.statuses) {
      const wrapper = chip(status)
      expect(wrapper.text()).toBe(status)
      expect(wrapper.classes()).not.toContain('chip--unknown')
    }
  })

  it('distinguishes past-the-gate from working memory', () => {
    // The one distinction worth a colour: `verified` ships, `proposed` does not.
    expect(chip('verified').classes()).toContain('chip--exportable')
    expect(chip('published').classes()).toContain('chip--exportable')
    expect(chip('auto-validated').classes()).toContain('chip--working')
    expect(chip('proposed').classes()).toContain('chip--working')
  })

  it('marks rejected as terminal', () => {
    expect(chip('rejected').classes()).toContain('chip--terminal')
  })

  it('flags a status the service has never heard of instead of styling it as fine', () => {
    // Only reachable when this page is older than the service. Silence here
    // would render an unknown state as ordinary working memory.
    const wrapper = chip('approved')
    expect(wrapper.classes()).toContain('chip--unknown')
    expect(wrapper.attributes('title')).toContain('reload')
  })

  it('invents nothing: no status outside the served list renders as known', () => {
    for (const invented of ['approved', 'draft', 'pending', 'live']) {
      expect(chip(invented).classes()).toContain('chip--unknown')
    }
  })
})
