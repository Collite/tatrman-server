// SPDX-License-Identifier: Apache-2.0
// The pattern inventory, fetched once and shared (NLS-P9.3 T4).
//
// LM-2 makes the sub-vzor inventory *data* — `seed/data/cs/vzory.yaml` — so
// that a pattern added there reaches everything that uses patterns. A picker
// with a hard-coded option list would be the one place that ruling failed to
// reach, and the analyst who needed the new sub-vzor could not choose it.

import { computed, readonly, ref } from 'vue'

import { api, type Vzor, type Vzory } from '@/api/client'

const EMPTY: Vzory = { language: '', flags: [], vzory: [] }

const inventory = ref<Vzory>(EMPTY)
const loaded = ref(false)
let inflight: Promise<Vzory> | null = null

export function useVzory() {
  async function load(): Promise<Vzory> {
    if (loaded.value) return inventory.value
    inflight ??= api.vzory().then((body) => {
      inventory.value = body
      loaded.value = true
      inflight = null
      return body
    })
    return inflight
  }

  /** Patterns for a part of speech — what the picker offers once upos is known. */
  function forUpos(upos: string): Vzor[] {
    if (!upos) return inventory.value.vzory
    return inventory.value.vzory.filter((vzor) => vzor.upos === upos)
  }

  /** Is this a pattern the engine has? The picker cannot offer anything else. */
  function has(name: string): boolean {
    return inventory.value.vzory.some((vzor) => vzor.name === name)
  }

  function find(name: string): Vzor | undefined {
    return inventory.value.vzory.find((vzor) => vzor.name === name)
  }

  const uposes = computed(() => [...new Set(inventory.value.vzory.map((v) => v.upos))].sort())

  return { inventory: readonly(inventory), loaded: readonly(loaded), load, forUpos, has, find, uposes }
}

export function resetVzory(): void {
  inventory.value = EMPTY
  loaded.value = false
  inflight = null
}
