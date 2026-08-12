# SPDX-License-Identifier: Apache-2.0
"""Layer files to snapshot — `ttr-morph compile` (contracts §2/§3).

Expands vzor entries through the engine, merges the layers, ranks, folds and
hashes. The three format rulings the NLS-P7.2 loader already enforces and this
compiler must match:

* the content hash is taken over the **raw row strings**, sorted, joined with
  ``\\n`` — not over a re-serialization of parsed rows;
* ``parts:`` decomposition rides in the *flags* cell (``parts:aby+bych``);
* ne-exceptions are one per line in their own header section.

Filled in at NLS-P8.2, with a cross-package test asserting compiler and loader
agree on the hash of the same rows.
"""
