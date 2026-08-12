<script setup lang="ts">
// Where an entry came from — both senses (NLS-P9.3 T2/T3).

import { computed } from 'vue'

import {
  EDITORIAL_TITLE,
  RUNTIME_TITLE,
  runtimeProvenance,
  shareAlike,
  type RuntimeProvenance,
} from '@/provenance'

const props = defineProps<{
  /** The runtime provenance, or an entry to derive it from. */
  runtime?: RuntimeProvenance
  provisional?: boolean
  /** `wiktionary` | `cac` | `manual` | `llm`. Omitted where it adds nothing. */
  editorial?: string
}>()

const kind = computed<RuntimeProvenance>(
  () => props.runtime ?? runtimeProvenance({ provisional: props.provisional ?? false }),
)
</script>

<template>
  <span class="badges">
    <span
      class="badge"
      :class="`badge--${kind}`"
      :title="RUNTIME_TITLE[kind]"
      data-test="runtime-provenance"
    >
      {{ kind }}
    </span>
    <span
      v-if="editorial"
      class="badge badge--editorial"
      :class="{ 'badge--share-alike': shareAlike(editorial) }"
      :title="EDITORIAL_TITLE[editorial] ?? editorial"
      data-test="editorial-provenance"
    >
      {{ editorial }}<span v-if="shareAlike(editorial)" aria-hidden="true"> ⚖</span>
    </span>
  </span>
</template>

<style scoped>
.badges {
  display: inline-flex;
  gap: 0.25rem;
}
.badge {
  padding: 0.05rem 0.4rem;
  border-radius: 3px;
  font-size: 0.75rem;
  border: 1px solid var(--line);
  background: var(--wash);
  color: var(--ink-dim);
}
.badge--lexicon {
  border-color: var(--good);
  color: var(--good-ink);
  background: var(--good-wash);
}
.badge--provisional {
  border-color: var(--warn);
  color: var(--warn-ink);
  background: var(--warn-wash);
}
.badge--statistical {
  border-style: dashed;
}
.badge--share-alike {
  border-color: var(--accent);
  color: var(--accent-ink);
}
</style>
