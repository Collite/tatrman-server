# SPDX-License-Identifier: Apache-2.0
"""The pinned gatenlp surface, re-exported (NL-2).

Every part of the suite imports `Document`/`AnnotationSet` from HERE, never from
`gatenlp` directly. That gives the vendored-subset exit (pre-approved at >2 local
patches) exactly one seam to cut at: swap the bodies of this module and nothing
upstream of it changes.

The pin is asserted at import time rather than left to the dependency resolver
alone. A wheel installed next to a different gatenlp — an editable dev tree, a
consumer that pinned its own — would otherwise fail much later and much more
obscurely, inside PAMPAC.
"""

from __future__ import annotations

import gatenlp
from gatenlp import AnnotationSet, Document

#: The one gatenlp version this suite is built and tested against (NL-2).
PINNED_GATENLP_VERSION = "1.0.8"

if gatenlp.__version__ != PINNED_GATENLP_VERSION:  # pragma: no cover — env guard
    raise RuntimeError(
        f"ttr-nlp requires gatenlp=={PINNED_GATENLP_VERSION} (NL-2), "
        f"found {gatenlp.__version__}. The pin is a contract, not a floor: "
        "PAMPAC's parser and selection internals are what the rule compiler and "
        "the JAPE-exact executor are written against."
    )

__all__ = ["PINNED_GATENLP_VERSION", "AnnotationSet", "Document"]
