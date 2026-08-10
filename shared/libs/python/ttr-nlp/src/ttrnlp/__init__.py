# SPDX-License-Identifier: Apache-2.0
"""ttr-nlp — the Tatrman NLP suite library.

One OSS suite for the whole ecosystem (NL-9), built in `tatrman-server` and
consumed everywhere: the `services/nlp` front hosts it in-process, `nlp-mcp` and
the DFP model-validator import it, and the CLI wraps the same code path.

Module map (architecture §2):

    ttrnlp.doc          annotation model — engine JSON -> gatenlp Document
    ttrnlp.rules        the JAPE-class rule engine — YAML DSL -> PAMPAC
    ttrnlp.gazetteer    list interchange + Lookup annotation
    ttrnlp.packs        pack/list loading (fail-all) + THE validation path
    ttrnlp.client       gRPC client + HTTP engine-adapter clients
    ttrnlp.cli          `ttr-nlp validate`

The load-bearing property (P-4): annotations are the interface. Engine output,
gazetteer `Lookup`s and rule output all live in one `Document`; the rule engine
neither knows nor cares which producer wrote an annotation.

The version here is a build-time artifact: the repo tree always says 0.0.0 and
the publish workflow injects the real version from the release tag. Read it from
the installed distribution when one exists so consumers see the published value.
"""

from __future__ import annotations

from importlib.metadata import PackageNotFoundError
from importlib.metadata import version as _dist_version

try:
    __version__ = _dist_version("ttr-nlp")
except PackageNotFoundError:  # pragma: no cover — running from a source tree
    __version__ = "0.0.0"

__all__ = ["__version__"]
