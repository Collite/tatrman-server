# SPDX-License-Identifier: Apache-2.0
"""Which sources may feed the lexicon — the poison list, as code (D-ε / Q-6).

The whole reason the LM effort exists is that the Czech model stack is
non-commercial: MorfFlex, MorphoDiTa, PDT, FicTree, and hunspell-cs under GPL.
A lexicon built to be free of them is worth exactly as much as the weakest
moment in its build, and that moment is a hurried afternoon where somebody has a
better-looking word list to hand.

So the allowlist is not documentation. An importer declares its source id, this
module refuses anything not on the list, and it also looks at the *path* it was
handed — because "I passed the right id" and "I opened the right file" are
different claims, and only the second one is checkable here.

None of this can stop a determined person, and it is not meant to. It is meant
to make the poisoned path require a deliberate edit to this file, with this
docstring above it, in a diff a reviewer will see.
"""

from __future__ import annotations

from pathlib import Path

#: The three sources that may write into a layer (contracts §3 provenance,
#: minus ``llm`` which produces proposals for a human, never a file directly).
ALLOWED_SOURCES = frozenset({"kaikki", "ud-czech-cac", "hand"})

#: Substrings that name a poisoned corpus. Matched case-insensitively against
#: the input path. Deliberately broad — a false positive costs a rename, a
#: false negative costs the NC-free claim the whole effort rests on.
POISONED = (
    "morfflex",
    "morphodita",
    "pdt",
    "fictree",
    "hunspell",
    "czech-pdt",
    "cs_pdt",
)


class PoisonedSource(RuntimeError):
    """An input that would make the lexicon non-commercial or GPL."""


def require_allowed(source: str, path: Path | str | None = None) -> None:
    """Refuse an importer run whose source is not on the allowlist.

    Args:
        source: The importer's declared source id.
        path: The file it is about to read, if there is one.

    Raises:
        PoisonedSource: The id is not allowed, or the path names a corpus on
            the poison list.
    """
    if source not in ALLOWED_SOURCES:
        raise PoisonedSource(
            f"source {source!r} is not on the allowlist "
            f"{sorted(ALLOWED_SOURCES)} — the NC-free claim of the cs path is "
            "an assertion about which corpora fed this lexicon (D-ε/Q-6), and "
            "it is only as good as this check"
        )
    if path is None:
        return
    lowered = str(path).casefold()
    for marker in POISONED:
        if marker in lowered:
            raise PoisonedSource(
                f"{path} names {marker!r}, which is on the poison list "
                "(non-commercial or GPL). No output derived from it may enter "
                "any layer — including as a hint"
            )


__all__ = ["ALLOWED_SOURCES", "POISONED", "PoisonedSource", "require_allowed"]
