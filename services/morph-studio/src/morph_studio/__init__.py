# SPDX-License-Identifier: Apache-2.0
"""morph-studio — the editorial service for the LM lexicon (⚑LMP-D5).

"DB works, files publish" (detailed-design §10): a PG working store in the B-F5
shape, the enrichment cascade over it, and an export that emits canonical layer
files with a gate at `verified`. One instance per world (LM-5).

The thinking lives in `ttrmorph.enrich` — the guesser, the LLM classifier and
the cascade are importable and testable without any of this. What is here is
the store, the status machine, the endpoints and the Q-7 overlay lane.
"""
