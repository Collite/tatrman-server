# SPDX-License-Identifier: Apache-2.0
"""Match-if-any gazetteer (LM-8) — a trie step over a token's *whole* candidate set.

The suite's `TokenGazetteer` walks its trie one key per token: the token's
``lemma`` feature, as a single string. Once morphology is ambiguous that is no
longer enough. *byt* and *být* fold together, *má* is *mít* or *můj*, and a
token now carries a ranked `lemmas` **list**. A list keyed on *byt* must match a
token whose head lemma happens to be *být* — otherwise ranking, which is a
best-guess ordering with nothing behind it, silently decides which vocabulary
matches, and the world's own list loses to a frequency table.

So the trie step iterates every candidate the token carries:

* **single pass** — the whole candidate set advances the same walk; a
  candidate-per-pass loop would annotate the same span once per lemma and turn
  ambiguity into duplicate `Lookup`s
* **longest-match preserved** — the phrase still beats its own tail, exactly as
  in the deterministic gazetteer
* **dedup built in** — one `Lookup` per span per list, even when two of the
  token's lemmas reach the same entry

Everything else is inherited: entry loading, list features, the annotation type,
`__call__`'s annotation writing. This is a subclass of the wheel's own gazetteer
wiring rather than a second gazetteer, so `matching: exact` codes, the list
schema and the pack loader keep meaning exactly what they meant.

Still no scoring (NL-17): a `Lookup` either matched or it did not.
"""

from __future__ import annotations

from collections.abc import Iterable

from gatenlp.processing.gazetteer.tokengazetteer import (
    TokenGazetteer,
    TokenGazetteerMatch,
)

from ttrnlp.doc.importers import TOKEN_TYPE
from ttrnlp.gazetteer.annotate import Gazetteer
from ttrnlp.gazetteer.lists import GazetteerList
from ttrnlp.morph.annotate import FEATURE_LEMMA, FEATURE_LEMMAS
from ttrnlp.packs.diag import NLS_PACK_003, DiagnosticCollector

#: What the emitted `Lookup`s record as their matching mode. The list file still
#: declares ``matching: lemma`` — it is a lemma list — but a reader of the
#: annotation needs to know it matched against a candidate SET, because that is
#: why a list saying *byt* annotated a word that reads as *být*.
MATCHING = "morph-lemma"


class MorphGazetteer(TokenGazetteer):
    """A `TokenGazetteer` whose trie step reads the token's candidate lemmas."""

    def keys(self, token, doc=None) -> list[str]:
        """Every key this token may match on, best first, deduplicated.

        ``lemmas`` (the ranked list) if the morph annotator ran, else ``lemma``
        (an engine's single answer), else the covered text. The fallback chain
        is what lets one list serve a morph pipeline and an engine pipeline
        without being written twice.
        """
        raw = token.features.get(FEATURE_LEMMAS)
        if isinstance(raw, (list, tuple)) and raw:
            candidates = [str(lemma) for lemma in raw]
        else:
            single = token.features.get(FEATURE_LEMMA)
            if single:
                candidates = [str(single)]
            elif doc is not None:
                candidates = [doc[token]]
            else:  # pragma: no cover — a token layer always has one of these
                candidates = []

        keys = []
        for candidate in dict.fromkeys(candidates):
            key = self.mapfunc(candidate) if self.mapfunc else candidate
            if self.ignorefunc and self.ignorefunc(key):
                continue
            if key not in keys:
                keys.append(key)
        return keys

    def match(
        self,
        tokens,
        doc=None,
        longest_only=None,
        idx=0,
        endidx=None,
        matchfunc=None,
    ):
        """Match at ``idx``, following every candidate key at every step.

        Same contract as the base method — ``(matches, longest)`` — so `find`,
        `find_all` and `__call__` are inherited unchanged. The difference is
        that the walk carries a *set* of live trie nodes instead of one, which
        is what makes it a single pass: two candidates that reach the same node
        merge there and never produce two matches.
        """
        if endidx is None:
            endidx = len(tokens)
        if longest_only is None:
            longest_only = self.longest_only
        assert idx < endidx

        token = tokens[idx]
        if token.type == self.splittype:
            return [], 0

        active = _step(self.nodes, self.keys(token, doc))
        if not active:
            return [], 0

        consumed = [token]
        ignored = 0
        #: length (in consumed tokens) -> {listidx: data}, so one span cannot
        #: produce two Lookups from one list however many lemmas reached it.
        found: dict[int, dict[int | None, object]] = {}
        _collect(found, active, len(consumed))

        j = idx + 1
        while j < endidx and active:
            token = tokens[j]
            if token.type == self.splittype:
                break
            keys = self.keys(token, doc)
            if not keys:
                j += 1
                ignored += 1
                continue
            nxt = _advance(active, keys)
            if not nxt:
                break
            active = nxt
            consumed.append(token)
            _collect(found, active, len(consumed))
            j += 1

        if not found:
            return [], 0

        longest = max(found)
        lengths = [longest] if longest_only else sorted(found)
        matches = []
        for length in lengths:
            entries = found[length]
            data = list(entries.values())
            listidx = list(entries.keys())
            end = idx + length + ignored
            if matchfunc:
                matches.append(
                    matchfunc(idx, end, consumed[:length], data, listidx)
                )
            else:
                matches.append(
                    TokenGazetteerMatch(idx, end, consumed[:length], data, listidx)
                )
        return matches, longest


def _step(nodes, keys) -> list:
    return [nodes[key] for key in keys if key in nodes]


def _advance(active, keys) -> list:
    """One trie step for every live node, deduplicated by node identity."""
    nxt = []
    seen = set()
    for node in active:
        if not node.nodes:
            continue
        for key in keys:
            child = node.nodes.get(key)
            if child is not None and id(child) not in seen:
                seen.add(id(child))
                nxt.append(child)
    return nxt


def _collect(found: dict, active: list, length: int) -> None:
    """Record the matches among the live nodes, one entry per list."""
    for node in active:
        if not node.is_match:
            continue
        for data, listidx in zip(node.data, node.listidx, strict=False):
            found.setdefault(length, {}).setdefault(listidx, data)


def build_morph_gazetteer(lists: Iterable[GazetteerList]) -> Gazetteer:
    """Compile lemma lists into a match-if-any `Gazetteer`.

    Returns the suite's own `Gazetteer`, so the pipeline calls ``annotate(doc,
    lists=[...])`` exactly as it does for the deterministic one and the service
    wiring at NLS-P9 does not grow a second shape to configure.

    Raises:
        PackError: ``NLS-PACK-003`` on a duplicate list id — ids are how a
            pipeline names a list and how a `Lookup` records where it came from.
    """
    ordered = tuple(lists)
    collector = DiagnosticCollector()
    seen: set[str] = set()
    for source in ordered:
        if source.id in seen:
            collector.error(
                NLS_PACK_003,
                f"duplicate list id {source.id!r}: two lists cannot share an id",
            )
        seen.add(source.id)
    collector.raise_if_any()

    annotators = {source.id: _build(source) for source in ordered}
    return Gazetteer(lists=ordered, _annotators=annotators)


def _build(source: GazetteerList) -> MorphGazetteer:
    entries = [
        (entry.term.split(), dict(entry.features or {})) for entry in source.entries
    ]
    return MorphGazetteer(
        source=entries,
        source_fmt="gazlist",
        # `feature` is unused — `keys()` decides what a token matches on — but
        # the base class reads it when nothing overrides, so it names the
        # feature a reader would expect.
        feature=FEATURE_LEMMA,
        longest_only=True,
        skip_longest=True,
        token_type=TOKEN_TYPE,
        ann_type=source.annotation,
        list_type=source.annotation,
        list_features={"source": source.id, "matching": MATCHING},
    )


__all__ = ["MATCHING", "MorphGazetteer", "build_morph_gazetteer"]
