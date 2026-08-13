<script setup lang="ts">
// A status, as the machine defines it (NLS-P9.3 T2).
//
// The chip does not decide what statuses exist — `GET /v1/machine` does. What
// it adds is the one thing worth seeing at a glance: whether this status is
// past the export gate, because that is the difference between an entry that
// ships and an entry that is still working memory.

import { computed } from 'vue'

import { useMachine } from '@/composables/useMachine'

const props = defineProps<{ status: string }>()

const { exportable, terminal, known } = useMachine()

const kind = computed(() => {
  if (!known(props.status)) return 'unknown'
  if (terminal(props.status)) return 'terminal'
  return exportable(props.status) ? 'exportable' : 'working'
})

const title = computed(() => {
  switch (kind.value) {
    case 'unknown':
      // The page is older than the service. Say so — it is not a styling gap.
      return `“${props.status}” is not a status this page knows; reload.`
    case 'terminal':
      return 'Terminal: one verdict, one permanent answer.'
    case 'exportable':
      return 'Past the export gate — this entry leaves the database.'
    default:
      return 'Working memory: below `verified`, so export holds it back.'
  }
})
</script>

<template>
  <span class="chip" :class="`chip--${kind}`" :title="title" data-test="status-chip">
    {{ status }}
  </span>
</template>

<style scoped>
.chip {
  display: inline-block;
  padding: 0.1rem 0.5rem;
  border-radius: 999px;
  border: 1px solid var(--line);
  font-size: 0.8rem;
  white-space: nowrap;
}
.chip--working {
  background: var(--wash);
  color: var(--ink-dim);
}
.chip--exportable {
  background: var(--good-wash);
  border-color: var(--good);
  color: var(--good-ink);
}
.chip--terminal {
  background: var(--bad-wash);
  border-color: var(--bad);
  color: var(--bad-ink);
  text-decoration: line-through;
}
.chip--unknown {
  background: var(--warn-wash);
  border-color: var(--warn);
  color: var(--warn-ink);
}
</style>
