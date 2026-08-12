// SPDX-License-Identifier: Apache-2.0
// The two provenances, which are not the same thing (contracts §1 and §3).
//
// **Runtime provenance** — `lexicon` | `statistical` | `provisional` — is what a
// `LookupResult` carries and what an answer's trustworthiness is read from. The
// studio's own lookup answers from its working store, so everything it returns
// is `lexicon`, except rows live under the narrow Q-7 ruling, which are
// `provisional`. `statistical` is the FRONT's fallback — the guessed analysis
// that produced the queue item in the first place — and the studio never
// serves one. It is in this vocabulary anyway because it is the reason a token
// is in the queue, and a badge set that could not name it would be lying about
// where these words come from.
//
// **Editorial provenance** — `wiktionary` | `cac` | `manual` | `llm` — is where
// the *material* came from, and it is the licence boundary: the share-alike
// layers are separable in the compiled artifact precisely along it (C-F3). It
// belongs next to the entry for the same reason it belongs in the file.

import type { Entry } from '@/api/client'

export type RuntimeProvenance = 'lexicon' | 'statistical' | 'provisional'

export const RUNTIME_LABEL: Record<RuntimeProvenance, string> = {
  lexicon: 'lexicon',
  statistical: 'statistical',
  provisional: 'provisional',
}

export const RUNTIME_TITLE: Record<RuntimeProvenance, string> = {
  lexicon: 'A curated entry: the forms come from the lexicon.',
  statistical:
    'A guessed analysis from the front’s fallback — this is why the token is in the queue.',
  provisional:
    'Live in the world overlay without a human having seen it (Q-7). Verify makes it permanent; reject retracts it.',
}

/** What a runtime `LookupResult` would say about this entry. */
export function runtimeProvenance(entry: Pick<Entry, 'provisional'>): RuntimeProvenance {
  return entry.provisional ? 'provisional' : 'lexicon'
}

/** Editorial provenances that carry a share-alike obligation (C-F3). */
const SHARE_ALIKE = new Set(['wiktionary', 'cac'])

export function shareAlike(provenance: string): boolean {
  return SHARE_ALIKE.has(provenance)
}

export const EDITORIAL_TITLE: Record<string, string> = {
  wiktionary: 'Imported from Wiktionary — share-alike, a separable layer in the artifact.',
  cac: 'From the CAC corpus — share-alike, a separable layer in the artifact.',
  manual: 'Authored or corrected by a person here.',
  llm: 'Classified by the LLM leg, then generated and checked by the engine.',
}
