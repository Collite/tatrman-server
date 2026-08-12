# SPDX-License-Identifier: Apache-2.0
"""``NOTICE-morph.md`` — the attribution the share-alike sources require (S-2).

Generated, never hand-written. A NOTICE maintained by hand is a NOTICE that
falls behind the layer it describes: someone adds a source, the artifact ships,
and the file still names the two sources it had last quarter. Here the input is
the same ``attribution:`` block the compiler already refuses to publish without
(`LM-MORPH-004`), so the document cannot describe a layer that is not in the
release, and a layer cannot be in the release without a section here.

CC BY-SA 4.0 asks for the name of the source, a link to it, the licence with a
link, and — the part most often missed — an indication that the material was
modified and how. All four are required fields on `Attribution`, so the
rendering is a table lookup rather than a judgement call.
"""

from __future__ import annotations

from collections.abc import Sequence

from ttrmorph.compile.layers import Layer

_HEADER = """# NOTICE — Tatrman Czech morphology snapshot

Artifact: `{language}` morphology snapshot, version `{version}`.

This artifact is an **aggregate**. The main body (`*.morph.snap`) is
suite-licensed material authored for this project. Material taken from
share-alike sources is *not* in it: each such source compiles into a separable
member file (`*.morph.part`) listed below, which keeps its own licence. Removing
a member file removes that source's contribution and nothing else.

Every row in every file names the layer it came from in its `layer` column, so
the boundary is checkable in the artifact, not only here.
"""

_NO_SOURCES = """
## Share-alike sources

None. This artifact contains suite-licensed material only.
"""

_SECTION = """
## `{part}` — {source}

| | |
|---|---|
| Layer | `{layer}` |
| Source | {source} |
| Origin | <{url}> |
| Licence | [{license}]({license_url}) |
| Extracted | {extracted} |
| Modifications | {transformation} |
"""


def render_notice(
    layers: Sequence[Layer], *, snapshot_version: str, language: str
) -> str:
    """Render the NOTICE for the share-alike layers of one compile."""
    parts = [_HEADER.format(language=language, version=snapshot_version)]

    if not layers:
        parts.append(_NO_SOURCES)
        return "".join(parts)

    parts.append("\n## Share-alike sources\n")
    for layer in sorted(layers, key=lambda item: item.layer):
        attribution = layer.attribution
        if attribution is None:  # pragma: no cover — LM-MORPH-004 refuses first
            continue
        parts.append(
            _SECTION.format(
                part=f"{layer.layer}.morph.part",
                layer=layer.layer,
                source=attribution.source,
                url=attribution.url,
                license=attribution.license,
                license_url=attribution.license_url,
                extracted=attribution.extracted,
                transformation=attribution.transformation,
            )
        )
    return "".join(parts)


__all__ = ["render_notice"]
