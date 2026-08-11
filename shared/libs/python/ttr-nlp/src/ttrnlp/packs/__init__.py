# SPDX-License-Identifier: Apache-2.0
"""Pack and list lifecycle (NL-15).

`diag.py` is the shared diagnostic shape, `loader.py` turns sources into an
immutable snapshot (FAIL-ALL-OR-NOTHING) and `validate.py` is THE single
validation code path that the CLI, the service boot and `ReloadPacks` all run —
so a pack that validates on a laptop validates identically in the cluster.

**The names below resolve on first use, not at import.** contracts §6 puts
``load_sources`` and ``validate_sources`` on this package, but importing them
here eagerly creates a cycle: ``diag.py`` lives under ``packs``, so
``ttrnlp.rules`` and ``ttrnlp.gazetteer`` both import from this package, while
``loader`` imports from both of them. Initialising the package would then
initialise its own dependents, and whichever of the three a consumer imported
first would decide whether it worked. PEP 562's module ``__getattr__`` gives the
documented surface without putting that ordering trap in it.
"""

from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:  # pragma: no cover — for type checkers and readers
    from ttrnlp.packs.diag import Diagnostic as Diagnostic
    from ttrnlp.packs.diag import PackError as PackError
    from ttrnlp.packs.loader import LoadedState as LoadedState
    from ttrnlp.packs.loader import LoadError as LoadError
    from ttrnlp.packs.loader import load_sources as load_sources
    from ttrnlp.packs.validate import validate_sources as validate_sources

#: name -> the submodule that defines it.
_EXPORTS = {
    "Diagnostic": "ttrnlp.packs.diag",
    "PackError": "ttrnlp.packs.diag",
    "LoadError": "ttrnlp.packs.loader",
    "LoadedState": "ttrnlp.packs.loader",
    "load_sources": "ttrnlp.packs.loader",
    "validate_sources": "ttrnlp.packs.validate",
}

__all__ = sorted(_EXPORTS)


def __getattr__(name: str) -> Any:
    module_name = _EXPORTS.get(name)
    if module_name is None:
        raise AttributeError(f"module {__name__!r} has no attribute {name!r}")
    from importlib import import_module

    return getattr(import_module(module_name), name)


def __dir__() -> list[str]:
    return __all__
