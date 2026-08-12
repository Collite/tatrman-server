<script setup lang="ts">
// FI-7 surface 1: look a word up and see everything known about it (T3).
//
// The search is *by form*, not by lemma — an analyst types the word they saw in
// a query, which is almost never the citation form. That is why the store
// materialises `entry_form` at all, and why the fold index exists: `Kauflandu`
// and `kauflandu` have to find the same entry, and the answer has to say which
// of the two happened.

import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { ApiError, api, type Entry, type LookupResponse } from '@/api/client'
import ProvenanceBadge from '@/components/ProvenanceBadge.vue'
import StatusChip from '@/components/StatusChip.vue'
import ParadigmTable from '@/components/ParadigmTable.vue'
import { useMachine } from '@/composables/useMachine'
import { formatFeats } from '@/feats'

const route = useRoute()
const router = useRouter()
const { load: loadMachine } = useMachine()

const term = ref(String(route.params.form ?? ''))
const result = ref<LookupResponse | null>(null)
const error = ref('')
const busy = ref(false)
const expanded = ref<number | null>(null)

onMounted(async () => {
  await loadMachine()
  if (term.value) await search()
})

watch(
  () => route.params.form,
  (next) => {
    const form = String(next ?? '')
    if (form && form !== term.value) {
      term.value = form
      void search()
    }
  },
)

async function search(): Promise<void> {
  const form = term.value.trim()
  if (!form) return
  busy.value = true
  error.value = ''
  try {
    result.value = await api.lookup(form)
    if (String(route.params.form ?? '') !== form) {
      await router.replace({ name: 'word', params: { form } })
    }
  } catch (caught) {
    error.value = caught instanceof ApiError ? caught.message : String(caught)
    result.value = null
  } finally {
    busy.value = false
  }
}

/**
 * Ranked: verified before proposed, then by how many forms back it up.
 *
 * The service returns entries, not a ranking — ordering is a presentation
 * question and the store has no business answering it. The rule is the one a
 * reviewer wants: the entry somebody has already confirmed comes first.
 */
const ranked = computed<Entry[]>(() => {
  const { exportable } = useMachine()
  return [...(result.value?.entries ?? [])].sort((a, b) => {
    const gate = Number(exportable(b.status)) - Number(exportable(a.status))
    if (gate !== 0) return gate
    if (b.confidence !== a.confidence) return b.confidence - a.confidence
    return b.forms.length - a.forms.length
  })
})

/** Which cell of an entry the searched form actually is. */
function matchingFeats(entry: Entry): string {
  const wanted = (result.value?.form ?? '').toLocaleLowerCase('cs')
  const hit = entry.forms.find((form) => form.form.toLocaleLowerCase('cs') === wanted)
  return hit ? formatFeats(hit.feats ?? '') : ''
}
</script>

<template>
  <section class="word">
    <form class="search" @submit.prevent="search">
      <input
        v-model="term"
        type="search"
        placeholder="a word, in any form — Kauflandu, tržbami, loňském"
        aria-label="look up a form"
        data-test="lookup-input"
      />
      <button type="submit" :disabled="busy" data-test="lookup-submit">Look up</button>
      <RouterLink class="ghost-link" :to="{ name: 'new-entry', query: { lemma: term } }">
        New entry
      </RouterLink>
    </form>

    <p v-if="error" class="error" data-test="lookup-error">{{ error }}</p>

    <template v-if="result">
      <p class="summary" data-test="lookup-summary">
        <strong>{{ result.form }}</strong>
        <span v-if="result.entries.length === 0" data-test="lookup-miss">
          — not in this store. It is a
          <RouterLink :to="{ name: 'queue' }">queue</RouterLink> candidate, or a word to enter.
        </span>
        <span v-else>
          — {{ result.entries.length }}
          {{ result.entries.length === 1 ? 'entry' : 'entries' }}
        </span>

        <span
          v-if="result.matched_via === 'folded'"
          class="folded"
          data-test="matched-via-folded"
          title="Matched without diacritics — the same fold the runtime applies (B-F3). The stored form is spelled differently from what you typed."
        >
          matched folded
        </span>
        <span v-else-if="result.entries.length" class="exact" data-test="matched-via-exact">
          exact match
        </span>
      </p>

      <ul class="entries">
        <li v-for="entry in ranked" :key="entry.id" class="entry" data-test="lookup-entry">
          <header>
            <RouterLink
              class="lemma"
              :to="{ name: 'entry', params: { id: entry.id } }"
              data-test="entry-link"
              >{{ entry.lemma }}</RouterLink
            >
            <span class="upos">{{ entry.upos }}</span>
            <code v-if="entry.vzor" class="vzor">{{ entry.vzor }}</code>
            <code v-else class="vzor vzor--none" title="A full-form entry: no pattern describes it."
              >full-form</code
            >
            <StatusChip :status="entry.status" />
            <ProvenanceBadge :provisional="entry.provisional" :editorial="entry.provenance" />
            <span class="layer">{{ entry.layer }}</span>
          </header>

          <p v-if="matchingFeats(entry)" class="matched" data-test="matched-feats">
            <code>{{ result.form }}</code> here is the {{ matchingFeats(entry) }}
          </p>

          <button
            type="button"
            class="ghost"
            data-test="toggle-paradigm"
            @click="expanded = expanded === entry.id ? null : entry.id"
          >
            {{ expanded === entry.id ? 'hide' : 'show' }} {{ entry.forms.length }} forms
          </button>

          <ParadigmTable v-if="expanded === entry.id" :forms="entry.forms" />
        </li>
      </ul>
    </template>
  </section>
</template>

<style scoped>
.search {
  display: flex;
  gap: 0.5rem;
  align-items: center;
  margin-bottom: 1rem;
}
input[type='search'] {
  font: inherit;
  padding: 0.35rem 0.5rem;
  min-width: 28rem;
  border: 1px solid var(--line);
  border-radius: 4px;
}
.summary {
  margin: 0 0 0.75rem;
}
.folded {
  margin-left: 0.5rem;
  padding: 0.05rem 0.4rem;
  border-radius: 3px;
  background: var(--warn-wash);
  color: var(--warn-ink);
  border: 1px solid var(--warn);
  font-size: 0.75rem;
}
.exact {
  margin-left: 0.5rem;
  font-size: 0.75rem;
  color: var(--ink-dim);
}
.entries {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}
.entry {
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 0.6rem 0.8rem;
}
.entry header {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex-wrap: wrap;
}
.lemma {
  font-size: 1.1rem;
  font-weight: 600;
}
.upos,
.layer {
  font-size: 0.8rem;
  color: var(--ink-dim);
}
.vzor {
  font-size: 0.8rem;
  background: var(--wash);
  padding: 0.05rem 0.35rem;
  border-radius: 3px;
}
.vzor--none {
  font-style: italic;
}
.matched {
  margin: 0.4rem 0;
  font-size: 0.9rem;
  color: var(--ink-dim);
}
.error {
  color: var(--bad-ink);
}
</style>
