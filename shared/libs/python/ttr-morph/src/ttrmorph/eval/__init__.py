# SPDX-License-Identifier: Apache-2.0
"""The eval harness — UD_Czech-CAC as the SOLE oracle (LM-16/S-6).

Metrics: coverage, lemma-in-set accuracy, head-of-list accuracy, fold-collision
rate. Named acceptance cases from S-7.

The constraint that outranks everything else in this sub-package: the split is
frozen **once**, by `ttr-morph split --seed 20260811`, committed as
``eval/cac-split.json`` before any CAC-derived seeding runs, and the Wave C
LM-6 training task trains on the train side of *that same manifest*. No
re-split, ever — a model trained on a different partition makes every number
this harness produces a measurement of memorization.

Filled in at NLS-P8.4; the split ceremony itself is NLS-P8.3's first commit.
"""
