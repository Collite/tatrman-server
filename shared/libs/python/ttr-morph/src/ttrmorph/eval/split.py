# SPDX-License-Identifier: Apache-2.0
"""The frozen CAC split (LM-16 / S-6 / contracts §11) — read §11 twice.

UD_Czech-CAC is the **sole** eval oracle, and it is the only corpus this effort
and the post-v1 Wave C training task share. That sharing is the whole point and
the whole danger: if seeding reads sentences that a later model is evaluated on,
the evaluation measures nothing, and the failure is invisible — the numbers look
fine, they are just about a corpus the model has already seen.

So the partition happens **once**, is written to
``shared/libs/python/ttr-morph/eval/cac-split.json``, and is law::

    ttr-morph split --cac <dir> --seed 20260811 -o eval/cac-split.json

committed alone, in a commit that says so, **before any CAC-derived read**.
Wave C's LM-6 task must train on the train side of *this* manifest and evaluate
on its test side. No re-split, ever — not for a new UD release, not for a bigger
corpus, not because a number would look better.

**The manifest is the guard, not a convention.** `load_manifest` is what the CAC
reader calls, and the reader refuses sentence ids outside the side it was asked
for (`importers/cac.py`). The check lives in the reader rather than in its
callers on purpose: a caller can be written wrong once per caller, and a reader
can only be written wrong once.

**Why our own partition when UD ships train/dev/test files.** The UD files are a
split for *parsing* benchmarks, drawn over documents whose composition we do not
control and which changes between releases. A frozen manifest of sentence ids is
reproducible from nothing but this file — and it pins the release it was drawn
from by sha256, so "the corpus changed under us" is detectable rather than
assumed away.

⚑ Note the two ``eval`` directories, and they are different things: this module
is `ttrmorph.eval` (code, inside the package) and the manifest lives in
``<package root>/eval/`` (data, outside it, where contracts §11 puts it — it is
committed evidence, not something an installed wheel carries).
"""

from __future__ import annotations

import hashlib
import json
import random
from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from pathlib import Path

#: The seed contracts §11 names. Not a parameter with a default anywhere else:
#: a second seed in a second place is how a re-split happens by accident.
FROZEN_SEED = 20260811

#: 80/10/10 (contracts §11).
RATIOS = (0.8, 0.1, 0.1)

SIDES = ("train", "dev", "test")

#: ``<package root>/eval/cac-split.json`` — see the module note on the two
#: ``eval`` directories.
MANIFEST_PATH = Path(__file__).resolve().parents[3] / "eval" / "cac-split.json"

#: The corpus this manifest may describe. A second corpus would be a second
#: manifest and a second decision, not a parameter.
CORPUS = "UD_Czech-CAC"


class SplitError(RuntimeError):
    """The manifest is missing, malformed, or does not describe this corpus."""


@dataclass(frozen=True)
class SplitManifest:
    """The frozen partition, as committed."""

    corpus: str
    release: str
    #: sha256 of the source archive the ids were drawn from.
    sha256: str
    seed: int
    train: tuple[str, ...]
    dev: tuple[str, ...]
    test: tuple[str, ...]

    def side(self, name: str) -> frozenset[str]:
        if name not in SIDES:
            raise SplitError(f"unknown side {name!r} — contracts §11 has {SIDES}")
        return frozenset(getattr(self, name))

    @property
    def counts(self) -> dict[str, int]:
        return {name: len(getattr(self, name)) for name in SIDES}


def partition(ids: Iterable[str], *, seed: int = FROZEN_SEED) -> dict[str, list[str]]:
    """Split sentence ids 80/10/10, deterministically.

    The ids are sorted before the shuffle, so the result depends on the *set* of
    ids and the seed — never on the order the reader happened to walk the files
    in. Each side comes back sorted for the same reason: a manifest whose ids
    were in shuffle order would produce a different file, and a different sha256,
    from identical input.
    """
    ordered = sorted(set(ids))
    shuffled = list(ordered)
    random.Random(seed).shuffle(shuffled)

    total = len(shuffled)
    train_end = round(total * RATIOS[0])
    dev_end = train_end + round(total * RATIOS[1])
    return {
        "train": sorted(shuffled[:train_end]),
        "dev": sorted(shuffled[train_end:dev_end]),
        "test": sorted(shuffled[dev_end:]),
    }


def sentence_ids(conllu_files: Sequence[Path]) -> list[str]:
    """Every ``# sent_id = …`` in these files, in file order.

    A minimal reader rather than the `conllu` package: this runs *before* the
    split exists, which is the one moment nothing may parse a CAC sentence.
    Reading only the id comments keeps that honest — there is no code path here
    that could return a token.
    """
    ids: list[str] = []
    for path in sorted(conllu_files):
        with path.open(encoding="utf-8") as handle:
            for line in handle:
                if line.startswith("# sent_id"):
                    _, _, value = line.partition("=")
                    ids.append(value.strip())
    return ids


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def render(manifest: SplitManifest) -> str:
    """The manifest's bytes. Stable: sorted keys, two-space indent, one \\n."""
    return (
        json.dumps(
            {
                "corpus": manifest.corpus,
                "release": manifest.release,
                "sha256": manifest.sha256,
                "seed": manifest.seed,
                "counts": manifest.counts,
                "train": list(manifest.train),
                "dev": list(manifest.dev),
                "test": list(manifest.test),
            },
            indent=2,
            sort_keys=True,
            ensure_ascii=False,
        )
        + "\n"
    )


def build(
    conllu_files: Sequence[Path],
    *,
    release: str,
    archive: Path,
    seed: int = FROZEN_SEED,
) -> SplitManifest:
    ids = sentence_ids(conllu_files)
    if not ids:
        raise SplitError(f"no sentence ids found in {[str(p) for p in conllu_files]}")
    sides = partition(ids, seed=seed)
    return SplitManifest(
        corpus=CORPUS,
        release=release,
        sha256=sha256_of(archive),
        seed=seed,
        train=tuple(sides["train"]),
        dev=tuple(sides["dev"]),
        test=tuple(sides["test"]),
    )


def load_manifest(path: Path | str | None = None) -> SplitManifest:
    """Read the frozen manifest, or say plainly why nothing may be read.

    Raises:
        SplitError: The manifest does not exist — which means the ceremony has
            not happened, and reading CAC now would be reading a corpus that
            has not been partitioned yet. That is the one failure this whole
            module exists to make loud.
    """
    target = Path(path) if path else MANIFEST_PATH
    if not target.exists():
        raise SplitError(
            f"the frozen CAC split manifest is not at {target} — no CAC-derived "
            "read may happen before it exists (LM-16/S-6, contracts §11). Run "
            "`ttr-morph split --cac <dir> --seed 20260811` and commit the "
            "manifest ALONE, then read the corpus"
        )
    try:
        raw = json.loads(target.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise SplitError(f"{target} is not valid JSON: {exc}") from exc

    required = ("corpus", "release", "sha256", "seed", *SIDES)
    missing = [key for key in required if key not in raw]
    if missing:
        raise SplitError(f"{target} is missing {missing}")
    if raw["corpus"] != CORPUS:
        raise SplitError(
            f"{target} describes {raw['corpus']!r}, and the sole eval oracle is "
            f"{CORPUS} (contracts §11)"
        )
    if raw["seed"] != FROZEN_SEED:
        raise SplitError(
            f"{target} was drawn with seed {raw['seed']} and contracts §11 "
            f"freezes {FROZEN_SEED} — this manifest is not the shared split"
        )
    return SplitManifest(
        corpus=raw["corpus"],
        release=raw["release"],
        sha256=raw["sha256"],
        seed=raw["seed"],
        train=tuple(raw["train"]),
        dev=tuple(raw["dev"]),
        test=tuple(raw["test"]),
    )


__all__ = [
    "CORPUS",
    "FROZEN_SEED",
    "MANIFEST_PATH",
    "RATIOS",
    "SIDES",
    "SplitError",
    "SplitManifest",
    "build",
    "load_manifest",
    "partition",
    "render",
    "sentence_ids",
    "sha256_of",
]
