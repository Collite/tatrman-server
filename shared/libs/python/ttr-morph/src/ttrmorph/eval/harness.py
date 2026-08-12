# SPDX-License-Identifier: Apache-2.0
"""``ttr-morph eval`` — the artifact measured against the oracle (contracts §11).

**This module is the only legitimate reader of the TEST side.** It is the one
place in the repo that passes ``allow_test=True`` to `importers.cac.sentences`,
and `tests/test_test_side_guard.py` asserts that no other file does. Everything
else — seeding, frequencies, the QA comparison — reads the train side, because a
lexicon that was built from the sentences it is scored on scores whatever it was
built from.

## Two runs, and only one of them needs the corpus

``--cac <dir>``
    The full contracts §11 measurement: coverage, lemma-in-set accuracy,
    head-of-list accuracy and fold-collision rate over the frozen test split,
    with a per-upos breakdown. Needs a checkout of UD_Czech-CAC.

``--gate``
    What CI runs on a ``morph/v*`` tag. The named acceptance cases (S-7), the
    target-vocabulary coverage of the artifact, and a comparison of the
    artifact's own shape against the committed baseline. **No corpus.**

The split is not an accident of convenience, it is the honest shape. A release
gate that downloaded the oracle on every run would be a gate that reads the test
side in an environment nobody is watching, on a schedule, forever; and one that
shipped the test side inside the repo would put the sentences the model is
scored on into every clone. So the corpus numbers are produced deliberately, by
a human or a scheduled job with the corpus in hand, and *committed* as
``eval/baseline.json`` + ``eval/eval-report.md``. The gate then checks what it
can actually see: that the cases still pass and that the artifact has not shrunk
under the numbers those files claim.

If ``--cac`` and ``--gate`` are given together the corpus metrics are gated too,
which is what a scheduled full run does.

## The statistical seam is not wired, and that is the point

Everything here goes through `MorphState.lookup` — the lexicon leg alone. The
chain's statistical seam (`ttrnlp.morph.chain`) exists, defaults to ``None``,
and nothing passes one in v1. So the coverage number is a measurement of the
curated lexicon and the **gap is the Wave C headroom**, stated in the report
template rather than left for a reader to work out.
"""

from __future__ import annotations

import json
from collections.abc import Sequence
from dataclasses import dataclass, field
from pathlib import Path

import yaml
from ttrnlp.morph import MorphState, load_morph

from ttrmorph.eval.cases import CaseResult, load_cases, run_cases
from ttrmorph.eval.metrics import GoldToken, Metrics, score
from ttrmorph.eval.split import SIDES, load_manifest
from ttrmorph.importers.cac import lexical_rows, sentences

#: ``<package root>/eval`` — the committed evidence directory.
EVAL_DIR = Path(__file__).resolve().parents[3] / "eval"

BASELINE_PATH = EVAL_DIR / "baseline.json"
REPORT_PATH = EVAL_DIR / "eval-report.md"

#: The coverage target the artifact is built against (P8.3's target list).
TARGETS_PATH = (
    Path(__file__).resolve().parents[1] / "seed" / "data" / "cs" / "target-words.yaml"
)

#: How far a metric may drift below the baseline before the gate fails. Not
#: zero: the artifact is recompiled from layer files whose ranks move when the
#: frequency table does, and a head-of-list metric that may never wobble by a
#: thousandth is a gate that fails on noise and gets disabled.
TOLERANCE = 0.005


@dataclass
class Report:
    """Everything a run produced. Rendered to Markdown and to JSON."""

    version: str
    content_hash: str
    rows: int
    forms: int
    layers: tuple[tuple[str, str], ...]
    fold_collisions: int
    targets_total: int = 0
    targets_covered: int = 0
    targets_missing: list[str] = field(default_factory=list)
    targets_thin: list[str] = field(default_factory=list)
    metrics: Metrics | None = None
    cases: list[CaseResult] = field(default_factory=list)
    corpus: str = ""
    sentences: int = 0

    @property
    def cases_passed(self) -> int:
        return sum(1 for case in self.cases if case.passed)

    @property
    def target_coverage(self) -> float:
        return self.targets_covered / self.targets_total if self.targets_total else 0.0

    def as_dict(self) -> dict:
        payload: dict = {
            "artifact": {
                "version": self.version,
                "content_hash": self.content_hash,
                "rows": self.rows,
                "forms": self.forms,
                "fold_collisions": self.fold_collisions,
                "layers": [list(pair) for pair in self.layers],
            },
            "targets": {
                "total": self.targets_total,
                "covered": self.targets_covered,
                "coverage": round(self.target_coverage, 4),
                "missing": self.targets_missing,
                "thin": self.targets_thin,
            },
            "cases": {
                "total": len(self.cases),
                "passed": self.cases_passed,
                "failed": [
                    {"case": result.case.case, "failures": result.failures}
                    for result in self.cases
                    if not result.passed
                ],
            },
        }
        if self.metrics is not None:
            payload["corpus"] = {
                "source": self.corpus,
                "sentences": self.sentences,
                **self.metrics.as_dict(),
            }
        return payload


# ── the corpus side ──────────────────────────────────────────────────────────


def gold_tokens(
    conllu_files: Sequence[Path], *, side: str = "test"
) -> tuple[list[GoldToken], int]:
    """Counted gold tokens from ``side`` of the frozen split.

    ⚑ The single ``allow_test=True`` in this repo. See the module note before
    adding another one anywhere.
    """
    manifest = load_manifest()
    counts: dict[tuple[str, str, str], int] = {}
    seen = 0
    for _, rows in sentences(
        conllu_files, side=side, manifest=manifest, allow_test=True
    ):
        seen += 1
        for form, lemma, upos, _feats in lexical_rows(rows):
            key = (form, lemma, upos)
            counts[key] = counts.get(key, 0) + 1
    tokens = [
        GoldToken(form=form, lemma=lemma, upos=upos, count=count)
        for (form, lemma, upos), count in sorted(counts.items())
    ]
    return tokens, seen


# ── the artifact side ────────────────────────────────────────────────────────


def target_lemmas(path: Path | None = None) -> list[str]:
    raw = yaml.safe_load(Path(path or TARGETS_PATH).read_text(encoding="utf-8"))
    groups = (raw or {}).get("groups")
    if not isinstance(groups, dict):
        raise ValueError(f"{path or TARGETS_PATH}: no `groups:` mapping")
    seen: dict[str, None] = {}
    for words in groups.values():
        for word in words or []:
            seen.setdefault(str(word), None)
    return list(seen)


#: Parts of speech that inflect in Czech. A target lemma of one of these
#: holding a single form in the artifact is *thin* — see `target_coverage`.
INFLECTING = frozenset({"NOUN", "PROPN", "ADJ", "VERB", "PRON", "DET", "NUM"})


def target_coverage(
    state: MorphState, lemmas: Sequence[str]
) -> tuple[int, list[str], list[str]]:
    """Which target lemmas the artifact can analyse, cannot, and barely can.

    A lemma counts as **covered** when looking up the lemma itself answers *with
    that lemma*. Not "answers at all": *kvartál* resolving to *kvartet* by a fold
    would be a miss wearing a hit's clothes.

    A covered lemma is **thin** when it inflects and the artifact holds one
    single form of it. That distinction is here because coverage without it lied
    once, in the way that matters: *zobrazit* arrived from the corpus as a bare
    infinitive, counted as covered, and the hero sentence starting *Zobraz* had
    no analysis at all. A verb the runtime can only recognise in the dictionary
    is a verb no user will ever type.
    """
    missing: list[str] = []
    thin: list[str] = []
    forms: dict[str, int] = {}
    for analyses in state.exact.values():
        for analysis in analyses:
            forms[analysis.lemma] = forms.get(analysis.lemma, 0) + 1

    for lemma in lemmas:
        hit = state.lookup(lemma)
        mine = [a for a in (hit.analyses if hit else ()) if a.lemma == lemma]
        if not mine:
            missing.append(lemma)
            continue
        if forms.get(lemma, 0) <= 1 and any(a.upos in INFLECTING for a in mine):
            thin.append(lemma)
    return len(lemmas) - len(missing), missing, thin


def build_report(
    state: MorphState,
    *,
    cac: Path | None = None,
    cases_dir: Path | None = None,
    side: str = "test",
) -> Report:
    stats = state.stats()
    report = Report(
        version=stats.version,
        content_hash=stats.content_hash,
        rows=stats.rows,
        forms=stats.forms,
        layers=stats.layers,
        fold_collisions=stats.fold_collisions,
    )

    lemmas = target_lemmas()
    report.targets_total = len(lemmas)
    covered, missing, thin = target_coverage(state, lemmas)
    report.targets_covered = covered
    report.targets_missing = missing
    report.targets_thin = thin

    report.cases = run_cases(load_cases(cases_dir), state)

    if cac is not None:
        files = sorted(Path(cac).glob("*.conllu"))
        if not files:
            raise ValueError(f"no .conllu files in {cac}")
        tokens, seen = gold_tokens(files, side=side)
        report.metrics = score(tokens, state)
        report.corpus = f"UD_Czech-CAC ({side} side of the frozen split)"
        report.sentences = seen
    return report


# ── the gate ─────────────────────────────────────────────────────────────────


def read_baseline(path: Path | None = None) -> dict:
    target = Path(path or BASELINE_PATH)
    if not target.exists():
        raise FileNotFoundError(
            f"{target} does not exist — the gate has no reference to compare "
            "against. Run `ttr-morph eval --cac <dir> --write-baseline` once, "
            "read the numbers, and commit them"
        )
    return json.loads(target.read_text(encoding="utf-8"))


def check(report: Report, baseline: dict, *, tolerance: float = TOLERANCE) -> list[str]:
    """Everything wrong with this artifact relative to the reference.

    Returns a list of failures rather than a verdict: a gate that says "failed"
    and makes the reader re-run it with more flags to find out why is a gate
    people learn to skip.
    """
    failures: list[str] = []

    for result in report.cases:
        for failure in result.failures:
            failures.append(f"case {result.case.case}: {failure}")

    artifact = baseline.get("artifact") or {}
    for key in ("rows", "forms"):
        was = artifact.get(key)
        now = getattr(report, key)
        if was is not None and now < was:
            failures.append(
                f"the artifact has {now} {key} and the baseline records {was} — "
                "a snapshot may grow, and a shrinking one is a layer that "
                "stopped compiling rather than an improvement"
            )

    targets = baseline.get("targets") or {}
    was_thin = targets.get("thin")
    if was_thin is not None and len(report.targets_thin) > len(was_thin):
        failures.append(
            "the target vocabulary grew a THIN entry (covered, inflecting, one "
            f"form): {sorted(set(report.targets_thin) - set(was_thin))}. See "
            "`target_coverage` — this is the check that would have caught "
            "`zobrazit` before an acceptance case did"
        )
    was_covered = targets.get("covered")
    if was_covered is not None and report.targets_covered < was_covered:
        failures.append(
            f"target-vocabulary coverage fell to {report.targets_covered}/"
            f"{report.targets_total} from {was_covered}: "
            f"{sorted(set(report.targets_missing) - set(targets.get('missing') or []))}"
        )

    if report.metrics is not None:
        reference = baseline.get("corpus") or {}
        for key in (
            "coverage",
            "lemma_in_set",
            "head_of_list",
        ):
            was = reference.get(key)
            now = getattr(report.metrics.total, key)
            if was is not None and now < was - tolerance:
                failures.append(
                    f"{key} fell to {now:.4f} from a baseline of {was:.4f} "
                    f"(tolerance {tolerance})"
                )
    return failures


# ── rendering ────────────────────────────────────────────────────────────────


def baseline_from(report: Report) -> dict:
    """The gate reference, as committed. A strict subset of the report."""
    payload = report.as_dict()
    baseline = {
        "note": (
            "The gate reference (NLS-P8.4 T5). Produced by "
            "`ttr-morph eval --snapshot <snap> --cac <dir> --write-baseline` "
            "with the corpus in hand, and committed. `--gate` in CI compares "
            "the artifact and the named cases against this; the corpus numbers "
            "are only re-checked by a run that has the corpus."
        ),
        "artifact": {
            key: payload["artifact"][key]
            for key in ("version", "rows", "forms", "fold_collisions")
        },
        "targets": {
            "total": payload["targets"]["total"],
            "covered": payload["targets"]["covered"],
            "missing": payload["targets"]["missing"],
            "thin": payload["targets"]["thin"],
        },
        "cases": {"total": payload["cases"]["total"]},
    }
    if "corpus" in payload:
        baseline["corpus"] = {
            key: payload["corpus"][key]
            for key in (
                "source",
                "tokens",
                "coverage",
                "lemma_in_set",
                "head_of_list",
                "fold_collision_rate",
            )
        }
    return baseline


_HEADROOM = (
    "**The coverage gap is the Wave C headroom, not a defect.** This run uses "
    "the lexicon leg alone: the chain's statistical seam "
    "(`ttrnlp.morph.chain`) exists, defaults to `None`, and nothing in v1 "
    "passes one. Every uncovered token below is a token the Wave C lemmatizer "
    "will be asked to answer — trained on the TRAIN side of this same frozen "
    "manifest, and scored on the same test side this report was produced from."
)


def render(report: Report) -> str:
    lines = [
        "# LM eval report",
        "",
        "> GENERATED by `ttr-morph eval` — do not hand-edit.",
        "",
        f"Artifact `{report.version}`, content hash `{report.content_hash}`.",
        "",
        "| | |",
        "|---|--:|",
        f"| rows | {report.rows} |",
        f"| distinct forms | {report.forms} |",
        f"| fold collisions | {report.fold_collisions} |",
        f"| layers | {', '.join(f'{k} ({v})' for k, v in report.layers)} |",
        "",
        "## Named acceptance cases (S-7 → arc gate 7)",
        "",
        f"**{report.cases_passed}/{len(report.cases)} pass.**",
        "",
    ]
    for result in report.cases:
        mark = "✓" if result.passed else "✗"
        lines += [f"### {mark} `{result.case.case}` — {result.case.title}", ""]
        if result.case.note:
            lines += [result.case.note, ""]
        lines += ["| form | lemma | matched_via | provenance |", "|---|---|---|---|"]
        lines += [
            f"| {form} | {lemma or '—'} | {via or '—'} | {provenance or '—'} |"
            for form, lemma, via, provenance in result.observed
        ]
        lines.append("")
        if result.failures:
            lines += [f"- ⛔ {failure}" for failure in result.failures] + [""]

    lines += [
        "## Target-vocabulary coverage",
        "",
        f"**{report.targets_covered} of {report.targets_total}** "
        f"({report.target_coverage:.0%}) target lemmas resolve to themselves.",
        "",
    ]
    if report.targets_missing:
        lines += ["Missing: " + ", ".join(f"`{m}`" for m in report.targets_missing), ""]
    if report.targets_thin:
        lines += [
            "**Thin** — covered, inflecting, and held as a single form, which is "
            "a word the runtime cannot recognise in a sentence: "
            + ", ".join(f"`{t}`" for t in report.targets_thin),
            "",
        ]

    if report.metrics is None:
        lines += [
            "## Corpus metrics",
            "",
            "Not run — this invocation had no `--cac`. The committed numbers in "
            "`baseline.json` are the reference; see the module note in "
            "`harness.py` on why the release gate does not read the oracle.",
            "",
        ]
        return "\n".join(lines) + "\n"

    total = report.metrics.total
    lines += [
        "## Corpus metrics (contracts §11)",
        "",
        f"{report.corpus} — {report.sentences} sentences, {total.tokens} tokens.",
        "",
        _HEADROOM,
        "",
        "| metric | value | denominator |",
        "|---|--:|---|",
        f"| coverage | {total.coverage:.1%} | every token |",
        f"| lemma-in-set accuracy | {total.lemma_in_set:.1%} | answered tokens |",
        f"| head-of-list accuracy | {total.head_of_list:.1%} | answered tokens |",
        f"| fold-collision rate | {total.fold_collision_rate:.1%} | answered tokens |",
        "",
        f"Over *every* token instead: lemma-in-set {total.lemma_in_set_all:.1%}, "
        f"head-of-list {total.head_of_list_all:.1%}.",
        "",
        f"{total.by_convention} tokens ({_pct(total.by_convention, total.answered)}) "
        "scored through a declared UD lemma convention — the personal-pronoun "
        "and possessive classes, where CAC lemmatizes to `já`/`jeho` and we "
        "keep the citation form (see `metrics.py`).",
        "",
        "### By part of speech",
        "",
        "| upos | tokens | coverage | lemma-in-set | head-of-list |",
        "|---|--:|--:|--:|--:|",
    ]
    lines += [
        f"| {upos} | {counts.tokens} | {counts.coverage:.1%} | "
        f"{counts.lemma_in_set:.1%} | {counts.head_of_list:.1%} |"
        for upos, counts in sorted(
            report.metrics.per_upos.items(), key=lambda pair: -pair[1].tokens
        )
    ]
    lines += [
        "",
        "### The head-of-list misses worth a minute",
        "",
        "| form | CAC | our head | tokens |",
        "|---|---|---|--:|",
    ]
    lines += [
        f"| {form} | {gold} | {ours or '—'} | {count} |"
        for form, gold, ours, count in report.metrics.head_misses
    ]
    lines += [
        "",
        "### The commonest uncovered forms",
        "",
        "This table is the Wave C brief.",
        "",
        "| form | CAC lemma | tokens |",
        "|---|---|--:|",
    ]
    lines += [
        f"| {form} | {lemma} | {count} |"
        for form, lemma, count in report.metrics.uncovered
    ]
    return "\n".join(lines) + "\n"


def _pct(part: int, whole: int) -> str:
    return f"{part / whole:.1%}" if whole else "—"


def load_state(paths: Sequence[str | Path]) -> MorphState:
    return load_morph([str(path) for path in paths])


__all__ = [
    "BASELINE_PATH",
    "EVAL_DIR",
    "REPORT_PATH",
    "SIDES",
    "TOLERANCE",
    "Report",
    "baseline_from",
    "build_report",
    "check",
    "gold_tokens",
    "load_state",
    "read_baseline",
    "render",
    "target_coverage",
    "target_lemmas",
]
