<script setup lang="ts">
// The pattern picker — constrained to the engine's inventory (NLS-P9.3 T2/T4).
//
// The options come from `GET /v1/vzory`, which reads the engine's own tables.
// There is no free-text pattern field anywhere in this UI, because a pattern
// the engine does not have is a 400 from the backend and a wasted click here:
// the inventory is closed (LM-2), and the closure is the useful part.
//
// The flag checkboxes are the same argument: `fleeting-e` and its siblings are
// the tables' flag vocabulary, not a list somebody typed into a component.

import { computed, onMounted } from 'vue'

import { useVzory } from '@/composables/useVzory'

const props = withDefaults(
  defineProps<{
    vzor: string
    flags: string[]
    /** Narrow the options to one part of speech, when the entry has one. */
    upos?: string
  }>(),
  { upos: '' },
)

const emit = defineEmits<{
  (event: 'update:vzor', vzor: string): void
  (event: 'update:flags', flags: string[]): void
}>()

const { inventory, load, forUpos, find } = useVzory()

onMounted(load)

const options = computed(() => forUpos(props.upos))
const chosen = computed(() => find(props.vzor))

/** The B-O5 hints, as the line that tells an analyst which sub-vzor they want. */
const hint = computed(() => {
  const hints = chosen.value?.hints ?? {}
  const parts: string[] = []
  if (hints.capitalized === true) parts.push('capitalised')
  if (typeof hints.lemma_pattern === 'string') parts.push(`lemma matches /${hints.lemma_pattern}/`)
  for (const [key, value] of Object.entries(hints)) {
    if (key !== 'capitalized' && key !== 'lemma_pattern') parts.push(`${key}=${value}`)
  }
  return parts.join(' · ')
})

function toggle(flag: string, on: boolean): void {
  const next = new Set(props.flags)
  if (on) next.add(flag)
  else next.delete(flag)
  emit('update:flags', [...next])
}
</script>

<template>
  <div class="picker">
    <label>
      <span class="label">vzor</span>
      <select
        :value="vzor"
        data-test="vzor-picker"
        @change="emit('update:vzor', ($event.target as HTMLSelectElement).value)"
      >
        <option value="">— choose a pattern —</option>
        <option v-for="option in options" :key="option.name" :value="option.name">
          {{ option.name }} ({{ option.upos }})
        </option>
      </select>
    </label>

    <p v-if="chosen" class="hint" data-test="vzor-hint">
      <span v-if="chosen.parent"
        >sub-pattern of <code>{{ chosen.parent }}</code> ·
      </span>
      <span v-if="hint">{{ hint }}</span>
      <span v-if="chosen.implied_flags.length">
        · implies {{ chosen.implied_flags.join(', ') }}</span
      >
    </p>

    <fieldset class="flags">
      <legend class="label">flags</legend>
      <label v-for="flag in inventory.flags" :key="flag" class="flag">
        <input
          type="checkbox"
          :value="flag"
          :checked="flags.includes(flag)"
          :data-test="`flag-${flag}`"
          @change="toggle(flag, ($event.target as HTMLInputElement).checked)"
        />
        {{ flag }}
      </label>
    </fieldset>
  </div>
</template>

<style scoped>
.picker {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}
.label {
  display: block;
  font-size: 0.8rem;
  color: var(--ink-dim);
}
select {
  font: inherit;
  padding: 0.2rem;
  min-width: 18rem;
}
.hint {
  margin: 0;
  font-size: 0.8rem;
  color: var(--ink-dim);
}
.flags {
  border: 1px solid var(--line);
  border-radius: 4px;
  padding: 0.4rem 0.6rem;
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem;
}
.flag {
  font-size: 0.85rem;
  white-space: nowrap;
}
</style>
