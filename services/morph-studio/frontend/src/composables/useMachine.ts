// SPDX-License-Identifier: Apache-2.0
// The status machine, fetched once and shared (NLS-P9.3 T2/T5).
//
// The chips and the verdict buttons are drawn from `GET /v1/machine`, which is
// `status.py` itself. That is the whole reason the endpoint exists: a frontend
// constant list can only ever be a copy that was correct once, and the task
// list asks for chips that match §8 *exactly*. Here, an invented state is
// unrepresentable — there is nowhere to write one down.

import { readonly, ref } from 'vue'

import { api, type Machine } from '@/api/client'

const EMPTY: Machine = {
  statuses: [],
  transitions: {},
  exportable: [],
  terminal: [],
  actions: [],
  layers: [],
}

const machine = ref<Machine>(EMPTY)
const loaded = ref(false)
let inflight: Promise<Machine> | null = null

export function useMachine() {
  /** Fetch once per page load; concurrent callers share the one request. */
  async function load(): Promise<Machine> {
    if (loaded.value) return machine.value
    inflight ??= api.machine().then((body) => {
      machine.value = body
      loaded.value = true
      inflight = null
      return body
    })
    return inflight
  }

  /** Is `current -> wanted` an edge? The UI's version of `status.can`. */
  function can(current: string, wanted: string): boolean {
    return (machine.value.transitions[current] ?? []).includes(wanted)
  }

  /** Does an entry at this status leave the database? (The export gate.) */
  function exportable(status: string): boolean {
    return machine.value.exportable.includes(status)
  }

  /** No way out — `rejected`. Rendered as a chip with no buttons beside it. */
  function terminal(status: string): boolean {
    return machine.value.terminal.includes(status)
  }

  /**
   * A status the service has never heard of.
   *
   * Only reachable if a response and this document disagree, which means the
   * page is older than the service. Worth showing rather than styling as
   * "unknown and probably fine".
   */
  function known(status: string): boolean {
    return machine.value.statuses.includes(status)
  }

  return { machine: readonly(machine), loaded: readonly(loaded), load, can, exportable, terminal, known }
}

/** Tests get a clean module — the cache is module-scoped on purpose. */
export function resetMachine(): void {
  machine.value = EMPTY
  loaded.value = false
  inflight = null
}
