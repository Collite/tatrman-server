<script setup lang="ts">
// FI-7 surface 2: the entry editor (T4).
//
// The three affordances of the brief, and all three are engine-backed:
//
//   try a pattern   → `POST /try-pattern`, the engine generates the table
//   ask the LLM     → `POST /ask-llm`, a *pattern* proposal, shown as a diff
//   type by hand    → `POST /forms`, which makes it a full-form entry
//
// None of them is a text box where somebody writes a paradigm the compiler
// would then reject. The pattern list is the engine's inventory, the generated
// table is the engine's output, and a hand-typed table is stored as exactly
// what it is rather than pretending a pattern still describes it.

import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'

import { ApiError, api, type Entry, type Form, type Paradigm } from '@/api/client'
import ParadigmTable from '@/components/ParadigmTable.vue'
import ProposalDiff from '@/components/ProposalDiff.vue'
import ProvenanceBadge from '@/components/ProvenanceBadge.vue'
import StatusChip from '@/components/StatusChip.vue'
import VzorPicker from '@/components/VzorPicker.vue'
import { useMachine } from '@/composables/useMachine'

const route = useRoute()
const { machine, load: loadMachine, can } = useMachine()

const entry = ref<Entry | null>(null)
const vzor = ref('')
const flags = ref<string[]>([])
const preview = ref<Paradigm | null>(null)
const proposal = ref<Paradigm | null>(null)
const edited = ref<Form[] | null>(null)
const error = ref('')
const notice = ref('')
const busy = ref(false)

const id = computed(() => Number(route.params.id))

onMounted(async () => {
  await loadMachine()
  await reload()
})

watch(id, reload)

// A preview belongs to the pattern that produced it. Leaving it on screen while
// the picker says something else is how somebody presses "Use this" and applies
// a pattern they are no longer looking at.
watch([vzor, flags], () => {
  preview.value = null
})

async function reload(): Promise<void> {
  try {
    const loaded = await api.entry(id.value)
    entry.value = loaded
    vzor.value = loaded.vzor ?? ''
    flags.value = [...loaded.flags]
    preview.value = null
    proposal.value = null
    edited.value = null
  } catch (caught) {
    error.value = message(caught)
  }
}

function message(caught: unknown): string {
  if (!(caught instanceof ApiError)) return String(caught)
  // A 409 means the page is out of date, and saying so is more useful than the
  // sentence the backend wrote for a caller that can act on it.
  return caught.stale ? `${caught.message} (reload and try again)` : caught.message
}

async function act(what: () => Promise<void>): Promise<void> {
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    await what()
  } catch (caught) {
    error.value = message(caught)
  } finally {
    busy.value = false
  }
}

/** Generate the table without touching the entry — the "try" of try-pattern. */
const tryPattern = () =>
  act(async () => {
    preview.value = await api.tryPattern(id.value, { vzor: vzor.value, flags: flags.value })
    proposal.value = null
  })

/** Keep it. This is the click that changes the entry. */
const usePattern = () =>
  act(async () => {
    await api.tryPattern(id.value, { vzor: vzor.value, flags: flags.value, apply: true })
    await reload()
    notice.value = `Pattern ${vzor.value} applied — the forms below are the engine's.`
  })

const askLlm = () =>
  act(async () => {
    proposal.value = await api.askLlm(id.value)
    preview.value = null
  })

/** Accepting the proposal is the same act as choosing its pattern by hand. */
const acceptProposal = () =>
  act(async () => {
    const accepted = proposal.value
    if (!accepted) return
    vzor.value = accepted.vzor
    flags.value = [...accepted.flags]
    await api.tryPattern(id.value, { vzor: accepted.vzor, flags: accepted.flags, apply: true })
    await reload()
    notice.value = `Applied the classifier's ${accepted.vzor}. It is a manual entry now — a person accepted it.`
  })

const saveForms = () =>
  act(async () => {
    if (!edited.value) return
    entry.value = await api.correctForms(id.value, edited.value)
    edited.value = null
    notice.value = 'Saved. Corrected forms make this a full-form entry at export.'
  })

const setStatus = (status: string) =>
  act(async () => {
    entry.value = await api.setStatus(id.value, status)
    notice.value = `Now ${status}.`
  })

/** The status buttons ARE the machine's edges out of where this entry is. */
const nextStatuses = computed(() => machine.value.transitions[entry.value?.status ?? ''] ?? [])

const dirty = computed(() => edited.value !== null)
const corrected = computed(() => (entry.value?.forms ?? []).some((form) => form.corrected))
</script>

<template>
  <section v-if="entry" class="editor">
    <header class="head">
      <h2>{{ entry.lemma }}</h2>
      <span class="upos">{{ entry.upos }}</span>
      <StatusChip :status="entry.status" />
      <ProvenanceBadge :provisional="entry.provisional" :editorial="entry.provenance" />
      <span class="layer">{{ entry.layer }} layer</span>
      <span v-if="entry.source !== 'human'" class="source" data-test="entry-source"
        >reached by {{ entry.source }} ({{ entry.confidence.toFixed(2) }})</span
      >
    </header>

    <p v-if="entry.provisional" class="provisional" data-test="entry-provisional">
      <strong>Live now, unverified.</strong> These forms are in this world's overlay under the
      narrow Q-7 ruling. <em>Verify</em> makes them permanent; <em>reject</em> retracts them and
      names the retraction in the file's header.
    </p>

    <p v-if="notice" class="notice" data-test="entry-notice">{{ notice }}</p>
    <p v-if="error" class="error" data-test="entry-error">{{ error }}</p>

    <div class="columns">
      <div class="pattern">
        <h3>Pattern</h3>
        <VzorPicker v-model:vzor="vzor" v-model:flags="flags" :upos="entry.upos" />
        <div class="actions">
          <button type="button" :disabled="!vzor || busy" data-test="try-pattern" @click="tryPattern">
            Try pattern
          </button>
          <button
            type="button"
            :disabled="!preview || busy"
            data-test="use-pattern"
            @click="usePattern"
          >
            Use this
          </button>
          <button type="button" class="ghost" :disabled="busy" data-test="ask-llm" @click="askLlm">
            Ask the LLM
          </button>
        </div>
      </div>

      <div class="result">
        <template v-if="preview">
          <h3>
            {{ preview.vzor }} generates
            <span
              v-if="preview.validates"
              class="validates"
              data-test="preview-validates"
              title="LM-14: the observed form is in this paradigm."
              >auto-validates</span
            >
            <span v-else class="invalidates" data-test="preview-invalidates"
              >does not produce the observed form</span
            >
          </h3>
          <ParadigmTable :forms="preview.forms" />
        </template>

        <ProposalDiff
          v-else-if="proposal"
          :proposal="proposal"
          :current="{ vzor: entry.vzor, flags: entry.flags, forms: entry.forms }"
          @accept="acceptProposal"
          @dismiss="proposal = null"
        />
      </div>
    </div>

    <section class="forms">
      <h3>
        Forms
        <span v-if="corrected" class="full-form" data-test="is-full-form"
          >full-form entry — corrected by hand</span
        >
      </h3>
      <ParadigmTable
        :forms="entry.forms"
        editable
        @update:forms="(forms) => (edited = forms)"
      />
      <button type="button" :disabled="!dirty || busy" data-test="save-forms" @click="saveForms">
        Save corrected forms
      </button>
    </section>

    <section class="status">
      <h3>Status</h3>
      <p v-if="nextStatuses.length === 0" class="hint" data-test="no-transitions">
        Nothing follows <code>{{ entry.status }}</code> — it is terminal.
      </p>
      <div v-else class="actions">
        <button
          v-for="status in nextStatuses"
          :key="status"
          type="button"
          :disabled="busy || !can(entry.status, status)"
          :data-test="`to-${status}`"
          @click="setStatus(status)"
        >
          → {{ status }}
        </button>
      </div>
    </section>
  </section>

  <p v-else-if="error" class="error">{{ error }}</p>
</template>

<style scoped>
.head {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  flex-wrap: wrap;
}
h2 {
  margin: 0;
}
.upos,
.layer,
.source {
  font-size: 0.8rem;
  color: var(--ink-dim);
}
.columns {
  display: flex;
  gap: 2rem;
  flex-wrap: wrap;
  margin-top: 1rem;
}
.pattern,
.result {
  flex: 1 1 22rem;
}
h3 {
  font-size: 1rem;
  margin: 0 0 0.5rem;
}
.actions {
  display: flex;
  gap: 0.5rem;
  flex-wrap: wrap;
  margin-top: 0.75rem;
}
.forms,
.status {
  margin-top: 1.5rem;
}
.forms button {
  margin-top: 0.5rem;
}
.provisional {
  border-left: 3px solid var(--warn);
  background: var(--warn-wash);
  padding: 0.5rem 0.75rem;
  max-width: 48rem;
}
.notice {
  color: var(--good-ink);
}
.error {
  color: var(--bad-ink);
}
.validates {
  color: var(--good-ink);
  font-size: 0.8rem;
}
.invalidates {
  color: var(--bad-ink);
  font-size: 0.8rem;
}
.full-form {
  font-size: 0.75rem;
  color: var(--warn-ink);
  border: 1px solid var(--warn);
  border-radius: 3px;
  padding: 0.05rem 0.35rem;
}
.hint {
  font-size: 0.85rem;
  color: var(--ink-dim);
}
</style>
