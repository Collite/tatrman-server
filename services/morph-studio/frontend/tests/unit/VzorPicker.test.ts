// SPDX-License-Identifier: Apache-2.0
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'

import VzorPicker from '@/components/VzorPicker.vue'

import { VZORY, reset, serve } from './helpers'

beforeEach(() => serve({ 'GET /v1/vzory': VZORY }))
afterEach(reset)

async function picker(props: Record<string, unknown> = {}) {
  const wrapper = mount(VzorPicker, { props: { vzor: '', flags: [], ...props } })
  await flushPromises()
  return wrapper
}

describe('the picker is constrained to the engine inventory', () => {
  it('offers the served patterns and nothing else', async () => {
    const wrapper = await picker()
    const options = wrapper
      .get('[data-test="vzor-picker"]')
      .findAll('option')
      .map((option) => option.attributes('value'))
    expect(options).toEqual(['', 'hrad', 'hrad-proper', 'žena'])
  })

  it('has no free-text pattern field anywhere', async () => {
    // A pattern the engine does not have is a 400 from the backend. Letting
    // somebody type one is a wasted round trip and a wasted decision.
    const wrapper = await picker()
    expect(wrapper.findAll('input[type="text"]')).toHaveLength(0)
    expect(wrapper.find('select').exists()).toBe(true)
  })

  it('narrows to the entry’s part of speech', async () => {
    const wrapper = await picker({ upos: 'PROPN' })
    const options = wrapper.findAll('option').map((option) => option.attributes('value'))
    expect(options).toEqual(['', 'hrad-proper'])
  })

  it('shows the B-O5 hints, which are what tell an analyst which sub-vzor they want', async () => {
    const wrapper = await picker({ vzor: 'hrad-proper' })
    const hint = wrapper.get('[data-test="vzor-hint"]').text()
    expect(hint).toContain('hrad')
    expect(hint).toContain('capitalised')
    expect(hint).toContain('lemma matches')
  })

  it('renders a boolean hint as a fact, not as the string "true"', async () => {
    // ⚑ `capitalized: true` arriving as the STRING "True" is truthy in
    // TypeScript, so every pattern would read as capitalised and no assertion
    // outside this one would notice. The backend types the hint values for
    // exactly this reason.
    const wrapper = await picker({ vzor: 'žena' })
    expect(wrapper.get('[data-test="vzor-hint"]').text()).not.toContain('capitalised')
  })

  it('builds the flag checkboxes from the tables’ flag vocabulary', async () => {
    const wrapper = await picker()
    expect(wrapper.find('[data-test="flag-fleeting-e"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="flag-no-vocative"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="flag-invented"]').exists()).toBe(false)
  })

  it('emits the chosen pattern and the toggled flags', async () => {
    const wrapper = await picker()
    await wrapper.get('[data-test="vzor-picker"]').setValue('hrad')
    expect(wrapper.emitted('update:vzor')!.at(-1)).toEqual(['hrad'])

    await wrapper.get('[data-test="flag-fleeting-e"]').setValue(true)
    expect(wrapper.emitted('update:flags')!.at(-1)).toEqual([['fleeting-e']])
  })
})
