<script setup lang="ts">
// The paradigm, as a table somebody can check (NLS-P9.3 T2/T4).
//
// Cases down, number across — the textbook grid B-O5's sub-vzor inventory maps
// 1:1 onto (design §B). A flat list of thirty strings is not something an
// analyst can scan for the one wrong locative, which is the entire job here.
//
// ⚑ **An edited cell is a full-form override, not a correction to a pattern.**
// The pattern still generates what it generated; the entry simply stops being
// describable by it, and at export it becomes a full-form entry (contracts §3).
// A compact entry that regenerates differently is `LM-MORPH-005`, so there is
// no version of this where we keep the `vzor:` and the edit.

import { computed, ref, watch } from 'vue'

import type { Form } from '@/api/client'
import { formatFeats, grid } from '@/feats'

const props = withDefaults(
  defineProps<{
    forms: Form[]
    editable?: boolean
    /** The pattern's own output, when it differs from what is being shown. */
    generated?: Form[]
  }>(),
  { editable: false, generated: undefined },
)

const emit = defineEmits<{ (event: 'update:forms', forms: Form[]): void }>()

/** The working copy. Edits are local until the view saves them. */
const rows = ref<Form[]>(props.forms.map((form) => ({ ...form })))

watch(
  () => props.forms,
  (next) => {
    rows.value = next.map((form) => ({ ...form }))
  },
)

/** What the pattern produced, keyed by feats — the baseline an edit departs from. */
const baseline = computed(() => {
  const source = props.generated ?? props.forms
  const map = new Map<string, string[]>()
  for (const row of source) {
    map.set(row.feats ?? '', [...(map.get(row.feats ?? '') ?? []), row.form])
  }
  return map
})

const table = computed(() => grid(rows.value))

/** Index into `rows` for a cell's nth form, so an input can write back to it. */
function indexOf(rowKey: string, column: string, nth: number): number {
  let seen = 0
  for (let i = 0; i < rows.value.length; i += 1) {
    const row = rows.value[i]
    if (!row) continue
    const feats = row.feats ?? ''
    const matchesRow =
      table.value.kind === 'flat'
        ? feats === rowKey || row.form === rowKey
        : feats.includes(`${table.value.kind === 'case' ? 'Case' : 'Person'}=${rowKey}`) &&
          (column === '' || feats.includes(`Number=${column}`))
    if (matchesRow) {
      if (seen === nth) return i
      seen += 1
    }
  }
  return -1
}

function edit(index: number, value: string): void {
  const row = rows.value[index]
  if (!row) return
  const original = baseline.value.get(row.feats ?? '') ?? []
  row.form = value
  // Corrected means "the pattern does not produce this", which is a statement
  // about the pattern's output — not about whether somebody typed in the box.
  row.corrected = !original.includes(value)
  emit(
    'update:forms',
    rows.value.map((entry) => ({ ...entry })),
  )
}

const corrections = computed(() => rows.value.filter((row) => row.corrected).length)
</script>

<template>
  <div class="paradigm">
    <p v-if="table.rows.length === 0" class="empty" data-test="paradigm-empty">
      No forms — the pattern generated nothing for this lemma.
    </p>

    <table v-else data-test="paradigm-table">
      <thead>
        <tr>
          <th class="rowhead">{{ table.kind === 'person' ? 'osoba' : 'pád' }}</th>
          <th v-for="column in table.columns" :key="column.key">{{ column.label }}</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in table.rows" :key="row.key">
          <th class="rowhead">{{ row.label }}</th>
          <td v-for="column in table.columns" :key="column.key">
            <template v-if="editable">
              <input
                v-for="(form, nth) in row.columns[column.key]"
                :key="`${row.key}-${column.key}-${nth}`"
                class="cell"
                :class="{ 'cell--corrected': rows[indexOf(row.key, column.key, nth)]?.corrected }"
                :value="form"
                :aria-label="`${row.label} ${column.label}`"
                @input="edit(indexOf(row.key, column.key, nth), ($event.target as HTMLInputElement).value)"
              />
            </template>
            <template v-else>
              <span
                v-for="(form, nth) in row.columns[column.key]"
                :key="`${row.key}-${column.key}-${nth}`"
                class="form"
                >{{ form }}</span
              >
            </template>
          </td>
        </tr>
      </tbody>
    </table>

    <p v-if="corrections > 0" class="note" data-test="override-note">
      {{ corrections }} corrected {{ corrections === 1 ? 'form' : 'forms' }} — saving these makes
      this a <strong>full-form entry</strong>: the pattern no longer describes it, and export will
      write every form out rather than a <code>vzor:</code>.
    </p>

    <details v-if="table.kind !== 'flat'" class="raw">
      <summary>feature strings</summary>
      <ul>
        <li v-for="(row, index) in rows" :key="index">
          <code>{{ row.form }}</code> — {{ formatFeats(row.feats ?? '') }}
          <span class="dim">({{ row.feats }})</span>
        </li>
      </ul>
    </details>
  </div>
</template>

<style scoped>
table {
  border-collapse: collapse;
  font-size: 0.95rem;
}
th,
td {
  border: 1px solid var(--line);
  padding: 0.2rem 0.5rem;
  text-align: left;
  vertical-align: top;
}
.rowhead {
  color: var(--ink-dim);
  font-weight: 500;
  white-space: nowrap;
}
.form + .form::before {
  content: ' / ';
  color: var(--ink-dim);
}
.cell {
  border: 1px solid transparent;
  background: transparent;
  font: inherit;
  color: inherit;
  padding: 0.1rem 0.2rem;
  width: 10rem;
}
.cell:focus {
  border-color: var(--accent);
  outline: none;
}
.cell--corrected {
  background: var(--warn-wash);
  border-color: var(--warn);
}
.note {
  font-size: 0.85rem;
  color: var(--warn-ink);
  max-width: 42rem;
}
.empty {
  color: var(--ink-dim);
}
.raw {
  margin-top: 0.5rem;
  font-size: 0.8rem;
  color: var(--ink-dim);
}
.dim {
  color: var(--ink-dim);
}
</style>
