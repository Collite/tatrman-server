# SPDX-License-Identifier: Apache-2.0
"""Gazetteers and list interchange (NL-3, NL-17).

Filled in at NLS-P2: `lists.py` (the interchange reader, contracts §4) and
`annotate.py` (TokenGazetteer lemma/ci/fold-diacritics + StringGazetteer exact
-> `Lookup` annotations). Deterministic longest-match only; scoring stays
world-side.
"""
