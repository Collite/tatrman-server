// SPDX-License-Identifier: Apache-2.0
// Reading UD feature strings the way a Czech lexicographer does.
//
// The engine emits `Animacy=Inan|Case=Loc|Gender=Masc|Number=Sing`. That is the
// right thing to store — it is what the layer files and the runtime carry — and
// the wrong thing to show an analyst who is checking whether the locative
// singular is *Kauflandu* or *Kauflandě*.
//
// ⚑ The case order here is the Czech textbook one (1.–7. pád), not UD's
// alphabetical. Both are "correct"; only one lets somebody who learned these
// tables at school check a paradigm at a glance, and B-O5's whole premise is
// that the sub-vzor inventory maps 1:1 onto those tables (design §B).

export type Feats = Record<string, string>

/** 1. nominativ … 7. instrumentál. */
export const CASES = ['Nom', 'Gen', 'Dat', 'Acc', 'Voc', 'Loc', 'Ins'] as const
export const NUMBERS = ['Sing', 'Plur'] as const
export const PERSONS = ['1', '2', '3'] as const

const CASE_LABEL: Record<string, string> = {
  Nom: '1. nom.',
  Gen: '2. gen.',
  Dat: '3. dat.',
  Acc: '4. ak.',
  Voc: '5. vok.',
  Loc: '6. lok.',
  Ins: '7. instr.',
}

const NUMBER_LABEL: Record<string, string> = { Sing: 'sg.', Plur: 'pl.' }

const SHORT: Record<string, Record<string, string>> = {
  Gender: { Masc: 'm.', Fem: 'f.', Neut: 'n.' },
  Animacy: { Anim: 'živ.', Inan: 'než.' },
  Number: NUMBER_LABEL,
  Case: CASE_LABEL,
  Person: { '1': '1. os.', '2': '2. os.', '3': '3. os.' },
  Tense: { Past: 'min.', Pres: 'přít.', Fut: 'bud.' },
  Degree: { Pos: 'pozit.', Cmp: 'komp.', Sup: 'superl.' },
  VerbForm: { Inf: 'infinitiv', Part: 'příčestí', Conv: 'přechodník' },
  Polarity: { Neg: 'neg.' },
  Aspect: { Imp: 'nedok.', Perf: 'dok.' },
}

/** The order features are read out in — grammatical, not alphabetical. */
const ORDER = [
  'VerbForm',
  'Person',
  'Case',
  'Number',
  'Gender',
  'Animacy',
  'Degree',
  'Tense',
  'Aspect',
  'Polarity',
]

export function parseFeats(feats: string): Feats {
  const parsed: Feats = {}
  for (const pair of feats.split('|')) {
    const [key, value] = pair.split('=')
    if (key && value) parsed[key] = value
  }
  return parsed
}

/**
 * A readable label — `"6. lok. sg. m. než."`.
 *
 * Unknown features are kept as `Key=Value` rather than dropped: the tables are
 * data (LM-2) and may grow a feature this map has never heard of, and an
 * analyst seeing a raw pair knows something is new. Silently hiding it would
 * make two different cells look identical.
 */
export function formatFeats(feats: string): string {
  const parsed = parseFeats(feats)
  const seen = new Set<string>()
  const parts: string[] = []
  for (const key of ORDER) {
    const value = parsed[key]
    if (value === undefined) continue
    seen.add(key)
    parts.push(SHORT[key]?.[value] ?? `${key}=${value}`)
  }
  for (const [key, value] of Object.entries(parsed)) {
    if (!seen.has(key)) parts.push(`${key}=${value}`)
  }
  return parts.join(' ')
}

export interface Cell {
  /** The row label — a case, a person, or the whole feature string. */
  key: string
  label: string
  /** Column -> the forms in it. A cell holds more than one when the pattern
   *  generates variants (`Kauflandu` / `Kauflandě` both being locatives). */
  columns: Record<string, string[]>
}

export interface Grid {
  /** `case` (nominal), `person` (verbal), or `flat` when neither applies. */
  kind: 'case' | 'person' | 'flat'
  columns: { key: string; label: string }[]
  rows: Cell[]
}

/**
 * The textbook grid: cases down, number across.
 *
 * A flat list of thirty strings is not a paradigm anybody can check. The
 * fallback to `flat` is not a failure either — indeclinables, infinitives and
 * anything the tables grow that has neither a case nor a person are genuinely
 * lists, and inventing a one-cell grid for them would be worse.
 */
export function grid(forms: { form: string; feats: string; corrected?: boolean }[]): Grid {
  const parsed = forms.map((row) => ({ ...row, f: parseFeats(row.feats) }))
  const kind: Grid['kind'] = parsed.some((row) => row.f.Case)
    ? 'case'
    : parsed.some((row) => row.f.Person)
      ? 'person'
      : 'flat'

  if (kind === 'flat') {
    return {
      kind,
      columns: [{ key: 'form', label: 'tvar' }],
      rows: parsed.map((row) => ({
        key: row.feats || row.form,
        label: row.feats ? formatFeats(row.feats) : '—',
        columns: { form: [row.form] },
      })),
    }
  }

  const rowKeys: readonly string[] = kind === 'case' ? CASES : PERSONS
  const labels: Record<string, string> = kind === 'case' ? CASE_LABEL : (SHORT.Person ?? {})
  const dimension = kind === 'case' ? 'Case' : 'Person'

  const present = new Set(parsed.map((row) => row.f.Number).filter(Boolean))
  const columns: { key: string; label: string }[] = NUMBERS.filter((n) => present.has(n)).map(
    (n) => ({ key: n, label: NUMBER_LABEL[n] ?? n }),
  )
  // A paradigm with no Number at all — some verb forms, anything the tables
  // grow — still deserves a column to live in.
  if (columns.length === 0) columns.push({ key: '', label: 'tvar' })

  const rows: Cell[] = []
  for (const key of rowKeys) {
    const cells: Record<string, string[]> = {}
    let any = false
    for (const column of columns) {
      const matching = parsed.filter(
        (row) => row.f[dimension] === key && (row.f.Number ?? '') === column.key,
      )
      cells[column.key] = matching.map((row) => row.form)
      any = any || matching.length > 0
    }
    if (any) rows.push({ key, label: labels[key] ?? key, columns: cells })
  }
  return { kind, columns, rows }
}
