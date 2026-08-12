# SPDX-License-Identifier: Apache-2.0
"""Writing a layer file an analyst can read (contracts §3).

Importers produce layers, and a layer is *editorial truth* — the thing a human
edits when the importer got it wrong. So this does not dump YAML: it writes the
same shape a hand-authored file has, entry order stable, one entry per lemma,
with a header comment saying which run produced it and how to reproduce that
run. An imported layer nobody can diff is an imported layer nobody can correct.

The output is round-tripped through `read_layer` by the importers' tests, so
"an analyst can read it" and "the compiler accepts it" are the same claim.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass

import yaml


@dataclass(frozen=True)
class LayerHeader:
    """Everything above ``entries:``."""

    layer: str
    language: str
    license: str
    attribution: Mapping[str, str] | None = None
    version: int = 1
    #: Free text above the document — how this file was produced.
    note: str = ""


def _block(text: str) -> str:
    return "\n".join(f"# {line}".rstrip() for line in text.strip().splitlines())


def render_layer(header: LayerHeader, entries: Sequence[Mapping[str, object]]) -> str:
    """Render a layer file.

    Entries are emitted in the order given — the importers sort by (lemma,
    upos), so a re-run of the same source produces the same file and a diff
    shows what the *source* changed rather than what a dict iteration did.
    """
    document: dict[str, object] = {
        "layer": header.layer,
        "version": header.version,
        "language": header.language,
        "license": header.license,
        "attribution": dict(header.attribution) if header.attribution else None,
        "entries": [dict(entry) for entry in entries],
    }
    body = yaml.safe_dump(
        document,
        allow_unicode=True,
        sort_keys=False,
        default_flow_style=False,
        width=88,
    )
    return (_block(header.note) + "\n" + body) if header.note else body


def vzor_entry(
    lemma: str,
    upos: str,
    vzor: str,
    flags: Sequence[str],
    provenance: str,
) -> dict[str, object]:
    entry: dict[str, object] = {"lemma": lemma, "upos": upos, "vzor": vzor}
    if flags:
        entry["flags"] = list(flags)
    entry["provenance"] = provenance
    return entry


def forms_entry(
    lemma: str,
    upos: str,
    forms: Sequence[tuple[str, str]],
    provenance: str,
    *,
    vzor: str = "",
    flags: Sequence[str] = (),
) -> dict[str, object]:
    """A full-form entry.

    ``vzor`` is optional and carries the importer's *rejected* guess when there
    was one: the compiler will regenerate it, disagree, and raise
    `LM-MORPH-005` naming the difference. That is the point — the mismatch is
    the review queue's input, and an entry that quietly dropped the guess would
    leave the studio nothing to show a human.
    """
    entry: dict[str, object] = {"lemma": lemma, "upos": upos}
    if vzor:
        entry["vzor"] = vzor
        if flags:
            entry["flags"] = list(flags)
    entry["provenance"] = provenance
    entry["forms"] = [
        {"form": form, "feats": feats} for form, feats in sorted(set(forms))
    ]
    return entry


__all__ = ["LayerHeader", "forms_entry", "render_layer", "vzor_entry"]
