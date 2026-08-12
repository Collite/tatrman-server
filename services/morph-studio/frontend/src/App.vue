<script setup lang="ts">
import { onMounted, ref } from 'vue'

import { api, type Status } from '@/api/client'
import { useMachine } from '@/composables/useMachine'

const status = ref<Status | null>(null)
const { load } = useMachine()

onMounted(async () => {
  await load()
  try {
    status.value = await api.status()
  } catch {
    // The header is not worth an error banner: every view reports its own
    // failures, and a studio whose /v1/status hiccuped is still usable.
  }
})
</script>

<template>
  <div class="app">
    <header class="bar">
      <strong class="brand">morph-studio</strong>
      <span v-if="status" class="world" data-test="world">{{ status.world }}</span>
      <nav>
        <RouterLink :to="{ name: 'queue' }">Queue</RouterLink>
        <RouterLink :to="{ name: 'word' }">Look up</RouterLink>
        <RouterLink :to="{ name: 'new-entry' }">New entry</RouterLink>
      </nav>
      <span class="spacer" />
      <span v-if="status" class="modes">
        <span :title="status.llm ? 'The classifier leg is configured.' : 'No LLM leg: this deployment runs guesser → human, which is a supported arrangement.'">
          llm {{ status.llm ? 'on' : 'off' }}
        </span>
        <span title="Q-7: auto-validated proper nouns may go live in this world's overlay before a human sees them.">
          provisional {{ status.provisional ? 'on' : 'off' }}
        </span>
        <span v-if="status.mode !== 'studio'" class="mode" data-test="mode">{{ status.mode }}</span>
      </span>
    </header>

    <main>
      <RouterView />
    </main>
  </div>
</template>

<style scoped>
.bar {
  display: flex;
  align-items: center;
  gap: 1rem;
  padding: 0.6rem 1.25rem;
  border-bottom: 1px solid var(--line);
  background: var(--wash);
}
.brand {
  letter-spacing: 0.02em;
}
.world {
  font-size: 0.8rem;
  border: 1px solid var(--line);
  border-radius: 999px;
  padding: 0.05rem 0.5rem;
  background: var(--bg);
}
nav {
  display: flex;
  gap: 0.75rem;
}
nav a {
  font-size: 0.9rem;
  padding-bottom: 2px;
  border-bottom: 2px solid transparent;
}
nav a.router-link-active {
  border-bottom-color: var(--accent);
}
.spacer {
  flex: 1;
}
.modes {
  display: flex;
  gap: 0.75rem;
  font-size: 0.75rem;
  color: var(--ink-dim);
}
.mode {
  color: var(--warn-ink);
}
main {
  padding: 1.25rem;
}
</style>
