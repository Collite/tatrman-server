// SPDX-License-Identifier: Apache-2.0
import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

import EntryView from '@/views/EntryView.vue'
import NewEntryView from '@/views/NewEntryView.vue'
import QueueView from '@/views/QueueView.vue'
import WordView from '@/views/WordView.vue'

// History mode, not hash: the backend's catch-all serves `index.html` for any
// path that is not an API route or a file, so `/word/Kauflandu` is a link
// somebody can paste into a message — which for an editorial tool is most of
// how the queue gets worked.
export const routes: RouteRecordRaw[] = [
  { path: '/', redirect: { name: 'queue' } },
  { path: '/queue', name: 'queue', component: QueueView },
  { path: '/word/:form?', name: 'word', component: WordView },
  { path: '/entry/new', name: 'new-entry', component: NewEntryView },
  { path: '/entry/:id', name: 'entry', component: EntryView },
]

export default createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
})
