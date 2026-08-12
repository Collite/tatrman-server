<script setup lang="ts">
// FI-7 surfaces 3+4: the verification queue (T5).
//
// One world's queue — this instance serves exactly one (LM-5/S-4), so there is
// no world selector and asking for another one is a 400, not an empty table.
// Ordered by how often the front saw the token, which is the queue's only
// priority signal and the honest one: the word blocking the most queries first.
//
// The three verdicts are the machine's, fetched from `GET /v1/machine`. A
// button for an edge the backend refuses cannot be drawn here — which matters
// most for `rejected`, where a second click on Verify would otherwise return a
// 409 the reviewer has no way to interpret.

import { computed, onMounted, ref } from 'vue'

import { ApiError, api, type QueueItem, type QueueResponse } from '@/api/client'
import ProvenanceBadge from '@/components/ProvenanceBadge.vue'
import StatusChip from '@/components/StatusChip.vue'
import { useMachine } from '@/composables/useMachine'

const { machine, load: loadMachine, terminal } = useMachine()

const queue = ref<QueueResponse | null>(null)
const filter = ref('')
const error = ref('')
const notice = ref('')
const busy = ref<number | null>(null)
const routing = ref<number | null>(null)

onMounted(async () => {
  await loadMachine()
  await reload()
})

async function reload(): Promise<void> {
  try {
    queue.value = await api.queue(filter.value ? { status: filter.value } : {})
  } catch (caught) {
    error.value = caught instanceof ApiError ? caught.message : String(caught)
  }
}

async function verdict(
  item: QueueItem,
  action: 'verify' | 'reject' | 'route',
  layer?: string,
): Promise<void> {
  busy.value = item.id
  error.value = ''
  notice.value = ''
  try {
    const result = await api.verdict(item.id, action, layer ? { layer } : {})
    notice.value = summarise(action, result.overlay_emitted, result.reload)
    routing.value = null
    await reload()
  } catch (caught) {
    error.value =
      caught instanceof ApiError
        ? caught.stale
          ? `${caught.message} — reloading`
          : caught.message
        : String(caught)
    if (caught instanceof ApiError && caught.stale) await reload()
  } finally {
    busy.value = null
  }
}

function summarise(action: string, emitted: boolean, reload: string): string {
  if (action === 'route') return 'Routed. LM-10’s default is overridden for this entry.'
  const overlay = emitted
    ? ` The world overlay was re-emitted${reload ? ` and the front was told: ${reload}` : ''}.`
    : ''
  return action === 'verify'
    ? `Verified — permanent, and past the export gate.${overlay}`
    : `Rejected — retracted, and terminal: the front can report this token again and it stays answered.${overlay}`
}

/** The proposal a reviewer is actually judging. */
function best(item: QueueItem) {
  return item.cascade?.proposals?.[0]
}

const counts = computed(() => {
  const tally: Record<string, number> = {}
  for (const item of queue.value?.items ?? []) tally[item.status] = (tally[item.status] ?? 0) + 1
  return tally
})
</script>

<template>
  <section class="queue">
    <header class="head">
      <h2>Queue — {{ queue?.world ?? '…' }}</h2>
      <span class="total" data-test="queue-total">{{ queue?.total ?? 0 }} items</span>
      <label class="filter">
        status
        <select v-model="filter" data-test="queue-filter" @change="reload">
          <option value="">all</option>
          <option v-for="status in machine.statuses" :key="status" :value="status">
            {{ status }} <template v-if="counts[status]">({{ counts[status] }})</template>
          </option>
        </select>
      </label>
    </header>

    <p v-if="notice" class="notice" data-test="queue-notice">{{ notice }}</p>
    <p v-if="error" class="error" data-test="queue-error">{{ error }}</p>

    <p v-if="queue && queue.items.length === 0" class="empty" data-test="queue-empty">
      Nothing waiting. Every token the front reported has an answer.
    </p>

    <table v-else-if="queue" data-test="queue-table">
      <thead>
        <tr>
          <th>token</th>
          <th>seen</th>
          <th>proposal</th>
          <th>status</th>
          <th>layer</th>
          <th>verdict</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="item in queue.items" :key="item.id" data-test="queue-row">
          <td>
            <RouterLink :to="{ name: 'word', params: { form: item.token } }" class="token">{{
              item.token
            }}</RouterLink>
            <span v-if="item.verdict !== 'miss'" class="verdict-kind">{{ item.verdict }}</span>
          </td>
          <td class="count" data-test="queue-count">{{ item.count }}×</td>
          <td>
            <template v-if="best(item)">
              <code data-test="proposal-lemma">{{ best(item)!.lemma }}</code>
              <span class="upos">{{ best(item)!.upos }}</span>
              <code class="vzor">{{ best(item)!.vzor }}</code>
              <span class="source" data-test="proposal-source"
                >{{ best(item)!.source }} {{ best(item)!.confidence.toFixed(2) }}</span
              >
              <span
                v-if="item.cascade.agreed"
                class="agreed"
                data-test="proposal-agreed"
                title="Two independent legs named the same entry."
                >agreed</span
              >
            </template>
            <span v-else class="none" data-test="proposal-none"
              >nothing proposed — a person has to author this</span
            >
            <ul v-if="item.cascade.notes.length" class="notes" data-test="cascade-notes">
              <li v-for="(note, index) in item.cascade.notes" :key="index">{{ note }}</li>
            </ul>
          </td>
          <td>
            <StatusChip :status="item.status" />
            <ProvenanceBadge
              v-if="item.status === 'auto-validated' && item.layer === 'world'"
              provisional
            />
          </td>
          <td>
            <span class="layer">{{ item.layer }}</span>
            <span
              class="routed"
              :title="
                item.routed_by === 'auto'
                  ? 'LM-10’s default: proper nouns and model-vocabulary matches go to the world layer.'
                  : 'A person overrode the routing.'
              "
              data-test="routed-by"
              >{{ item.routed_by }}</span
            >
          </td>
          <td class="verdicts">
            <template v-if="terminal(item.status)">
              <span class="done" data-test="verdict-done">answered</span>
            </template>
            <template v-else>
              <button
                type="button"
                :disabled="busy === item.id"
                data-test="verdict-verify"
                @click="verdict(item, 'verify')"
              >
                Verify
              </button>
              <button
                type="button"
                class="danger"
                :disabled="busy === item.id"
                data-test="verdict-reject"
                @click="verdict(item, 'reject')"
              >
                Reject
              </button>
              <button
                type="button"
                class="ghost"
                :disabled="busy === item.id"
                data-test="verdict-route"
                @click="routing = routing === item.id ? null : item.id"
              >
                Route…
              </button>
              <span v-if="routing === item.id" class="route-choice" data-test="route-choice">
                <button
                  v-for="layer in machine.layers"
                  :key="layer"
                  type="button"
                  :disabled="layer === item.layer"
                  :data-test="`route-to-${layer}`"
                  @click="verdict(item, 'route', layer)"
                >
                  → {{ layer }}
                </button>
              </span>
            </template>
          </td>
        </tr>
      </tbody>
    </table>

    <p class="legend">
      <strong>Verify</strong> makes an entry permanent and lets it past the export gate — including
      any <em>provisional</em> overlay rows already live under Q-7. <strong>Reject</strong> retracts
      them and is terminal: one verdict answers this token for good.
    </p>
  </section>
</template>

<style scoped>
.head {
  display: flex;
  align-items: baseline;
  gap: 1rem;
  flex-wrap: wrap;
}
h2 {
  margin: 0;
}
.total,
.filter {
  font-size: 0.85rem;
  color: var(--ink-dim);
}
table {
  border-collapse: collapse;
  width: 100%;
  margin-top: 1rem;
  font-size: 0.9rem;
}
th,
td {
  border-bottom: 1px solid var(--line);
  padding: 0.4rem 0.5rem;
  text-align: left;
  vertical-align: top;
}
th {
  font-size: 0.75rem;
  text-transform: uppercase;
  letter-spacing: 0.03em;
  color: var(--ink-dim);
}
.token {
  font-weight: 600;
}
.count {
  text-align: right;
  white-space: nowrap;
}
.upos,
.source,
.layer,
.routed,
.verdict-kind {
  font-size: 0.75rem;
  color: var(--ink-dim);
  margin-left: 0.35rem;
}
.vzor {
  margin-left: 0.35rem;
  background: var(--wash);
  padding: 0 0.3rem;
  border-radius: 3px;
}
.agreed {
  margin-left: 0.35rem;
  font-size: 0.7rem;
  color: var(--good-ink);
  border: 1px solid var(--good);
  border-radius: 3px;
  padding: 0 0.25rem;
}
.none {
  color: var(--ink-dim);
  font-style: italic;
}
.notes {
  margin: 0.25rem 0 0;
  padding-left: 1rem;
  font-size: 0.75rem;
  color: var(--ink-dim);
}
.verdicts {
  white-space: nowrap;
}
.verdicts button {
  margin-right: 0.25rem;
}
.route-choice {
  display: inline-flex;
  gap: 0.25rem;
  margin-left: 0.25rem;
}
.done {
  color: var(--ink-dim);
  font-size: 0.8rem;
}
.legend {
  margin-top: 1rem;
  font-size: 0.8rem;
  color: var(--ink-dim);
  max-width: 48rem;
}
.notice {
  color: var(--good-ink);
}
.error {
  color: var(--bad-ink);
}
.empty {
  color: var(--ink-dim);
}
</style>
