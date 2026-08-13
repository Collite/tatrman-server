# SPDX-License-Identifier: Apache-2.0
"""Layer files to snapshot — `ttr-morph validate` and `ttr-morph compile`.

`layers.py` is the editorial side: what an analyst writes, and every check that
can be made about one file on its own (schema, licence boundary, does the
declared pattern regenerate the declared forms). `snapshot.py` is the artifact
side: expansion, merge, ranks, the fold index, the hash and the routing of
share-alike layers into separable member files. `notice.py` renders the
attribution those member files require.

The split is the same one `ttr-nlp` draws between validating a pack and loading
it, and for the same reason: a layer that validates on an analyst's laptop must
compile in CI, so both run one reader.

The three format rulings the NLS-P7.2 loader enforces are obeyed here, and two
of the three by *sharing the implementation* rather than matching it — the
content hash comes from `ttrnlp.morph.snapshot.content_hash` and the fold from
`ttrnlp.morph.records.fold`. Only what cannot be shared is restated: the
``parts:`` spelling in the flags cell, and one ne-exception per line.
"""

from ttrmorph.compile.layers import Layer, read_layer, validate_layers
from ttrmorph.compile.snapshot import (
    CompileResult,
    compile_layers,
    read_frequencies,
)

__all__ = [
    "CompileResult",
    "Layer",
    "compile_layers",
    "read_frequencies",
    "read_layer",
    "validate_layers",
]
