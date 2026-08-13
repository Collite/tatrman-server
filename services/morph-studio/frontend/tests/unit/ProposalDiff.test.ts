// SPDX-License-Identifier: Apache-2.0
import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'

import ProposalDiff from '@/components/ProposalDiff.vue'

import { KAUFLAND_FORMS, reset } from './helpers'

afterEach(reset)

const CURRENT = { vzor: 'hrad', flags: [], forms: KAUFLAND_FORMS.slice(0, 2) }

function diff(proposal: Record<string, unknown>, current = CURRENT) {
  return mount(ProposalDiff, {
    props: {
      proposal: { vzor: 'hrad-proper', flags: [], forms: KAUFLAND_FORMS, validates: true, ...proposal },
      current,
    },
  })
}

describe('the LLM proposal is shown before it is accepted', () => {
  it('is a diff of patterns — old struck through, new beside it', () => {
    const wrapper = diff({})
    const vzor = wrapper.get('[data-test="diff-vzor"]')
    expect(vzor.text()).toContain('hrad')
    expect(vzor.text()).toContain('hrad-proper')
    expect(vzor.classes()).toContain('changed')
  })

  it('shows the forms it would add and drop', () => {
    const wrapper = diff({})
    expect(wrapper.get('[data-test="diff-added"]').text()).toContain('Kauflandem')
    expect(wrapper.find('[data-test="diff-removed"]').exists()).toBe(false)
  })

  it('says whether the pattern auto-validates, and what it means when it does not', () => {
    expect(diff({ validates: true }).get('[data-test="diff-validates"]').text()).toContain('yes')

    const no = diff({ validates: false }).get('[data-test="diff-validates"]').text()
    expect(no).toContain('no')
    expect(no).toContain('cannot make it')
  })

  it('changes nothing by being looked at — accepting is a separate click', async () => {
    const view = diff({})
    expect(view.emitted('accept')).toBeUndefined()

    await view.get('[data-test="accept-proposal"]').trigger('click')
    expect(view.emitted('accept')).toHaveLength(1)
  })

  it('can be dismissed', async () => {
    const view = diff({})
    await view.get('[data-test="dismiss-proposal"]').trigger('click')
    expect(view.emitted('dismiss')).toHaveLength(1)
  })

  it('offers nothing to accept when the classifier named the pattern already in use', () => {
    const view = diff(
      { vzor: 'hrad', forms: CURRENT.forms },
      { vzor: 'hrad', flags: [], forms: CURRENT.forms },
    )
    expect(view.find('[data-test="diff-identical"]').exists()).toBe(true)
    expect(view.get('[data-test="accept-proposal"]').attributes('disabled')).toBeDefined()
  })
})
