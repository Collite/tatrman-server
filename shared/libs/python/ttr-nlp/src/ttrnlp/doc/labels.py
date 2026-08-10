# SPDX-License-Identifier: Apache-2.0
"""NER label vocabularies.

The suite transports whatever label an engine produced; it does not harmonise
the two vocabularies in play, because rewriting engine output to look uniform
would be a lie told at the exact place a rule author needs the truth:

* **cs / NameTag 3** — CNEC 2.0, a fine-grained tagset (``gu`` city, ``gc``
  country, ``pf`` firstname, ``ps`` surname, ``if`` firm/institution, ``oa``
  artifact, ``th`` time interval, …). Mapped here to the universal coarse set.
* **en / spaCy** — OntoNotes labels (``ORG``, ``PERSON``, ``GPE``, …) pass
  straight through untouched.

So a Czech pack matches ``{ann: ORGANIZATION}`` and its English sibling matches
``{ann: ORG}``. That asymmetry is real and belongs in the pack, not hidden in a
translation layer.

The table is the one from ``services/nlp``'s NameTag adapter; it moves here so
the wheel owns it before the adapter itself follows at NLS-P3 (⚑NLS-D7).
"""

from __future__ import annotations

#: CNEC 2.0 leading class letter -> universal coarse entity type.
CNEC_CLASS_TO_UNIVERSAL = {
    "p": "PERSON",  # personal names
    "g": "LOCATION",  # geographical
    "i": "ORGANIZATION",  # institutions
    "t": "DATE",  # time expressions
}

#: Where anything unmapped lands. Products (``o*``) end up here, for instance —
#: the resolver's domain path, not this label, is what resolves a product name.
UNIVERSAL_FALLBACK = "MISC"

#: Prefix marking a `normalizedValue` that carries a raw CNEC tag.
CNEC_PREFIX = "cnec:"


def cnec_to_universal(cnec_tag: str) -> str:
    """Map a CNEC 2.0 tag to its universal coarse label.

    Classification is by the FIRST letter, case-insensitively. An unknown or
    empty tag collapses to ``MISC`` so that an entity the engine found is never
    silently dropped — losing it entirely is a worse failure than typing it
    coarsely.
    """
    if not cnec_tag:
        return UNIVERSAL_FALLBACK
    return CNEC_CLASS_TO_UNIVERSAL.get(cnec_tag[0].lower(), UNIVERSAL_FALLBACK)


def parse_cnec(normalized_value: str) -> str | None:
    """Extract the raw CNEC tag from a `normalizedValue`, if it carries one.

    Returns ``None`` when the value is not a CNEC marker at all (spaCy sends an
    empty string), and ``""`` when the marker is present but the tag is empty —
    the caller must distinguish those: the first means "trust the label", the
    second means "this came from NameTag and the tag was lost".
    """
    if not normalized_value.startswith(CNEC_PREFIX):
        return None
    return normalized_value[len(CNEC_PREFIX) :]


__all__ = [
    "CNEC_CLASS_TO_UNIVERSAL",
    "CNEC_PREFIX",
    "UNIVERSAL_FALLBACK",
    "cnec_to_universal",
    "parse_cnec",
]
