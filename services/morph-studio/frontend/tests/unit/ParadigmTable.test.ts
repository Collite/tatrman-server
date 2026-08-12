// SPDX-License-Identifier: Apache-2.0
import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'

import ParadigmTable from '@/components/ParadigmTable.vue'

import { KAUFLAND_FORMS, reset } from './helpers'

afterEach(reset)

describe('the paradigm, as a table somebody can check', () => {
  it('renders the generated forms in a grid', () => {
    const wrapper = mount(ParadigmTable, { props: { forms: KAUFLAND_FORMS } })
    const table = wrapper.get('[data-test="paradigm-table"]')
    expect(table.text()).toContain('Kauflandem')
    expect(table.text()).toContain('1. nom.')
    expect(table.text()).toContain('sg.')
    expect(table.text()).toContain('pl.')
  })

  it('says so when a pattern generated nothing', () => {
    const wrapper = mount(ParadigmTable, { props: { forms: [] } })
    expect(wrapper.find('[data-test="paradigm-empty"]').exists()).toBe(true)
  })

  it('is read-only unless asked otherwise', () => {
    const wrapper = mount(ParadigmTable, { props: { forms: KAUFLAND_FORMS } })
    expect(wrapper.findAll('input')).toHaveLength(0)
  })
})

describe('an edited cell is a full-form override', () => {
  it('marks the edited form corrected and emits the whole table', async () => {
    const wrapper = mount(ParadigmTable, {
      props: { forms: KAUFLAND_FORMS, editable: true },
    })

    const input = wrapper.findAll('input')[0]
    await input.setValue('Kauflande')

    const emitted = wrapper.emitted('update:forms')
    expect(emitted).toBeTruthy()
    const forms = emitted!.at(-1)![0] as { form: string; corrected?: boolean }[]
    const changed = forms.filter((form) => form.corrected)
    expect(changed).toHaveLength(1)
    expect(changed[0].form).toBe('Kauflande')
    // The rest of the table travels with it: `POST /forms` replaces the table,
    // so sending only the edited cell would delete every other form.
    expect(forms).toHaveLength(KAUFLAND_FORMS.length)
  })

  it('warns that saving makes this a full-form entry', async () => {
    const wrapper = mount(ParadigmTable, {
      props: { forms: KAUFLAND_FORMS, editable: true },
    })
    expect(wrapper.find('[data-test="override-note"]').exists()).toBe(false)

    await wrapper.findAll('input')[0].setValue('Kauflande')
    const note = wrapper.get('[data-test="override-note"]')
    expect(note.text()).toContain('full-form entry')
    expect(note.text()).toContain('vzor')
  })

  it('typing a cell back to what the pattern produces is not a correction', async () => {
    // ⚑ `corrected` means "the pattern does not produce this" — a claim about
    // the pattern's output, not about whether somebody touched the box. Getting
    // this wrong turns an entry into a full-form one for no reason, and export
    // then writes out thirty forms where a `vzor:` would have done.
    const wrapper = mount(ParadigmTable, {
      props: { forms: KAUFLAND_FORMS, editable: true },
    })
    const input = wrapper.findAll('input')[0]
    const original = (input.element as HTMLInputElement).value

    await input.setValue('Kauflande')
    await input.setValue(original)

    const forms = wrapper.emitted('update:forms')!.at(-1)![0] as { corrected?: boolean }[]
    expect(forms.some((form) => form.corrected)).toBe(false)
    expect(wrapper.find('[data-test="override-note"]').exists()).toBe(false)
  })
})
