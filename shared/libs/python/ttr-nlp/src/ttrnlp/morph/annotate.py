# SPDX-License-Identifier: Apache-2.0
"""The morph annotator: ranked analyses onto token features (contracts §5).

Five features are written on every word token::

    lemma             head of the ranked list — what EVERY pre-morph consumer
                      already reads, so nothing downstream needs changing
    upos              the head reading's part of speech, for the same reason
    lemmas            the ranked list, for the match-if-any gazetteer (LM-8)
    analyses          the full records as dicts (contracts §1)
    morph_provenance  lexicon | statistical | provisional

``lemma`` staying a plain string is the compatibility hinge. The gazetteer's
``lemma`` matching mode, every pack that says ``lemma: faktura``, and the wire
payload all keep working unchanged; what they lose is the *other* candidates,
which is exactly what they lost before as well because the engine only ever gave
one. Nothing regresses, and a consumer that wants ambiguity now has somewhere to
read it.

``upos`` is that same hinge, and it was missing until a real pack walked into
the gap. A JAPE rule saying ``features: {upos: PROPN}`` is ordinary — the hero
pack's `ProperNounIsANameCandidate` is exactly that — and a pack is data, so
`ttrnlp.morph.helpers.upos_any` (a callable) is not available to it. Writing
only ``analyses`` would mean a pipeline that swapped its engine for the lexicon
kept matching lemmas and silently stopped matching parts of speech: the phase
still runs, the rule still compiles, nothing fires, and the diff that caused it
is in a config file three layers away. So the head reading's ``upos`` is written
beside its ``lemma``, from the same analysis, with the same "best guess with the
ranking behind it" caveat that ``lemma`` has always carried.

**Who wins over whom.** A lexicon hit always overwrites whatever an engine
wrote: curated vocabulary is the reason this layer exists. When there is no
lexicon hit and the token already carries an engine lemma, that value **stands**
and the provenance records ``statistical`` — because today's engine lemma *is*
the statistical leg of the chain (LM-6) until the pipeline drops the engine
entirely. Overwriting a MorphoDiTa lemma with our own stem guess would be a
regression sold as a feature.

**Which tokens.** Only ``kind: word``. Numbers, punctuation, symbols, codes and
abbreviations are carried untouched: none of them has a lemma in a curated
lexicon, and looking them up would report every ``č.`` and every invoice number
to the enrichment queue as a missing word. Tokens with no ``kind`` at all — the
ones an engine adapter built — are treated as words, because that is what an
engine's token layer contains.
"""

from __future__ import annotations

from ttrnlp.doc.importers import TOKEN_TYPE
from ttrnlp.doc.model import Document
from ttrnlp.morph.chain import MissSink, resolve
from ttrnlp.morph.records import PROVENANCE_STATISTICAL, Analysis
from ttrnlp.morph.snapshot import MorphState
from ttrnlp.morph.tokenize import tokenize

#: Token kinds that get a morphological lookup.
LOOKUP_KINDS = frozenset({"word"})

#: Feature names written by this module (contracts §5).
FEATURE_LEMMA = "lemma"
FEATURE_UPOS = "upos"
FEATURE_LEMMAS = "lemmas"
FEATURE_ANALYSES = "analyses"
FEATURE_PROVENANCE = "morph_provenance"

#: Where the tokenizer's own token kind is recorded.
FEATURE_KIND = "kind"


def build_document(text: str, *, profile: str = "cs", language: str = "") -> Document:
    """Tokenize ``text`` and return a `Document` carrying the tokens.

    The engine lane has `ttrnlp.doc.build_document`, which turns backend JSON
    into a document. The morph lane needs the same document with *our*
    tokenizer's spans on it — one substrate, produced once (LM-9) — and this is
    that entry point.

    Args:
        text: The text to analyse.
        profile: Tokenizer profile.
        language: Recorded as the document's ``language`` feature; defaults to
            the profile name, which is right for every profile we ship.
    """
    doc = Document(text)
    annset = doc.annset("")
    for token in tokenize(text, profile=profile):
        annset.add(
            token.start,
            token.end,
            TOKEN_TYPE,
            {"text": token.text, FEATURE_KIND: token.kind},
        )
    doc.features["language"] = language or profile
    doc.features["tokenizer"] = f"ttrnlp.morph:{profile}"
    return doc


def _as_dict(analysis: Analysis) -> dict:
    """One analysis, in a shape the wire and a human both read.

    Sets become sorted lists: a `frozenset` has no order, and a feature that
    serialises differently on two runs makes every golden comparison downstream
    flap for no reason.
    """
    payload = {
        "lemma": analysis.lemma,
        "upos": analysis.upos,
        "feats": sorted(analysis.feats),
        "provenance": analysis.provenance,
        "rank": analysis.rank,
    }
    if analysis.vzor:
        payload["vzor"] = analysis.vzor
    if analysis.flags:
        payload["flags"] = list(analysis.flags)
    if analysis.parts:
        payload["parts"] = list(analysis.parts)
    return payload


def annotate_morph(
    doc: Document,
    state: MorphState,
    *,
    miss_sink: MissSink | None = None,
    world: str = "",
) -> int:
    """Annotate every word token in ``doc`` with its analyses.

    Args:
        doc: A document with a ``Token`` layer — from `build_document` here, or
            from an engine adapter.
        state: The loaded snapshot.
        miss_sink: Called ``(world, form, "miss")`` for every token the lexicon
            did not answer. This is the enrichment loop's entry point.
        world: The world the misses belong to (LM-5: queues never cross worlds).

    Returns:
        How many tokens were annotated.

    Note:
        There is deliberately **no** statistical-seam parameter. The seam exists
        in `chain.resolve` and Wave C wires it; a parameter here would make it
        reachable from the pipeline before the model it needs exists, and an
        unwired seam with a caller is not unwired.
    """
    annotated = 0
    for token in doc.annset("").with_type(TOKEN_TYPE):
        kind = token.features.get(FEATURE_KIND)
        if kind is not None and kind not in LOOKUP_KINDS:
            continue

        form = token.features.get("text") or doc.text[token.start : token.end]
        analyses = resolve(form, state, miss_sink=miss_sink, world=world)
        if not analyses:  # pragma: no cover — the guess always answers
            continue

        engine_lemma = token.features.get(FEATURE_LEMMA)
        is_guess = all(a.provenance == PROVENANCE_STATISTICAL for a in analyses)

        if is_guess and engine_lemma:
            # The engine's lemma IS the statistical leg (LM-6). Keep it, and
            # say where it came from — replacing it with our stem guess would
            # be a regression dressed up as an upgrade.
            token.features[FEATURE_LEMMAS] = [engine_lemma]
            token.features[FEATURE_ANALYSES] = []
            token.features[FEATURE_PROVENANCE] = PROVENANCE_STATISTICAL
        else:
            token.features[FEATURE_LEMMA] = analyses[0].lemma
            if analyses[0].upos:
                # From the head analysis, beside its lemma — never invented, so
                # a reading with no part of speech leaves whatever an engine
                # wrote rather than replacing it with an empty string that
                # `features: {upos: NOUN}` would then have to be written to
                # avoid matching.
                token.features[FEATURE_UPOS] = analyses[0].upos
            token.features[FEATURE_LEMMAS] = list(
                dict.fromkeys(a.lemma for a in analyses)
            )
            token.features[FEATURE_ANALYSES] = [_as_dict(a) for a in analyses]
            token.features[FEATURE_PROVENANCE] = analyses[0].provenance
        annotated += 1
    return annotated


__all__ = [
    "FEATURE_ANALYSES",
    "FEATURE_KIND",
    "FEATURE_LEMMA",
    "FEATURE_LEMMAS",
    "FEATURE_UPOS",
    "FEATURE_PROVENANCE",
    "LOOKUP_KINDS",
    "annotate_morph",
    "build_document",
]
