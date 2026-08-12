# SPDX-License-Identifier: Apache-2.0
"""Lexicon importers — kaikki (Wiktionary) and UD_Czech-CAC.

Both are **classify-with-validation** (D-F1-α): an importer never trusts a
source table, it asks `ttrmorph.engine.classify` for the (vzor, flags) that
regenerate it exactly. A hit becomes a compact vzor entry; a miss becomes a
full-form entry carrying `LM-MORPH-005`. That is also why the importers double
as the engine's conformance harness — every imported word is one more assertion
that the paradigm tables are right.

Filled in at NLS-P8.3. The order there is law: the CAC split is frozen and
committed **before** any CAC read (LM-16/S-6).
"""
