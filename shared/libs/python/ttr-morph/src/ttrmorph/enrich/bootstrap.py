# SPDX-License-Identifier: Apache-2.0
"""The bulk enrichment batch (NLS-P9.3 T6).

The cascade, over a target list instead of over the queue. Same code, same
rules, same auto-validation — `run_cascade` is a pure function precisely so that
this can drive it a few thousand times without an HTTP request, a database or a
running service anywhere in the picture.

**What it is for.** After P8 the artifact covers most of the target vocabulary
and misses a measured remainder (`eval.harness.target_coverage`). Working that
remainder one word at a time through the UI is the wrong tool: the words are
known, the patterns are mostly guessable, and what a person should be doing is
*reviewing* proposals rather than producing them. So this produces them, and the
review is one pass over a report.

**⚑ The batch runs the cascade; the studio does not run it again.** The obvious
alternative — POST the tokens and let each studio work them out — would mean the
report Bora reads and the queue he then acts on came from two different runs, and
the LLM leg is not a pure function. It would also pay for every classification
twice. So the results travel *with* the ingest rows, and the studio stores what
it is given. That is also what makes the review block possible at all: the batch
runs, a person reads the report, and only then does anything reach a queue.

**Nothing here auto-verifies.** The output is queue items at `proposed` or
`auto-validated`, and `auto-validated` is not past the export gate — it is a
proposal that generated its own evidence. The `verified` that follows is a human
act, which is the whole shape of LM-14.
"""

from __future__ import annotations

import json
from collections import Counter
from collections.abc import Iterable, Iterator, Sequence
from dataclasses import dataclass, field
from pathlib import Path

from ttrmorph.enrich.cascade import (
    LAYER_CORE,
    LLM_UNAVAILABLE,
    STATUS_AUTO_VALIDATED,
    STATUS_PROPOSED,
    CascadeResult,
    run_cascade,
)
from ttrmorph.enrich.llm import LlmLeg

#: A line of a plain word list this ignores.
COMMENT = "#"

#: The report's sample size per bucket. Enough to judge a tier by, short enough
#: that the whole document is read rather than skimmed.
SAMPLES = 12


@dataclass
class Row:
    """One target, and what the cascade concluded about it."""

    token: str
    result: CascadeResult
    #: Where the target came from — the list's name, for the report's breakdown.
    source: str = ""

    def as_report(self, world: str) -> dict:
        """The `POST /v1/ingest` row, carrying the cascade with it.

        `verdict: "bootstrap"` rather than `"miss"`: nobody's query missed on
        this word. It is a coverage gap somebody went looking for, and a queue
        that could not tell the two apart would lose the only signal saying
        which words users actually type.
        """
        return {
            "world": world,
            "token": self.token,
            "verdict": "bootstrap",
            "count": 0,
            "cascade": {
                "proposals": [p.as_dict() for p in self.result.proposals],
                "tier": self.result.tier,
                "agreed": self.result.agreed,
                "notes": list(self.result.notes),
                "status": self.result.status,
                "layer": self.result.layer,
            },
        }


@dataclass
class Batch:
    """A whole run — the rows, and what went wrong along the way."""

    world: str = LAYER_CORE
    rows: list[Row] = field(default_factory=list)
    #: Targets skipped because the artifact already covers them.
    covered: list[str] = field(default_factory=list)
    #: Targets the LLM leg failed on. Not fatal: a batch that died on the first
    #: gateway hiccup would waste every classification before it.
    failed: list[tuple[str, str]] = field(default_factory=list)
    sources: dict[str, int] = field(default_factory=dict)

    @property
    def auto_validated(self) -> list[Row]:
        return [row for row in self.rows if row.result.status == STATUS_AUTO_VALIDATED]

    @property
    def proposed(self) -> list[Row]:
        return [row for row in self.rows if row.result.status == STATUS_PROPOSED]

    @property
    def empty(self) -> list[Row]:
        """Rows the cascade had nothing to say about — a person must author these."""
        return [row for row in self.rows if not row.result.proposals]

    def by_tier(self) -> dict[str, int]:
        return dict(Counter(row.result.tier for row in self.rows).most_common())

    def by_layer(self) -> dict[str, int]:
        return dict(Counter(row.result.layer for row in self.rows).most_common())

    def by_vzor(self) -> dict[str, int]:
        counts = Counter(
            row.result.best.vzor for row in self.rows if row.result.best is not None
        )
        return dict(counts.most_common())


# ── the targets ──────────────────────────────────────────────────────────────


def read_targets(path: Path, *, lang: str = "cs") -> list[str]:
    """Every word in one list, in order, deduplicated.

    Three formats, because the target vocabulary genuinely arrives as all three:
    the grouped `target-words.yaml` the artifact is measured against, a
    `ttr-lexicon` glossary as a world exports one, and plain one-word-per-line
    lists for everything else.

    ⚑ **Multi-word terms are dropped, not split.** A glossary holds
    *"druhé pololetí"* and a catalogue holds *"Farrow Běžecká bota MX-MRS"*; the
    cascade takes one lemma at a time, and splitting those would ask the guesser
    to find a paradigm for `MX-MRS`. A phrase belongs to the gazetteer lane, not
    to the lexicon.
    """
    text = path.read_text(encoding="utf-8")
    if path.suffix in (".yaml", ".yml"):
        return _from_yaml(text, path, lang=lang)
    words: dict[str, None] = {}
    for line in text.splitlines():
        word = line.split(COMMENT, 1)[0].strip()
        if word and _single(word):
            words.setdefault(word, None)
    return list(words)


def _single(word: str) -> bool:
    return bool(word) and not any(char.isspace() for char in word)


def _from_yaml(text: str, path: Path, *, lang: str = "cs") -> list[str]:
    import yaml

    raw = yaml.safe_load(text) or {}
    words: dict[str, None] = {}

    def keep(word: object) -> None:
        text = str(word).strip()
        if _single(text):
            words.setdefault(text, None)

    groups = raw.get("groups") if isinstance(raw, dict) else None
    entries = raw.get("entries") if isinstance(raw, dict) else None

    if isinstance(groups, dict):
        for words_in_group in groups.values():
            for word in words_in_group or []:
                keep(word)
    elif isinstance(entries, list):
        # A `ttr-lexicon` glossary. Terms carry their own language and a
        # per-file default; a world's cs glossary routinely holds en terms too,
        # and running the Czech engine over them would propose Czech paradigms
        # for English words with nothing to say they are wrong.
        default = ((raw.get("defaults") or {}) if isinstance(raw, dict) else {}).get(
            "lang", lang
        )
        for entry in entries:
            for term in (entry or {}).get("terms") or []:
                if not isinstance(term, dict):
                    keep(term)
                    continue
                languages = str(term.get("lang", default)).split("|")
                if lang in languages:
                    keep(term.get("text", ""))
    elif isinstance(raw, list):
        for word in raw:
            keep(word)
    else:
        raise ValueError(
            f"{path}: expected a `groups:` mapping (a target list), an "
            "`entries:` list (a ttr-lexicon glossary), or a plain list of words"
        )
    return list(words)


def uncovered(targets: Sequence[str], state=None) -> tuple[list[str], list[str]]:
    """Split targets into (to work, already covered) against a snapshot.

    ⚑ **Thin counts as uncovered.** A lemma the artifact holds exactly one form
    of — *zobrazit* as a bare infinitive — is covered by the letter of the
    measurement and useless in practice, because no user types the citation
    form. P8.4 learned that the hard way; repeating the mistake here would send
    the batch past exactly the words most worth its attention.
    """
    if state is None:
        return list(targets), []
    from ttrmorph.eval.harness import target_coverage

    _, missing, thin = target_coverage(state, list(targets))
    wanted = set(missing) | set(thin)
    return [t for t in targets if t in wanted], [t for t in targets if t not in wanted]


# ── the run ──────────────────────────────────────────────────────────────────


def run(
    targets: Iterable[tuple[str, str]],
    *,
    world: str = LAYER_CORE,
    llm: LlmLeg | None = None,
    vocabulary: Sequence[str] = (),
    lang: str = "cs",
    progress=None,
) -> Batch:
    """Drive the cascade over `(word, source)` pairs.

    Args:
        targets: The words to work, each with the list it came from.
        world: The world these queue items belong to (LM-5).
        llm: The classifier leg, or None — guesser-only is a supported run.
        vocabulary: The world's model vocabulary, for LM-10 routing.
        progress: Called with `(done, word)` so a long run says something.
    """
    batch = Batch(world=world)
    for index, (word, source) in enumerate(targets, start=1):
        batch.sources[source] = batch.sources.get(source, 0) + 1
        result = run_cascade(word, llm=llm, vocabulary=vocabulary, lang=lang)
        # ⚑ The cascade never raises for a leg's failure — it records a NOTE and
        # falls through to the human tier, so that one unreachable gateway
        # cannot stop an ingest. Which means a `try/except LlmError` here would
        # be dead code, and a batch run against a gateway that was down for an
        # hour would report a clean sheet. The notes are the signal.
        for note in result.notes:
            if note.startswith(LLM_UNAVAILABLE):
                batch.failed.append((word, note[len(LLM_UNAVAILABLE) :].strip()))
        batch.rows.append(Row(token=word, result=result, source=source))
        if progress is not None:
            progress(index, word)
    return batch


def write_rows(batch: Batch, path: Path) -> int:
    """The ingest rows, one JSON object per line.

    JSONL rather than one document: a bootstrap of the whole target vocabulary
    is thousands of rows, and a reviewer wanting to look at one word should be
    able to grep for it.
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        for row in batch.rows:
            handle.write(json.dumps(row.as_report(batch.world), ensure_ascii=False))
            handle.write("\n")
    return len(batch.rows)


def read_rows(path: Path) -> Iterator[dict]:
    """The rows back, for whatever posts them."""
    with path.open(encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if line:
                yield json.loads(line)


# ── the report ───────────────────────────────────────────────────────────────


def render(batch: Batch, *, samples: int = SAMPLES) -> str:
    """`bootstrap-report.md` — the document the review block is a pass over.

    Organised by *what a reviewer has to decide*, not by what the code did:
    the auto-validated rows are a spot check (the engine already agreed with
    itself), the proposed rows are the actual work, and the empty ones are words
    somebody has to author. Counts first, because the shape of the batch decides
    whether the review is an afternoon or a week.
    """
    lines: list[str] = []
    add = lines.append

    total = len(batch.rows)
    auto = batch.auto_validated
    proposed = batch.proposed
    empty = batch.empty

    add(f"# Bootstrap batch — world `{batch.world}`")
    add("")
    add(
        "The p9-2 cascade, run over the uncovered target vocabulary. Nothing "
        "here is verified: `auto-validated` means the engine generated the word "
        "from the pattern it proposed (LM-14), which is evidence, not a "
        "decision. Everything below still needs a person."
    )
    add("")
    add("## Counts")
    add("")
    add("| | |")
    add("|---|---:|")
    add(f"| targets worked | {total} |")
    add(f"| already covered, skipped | {len(batch.covered)} |")
    add(f"| **auto-validated** (spot-check these) | {len(auto)} |")
    add(f"| **proposed** (the review) | {len(proposed)} |")
    add(f"| nothing proposed (author these) | {len(empty)} |")
    if batch.failed:
        add(f"| LLM failures (fell back to the guesser) | {len(batch.failed)} |")
    add("")

    if batch.sources:
        add("### By list")
        add("")
        for name, count in sorted(batch.sources.items(), key=lambda kv: -kv[1]):
            add(f"- `{name}` — {count}")
        add("")

    add("### By tier")
    add("")
    add("The leg that reached the answer. `human` means the cascade declined.")
    add("")
    for tier, count in batch.by_tier().items():
        add(f"- **{tier}** — {count}")
    add("")

    add("### By layer (LM-10 routing)")
    add("")
    for layer, count in batch.by_layer().items():
        add(f"- **{layer}** — {count}")
    add("")

    top = list(batch.by_vzor().items())[:15]
    if top:
        add("### Patterns proposed")
        add("")
        add("A pattern taking an implausible share of the batch is the thing to")
        add("notice here — it usually means the guesser found a shape, not a word.")
        add("")
        for vzor, count in top:
            add(f"- `{vzor}` — {count}")
        add("")

    add("## Samples")
    add("")
    _samples(add, "Auto-validated", auto, samples)
    _samples(add, "Proposed", proposed, samples)
    _samples(add, "Nothing proposed", empty, samples)

    if batch.failed:
        add("## LLM failures")
        add("")
        add("These fell back to the deterministic leg alone.")
        add("")
        for word, detail in batch.failed[:samples]:
            add(f"- `{word}` — {detail}")
        if len(batch.failed) > samples:
            add(f"- …and {len(batch.failed) - samples} more")
        add("")

    add("## What happens next")
    add("")
    add(
        "1. Read this. If the shape is wrong — one pattern swallowing the batch, "
        "a tier doing more than it should — say so before anything is ingested."
    )
    add(
        "2. `POST /v1/ingest` the rows file into the world's studio. They arrive "
        "at the status this report gives them; the cascade is not run again."
    )
    add("3. Work the queue (FI-7 surface 3). `verified` is the human act.")
    add(
        "4. `POST /v1/export` → the layer files → recompile → the next `morph/v*`."
    )
    add("")
    return "\n".join(lines) + "\n"


def _samples(add, title: str, rows: Sequence[Row], limit: int) -> None:
    add(f"### {title} ({len(rows)})")
    add("")
    if not rows:
        add("_none_")
        add("")
        return
    add("| word | lemma | upos | vzor | conf | source | notes |")
    add("|---|---|---|---|---:|---|---|")
    for row in rows[:limit]:
        best = row.result.best
        if best is None:
            add(f"| `{row.token}` | — | — | — | — | {row.result.tier} | "
                f"{'; '.join(row.result.notes) or '—'} |")
        else:
            add(
                f"| `{row.token}` | {best.lemma} | {best.upos} | `{best.vzor}` | "
                f"{best.confidence:.2f} | {best.source} | "
                f"{'; '.join(row.result.notes) or '—'} |"
            )
    if len(rows) > limit:
        add("")
        add(f"_…and {len(rows) - limit} more._")
    add("")


__all__ = [
    "SAMPLES",
    "Batch",
    "Row",
    "read_rows",
    "read_targets",
    "render",
    "run",
    "uncovered",
    "write_rows",
]
