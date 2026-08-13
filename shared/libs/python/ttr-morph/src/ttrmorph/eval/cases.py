# SPDX-License-Identifier: Apache-2.0
"""Named acceptance cases (S-7 → NLS arc gate 7).

Three sentences, written down once, that have to keep working:

1. the hero with its diacritics,
2. the same hero typed without them,
3. the NL hero's lemma path — *faktury* → ``faktura``, *zákazníka* →
   ``zákazník`` — **through the core lexicon**, which is the NC-free proof: the
   demo does not resolve Czech by way of a non-commercially-licensed model.

A case is data, not a test function. It names an input text and, per token, the
lemma we must produce, how the answer was reached (``exact`` or ``folded``) and
what provenance it carries. That shape is deliberate: the same file is read by
the pytest suite, by ``ttr-morph eval``, and — at NLS-P9 — by the service tier
of the same gate, so a case cannot pass in one place and be absent in another.

**A token may be expected to be ABSENT**, and one is. *Kaufland* is a world
entity: the open vocabulary belongs to world layers (FI-1), so a core artifact
that resolved it would be a core artifact that had eaten somebody's customer
list. The hero case asserts the absence, and the same case run with the world
overlay asserts the presence — that pair is the whole enrichment loop, stated as
an expectation rather than a hope.

**The folded twin's law: an exact hit is never reported as folded.** Contracts
§5 ranks exact-form-derived analyses before fold-derived ones, and the twin is
where that ordering earns its keep — a form that exists verbatim in the artifact
must answer from itself, or a user who *did* type the diacritics is being served
somebody else's word first. The runner asserts it for every token of every case,
not only where a case remembered to say so.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass, field
from pathlib import Path

import yaml
from ttrnlp.morph import MATCHED_FOLDED, MorphState, tokenize
from ttrnlp.morph.annotate import LOOKUP_KINDS

#: ``<package root>/eval/cases`` — data, beside the split manifest, outside the
#: installed package. The same reasoning as `split.MANIFEST_PATH`: these are
#: committed evidence about an artifact, not something a wheel carries.
CASES_DIR = Path(__file__).resolve().parents[3] / "eval" / "cases"

CASE_SUFFIX = ".case.yaml"


class CaseError(RuntimeError):
    """A case file is malformed — which is a broken gate, not a failed case."""


@dataclass(frozen=True)
class Expectation:
    """What one token of a case must produce."""

    form: str
    lemma: str | None = None
    matched_via: str | None = None
    provenance: str | None = None
    #: The full expected head order, when a case is about ranking.
    lemmas: tuple[str, ...] | None = None
    absent: bool = False
    note: str = ""


@dataclass(frozen=True)
class Case:
    case: str
    title: str
    text: str
    tokens: tuple[Expectation, ...]
    #: Extra snapshot files to load beside the core, as paths relative to the
    #: package root. A case that names one is asserting the overlay path.
    overlays: tuple[str, ...] = ()
    note: str = ""


@dataclass
class CaseResult:
    case: Case
    failures: list[str] = field(default_factory=list)
    #: (form, lemma, matched_via, provenance) as actually produced — the table
    #: the report prints, so a reader sees the answer and not only the verdict.
    observed: list[tuple[str, str, str, str]] = field(default_factory=list)

    @property
    def passed(self) -> bool:
        return not self.failures


def read_case(path: Path) -> Case:
    raw = yaml.safe_load(Path(path).read_text(encoding="utf-8"))
    if not isinstance(raw, dict):
        raise CaseError(f"{path}: not a YAML mapping")
    for key in ("case", "text", "tokens"):
        if key not in raw:
            raise CaseError(f"{path}: missing `{key}`")
    tokens = []
    for index, entry in enumerate(raw["tokens"] or []):
        if not isinstance(entry, dict) or "form" not in entry:
            raise CaseError(f"{path}: token {index} has no `form`")
        unknown = set(entry) - set(Expectation.__dataclass_fields__)
        if unknown:
            raise CaseError(f"{path}: token {index} has unknown keys {sorted(unknown)}")
        lemmas = entry.get("lemmas")
        tokens.append(
            Expectation(
                form=str(entry["form"]),
                lemma=entry.get("lemma"),
                matched_via=entry.get("matched_via"),
                provenance=entry.get("provenance"),
                lemmas=tuple(lemmas) if lemmas else None,
                absent=bool(entry.get("absent", False)),
                note=str(entry.get("note") or ""),
            )
        )
    return Case(
        case=str(raw["case"]),
        title=str(raw.get("title") or raw["case"]),
        text=str(raw["text"]),
        tokens=tuple(tokens),
        overlays=tuple(raw.get("overlays") or ()),
        note=str(raw.get("note") or ""),
    )


def load_cases(directory: Path | str | None = None) -> list[Case]:
    root = Path(directory) if directory else CASES_DIR
    paths = sorted(root.glob(f"*{CASE_SUFFIX}"))
    if not paths:
        raise CaseError(f"no {CASE_SUFFIX} files under {root}")
    return [read_case(path) for path in paths]


def run_case(case: Case, state: MorphState, *, profile: str = "cs") -> CaseResult:
    """Run one case against a loaded state and report every failure it has.

    Every mismatch is collected rather than raised at the first one: a case that
    stops at its first bad token tells you one thing per run, and the reason to
    write the whole sentence down was to see the whole sentence.
    """
    result = CaseResult(case=case)
    # The same kinds the annotator looks up, imported rather than restated: a
    # case that scored a token the service will never look up would pass a gate
    # the service cannot.
    words = [
        token.text
        for token in tokenize(case.text, profile=profile)
        if token.kind in LOOKUP_KINDS
    ]
    expected = {expectation.form: expectation for expectation in case.tokens}

    unexpected = [form for form in expected if form not in words]
    if unexpected:
        result.failures.append(
            f"the case expects tokens the tokenizer does not produce: {unexpected} "
            f"(it produced {words})"
        )

    for form in words:
        hit = state.lookup(form)
        analyses = hit.analyses if hit else ()
        lemma = analyses[0].lemma if analyses else ""
        via = hit.matched_via if hit and analyses else ""
        provenance = analyses[0].provenance if analyses else ""
        result.observed.append((form, lemma, via, provenance))

        # The folded twin's law, asserted for every token of every case rather
        # than only where a case says so (see the module note).
        if via == MATCHED_FOLDED and form in state.exact:
            result.failures.append(
                f"{form!r}: answered via `folded` although it is in the artifact "
                "verbatim — exact-form-derived analyses rank first (contracts §5)"
            )

        expectation = expected.get(form)
        if expectation is None:
            continue
        result.failures.extend(_check(expectation, lemma, via, provenance, analyses))

    return result


def _check(
    expectation: Expectation,
    lemma: str,
    via: str,
    provenance: str,
    analyses: Sequence,
) -> list[str]:
    form = expectation.form
    if expectation.absent:
        if analyses:
            return [
                f"{form!r}: expected NO lexicon answer (it is world vocabulary, "
                f"FI-1) and got lemma {lemma!r} from {provenance!r}"
            ]
        return []

    failures: list[str] = []
    if not analyses:
        return [f"{form!r}: no analysis at all; the case expects {expectation.lemma!r}"]
    if expectation.lemma is not None and lemma != expectation.lemma:
        failures.append(
            f"{form!r}: head lemma is {lemma!r}, the case expects "
            f"{expectation.lemma!r}"
        )
    if expectation.matched_via is not None and via != expectation.matched_via:
        failures.append(
            f"{form!r}: matched_via is {via!r}, the case expects "
            f"{expectation.matched_via!r}"
        )
    if expectation.provenance is not None and provenance != expectation.provenance:
        failures.append(
            f"{form!r}: provenance is {provenance!r}, the case expects "
            f"{expectation.provenance!r}"
        )
    if expectation.lemmas is not None:
        ours = tuple(dict.fromkeys(analysis.lemma for analysis in analyses))
        if ours[: len(expectation.lemmas)] != expectation.lemmas:
            failures.append(
                f"{form!r}: ranked lemmas are {ours}, the case expects "
                f"{expectation.lemmas} first"
            )
    return failures


def run_cases(
    cases: Sequence[Case], state: MorphState, *, profile: str = "cs"
) -> list[CaseResult]:
    return [run_case(case, state, profile=profile) for case in cases]


__all__ = [
    "CASES_DIR",
    "CASE_SUFFIX",
    "Case",
    "CaseError",
    "CaseResult",
    "Expectation",
    "load_cases",
    "read_case",
    "run_case",
    "run_cases",
]
