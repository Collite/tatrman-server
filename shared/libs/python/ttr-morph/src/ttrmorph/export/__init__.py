# SPDX-License-Identifier: Apache-2.0
"""Generation-expanded gazetteer lists (LM-7, C-O2).

The export direction of the engine: a world's entity list says ``Kaufland``
once and the exporter emits every form the paradigm produces, with per-form
features. This is what lets a `matching: exact` list survive Czech declension
without the runtime doing morphology.

C-O2 is design law: a snapshot bump **triggers** the re-runs, so expanded lists
can never lag the lexicon they were expanded from. Merged into the NLS-P4
exporter task; filled in at NLS-P8.4.
"""
