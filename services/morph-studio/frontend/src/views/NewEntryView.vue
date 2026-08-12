<script setup lang="ts">
// FI-7 surface 2, the way in: "enter a new word (basic form + POS)" (T4).
//
// Deliberately two fields. A pattern is NOT required to create an entry —
// choosing one is what the editor is for, and an analyst who has typed the word
// and its part of speech has entered something worth keeping (`NewEntry` in the
// backend says the same). Demanding the pattern here would mean guessing before
// the try-pattern affordance that exists to stop you guessing.

import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { ApiError, api } from '@/api/client'
import { useMachine } from '@/composables/useMachine'
import { useVzory } from '@/composables/useVzory'

const route = useRoute()
const router = useRouter()
const { machine, load: loadMachine } = useMachine()
const { uposes, load: loadVzory } = useVzory()

const lemma = ref(String(route.query.lemma ?? ''))
const upos = ref('')
const layer = ref('core')
const error = ref('')
const busy = ref(false)

onMounted(async () => {
  await Promise.all([loadMachine(), loadVzory()])
})

async function create(): Promise<void> {
  if (!lemma.value.trim() || !upos.value) return
  busy.value = true
  error.value = ''
  try {
    const entry = await api.createEntry({
      lemma: lemma.value.trim(),
      upos: upos.value,
      layer: layer.value,
      flags: [],
      forms: [],
      provenance: 'manual',
      actor: 'human',
    })
    await router.push({ name: 'entry', params: { id: entry.id } })
  } catch (caught) {
    error.value = caught instanceof ApiError ? caught.message : String(caught)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="new">
    <h2>New entry</h2>

    <form @submit.prevent="create">
      <label>
        <span class="label">basic form (lemma)</span>
        <input v-model="lemma" required data-test="new-lemma" />
      </label>

      <label>
        <span class="label">part of speech</span>
        <select v-model="upos" required data-test="new-upos">
          <option value="">— choose —</option>
          <option v-for="option in uposes" :key="option" :value="option">{{ option }}</option>
        </select>
      </label>

      <fieldset class="layer">
        <legend class="label">layer (LM-10)</legend>
        <label v-for="option in machine.layers" :key="option" class="radio">
          <input v-model="layer" type="radio" :value="option" :data-test="`new-layer-${option}`" />
          {{ option }}
        </label>
        <p class="hint">
          Proper nouns and this world's model vocabulary belong in the world layer; the analytical
          vocabulary belongs in the core.
        </p>
      </fieldset>

      <p class="hint">
        Saving creates the entry at <code>proposed</code>. Everything after that — a pattern, the
        LLM's opinion, corrected forms, verification — happens in the editor.
      </p>

      <p v-if="error" class="error" data-test="new-error">{{ error }}</p>

      <button type="submit" :disabled="busy" data-test="new-submit">Create</button>
    </form>
  </section>
</template>

<style scoped>
form {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  max-width: 32rem;
}
.label {
  display: block;
  font-size: 0.8rem;
  color: var(--ink-dim);
}
input,
select {
  font: inherit;
  padding: 0.3rem 0.4rem;
  border: 1px solid var(--line);
  border-radius: 4px;
  width: 100%;
}
.layer {
  border: 1px solid var(--line);
  border-radius: 4px;
  padding: 0.5rem 0.75rem;
}
.radio {
  margin-right: 1rem;
}
.radio input {
  width: auto;
}
.hint {
  font-size: 0.8rem;
  color: var(--ink-dim);
  margin: 0.25rem 0 0;
}
.error {
  color: var(--bad-ink);
}
button {
  align-self: flex-start;
}
</style>
