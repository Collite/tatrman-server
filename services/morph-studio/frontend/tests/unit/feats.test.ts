// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'

import { CASES, formatFeats, grid, parseFeats } from '@/feats'

describe('reading feature strings', () => {
  it('parses UD pairs', () => {
    expect(parseFeats('Case=Loc|Number=Sing')).toEqual({ Case: 'Loc', Number: 'Sing' })
  })

  it('reads out grammatically, not alphabetically', () => {
    expect(formatFeats('Animacy=Inan|Case=Loc|Gender=Masc|Number=Sing')).toBe(
      '6. lok. sg. m. než.',
    )
  })

  it('keeps a feature it has never heard of rather than hiding it', () => {
    // The tables are data (LM-2) and may grow one. Two different cells rendered
    // identically is worse than an unfamiliar `Key=Value`.
    const label = formatFeats('Case=Nom|Number=Sing|Foreign=Yes')
    expect(label).toContain('Foreign=Yes')
    expect(label.startsWith('1. nom. sg.')).toBe(true)
  })
})

describe('the paradigm grid', () => {
  const forms = [
    { form: 'hrad', feats: 'Case=Nom|Number=Sing' },
    { form: 'hradu', feats: 'Case=Gen|Number=Sing' },
    { form: 'hrady', feats: 'Case=Nom|Number=Plur' },
  ]

  it('is the textbook table: cases down, number across', () => {
    const table = grid(forms)
    expect(table.kind).toBe('case')
    expect(table.columns.map((c) => c.key)).toEqual(['Sing', 'Plur'])
    expect(table.rows.map((r) => r.key)).toEqual(['Nom', 'Gen'])
    expect(table.rows[0].columns.Sing).toEqual(['hrad'])
    expect(table.rows[0].columns.Plur).toEqual(['hrady'])
    expect(table.rows[1].columns.Plur).toEqual([])
  })

  it('orders cases 1.–7., not the way UD spells them', () => {
    expect(CASES).toEqual(['Nom', 'Gen', 'Dat', 'Acc', 'Voc', 'Loc', 'Ins'])
    const table = grid([
      { form: 'a', feats: 'Case=Ins|Number=Sing' },
      { form: 'b', feats: 'Case=Acc|Number=Sing' },
      { form: 'c', feats: 'Case=Nom|Number=Sing' },
    ])
    expect(table.rows.map((r) => r.key)).toEqual(['Nom', 'Acc', 'Ins'])
  })

  it('keeps both variants when a cell has two forms', () => {
    const table = grid([
      { form: 'Kauflandu', feats: 'Case=Loc|Number=Sing' },
      { form: 'Kauflandě', feats: 'Case=Loc|Number=Sing' },
    ])
    expect(table.rows[0].columns.Sing).toEqual(['Kauflandu', 'Kauflandě'])
  })

  it('falls back to a list for something with neither case nor person', () => {
    // Indeclinables and infinitives are genuinely lists; a one-cell grid would
    // be a worse lie than admitting it.
    const table = grid([{ form: 'atašé', feats: '' }])
    expect(table.kind).toBe('flat')
    expect(table.rows).toHaveLength(1)
  })

  it('builds a person grid for verbs', () => {
    const table = grid([
      { form: 'zobrazím', feats: 'Person=1|Number=Sing' },
      { form: 'zobrazíme', feats: 'Person=1|Number=Plur' },
    ])
    expect(table.kind).toBe('person')
    expect(table.rows[0].columns.Plur).toEqual(['zobrazíme'])
  })
})
