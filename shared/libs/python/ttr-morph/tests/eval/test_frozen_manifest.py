# SPDX-License-Identifier: Apache-2.0
"""The committed split manifest — the CI guard (T2, contracts §11).

⚠ **If `test_the_manifest_is_frozen` fails, do not update the literal.**

It failing means someone re-partitioned UD_Czech-CAC. That corpus is shared:
this effort seeds from its train side, and the post-v1 Wave C training task must
train on the same train side and evaluate on the same test side. A re-split
moves sentences across that line, and afterwards the evaluation measures a model
against text it was trained on — with numbers that look *better*, which is the
part that makes it dangerous rather than merely wrong.

The fix is `git checkout shared/libs/python/ttr-morph/eval/cac-split.json`.

This file was committed together with the manifest, in the ceremony commit, so
the guard has never been absent from a commit where the manifest was present.
"""

from __future__ import annotations

import hashlib

from ttrmorph.eval.split import (
    CORPUS,
    FROZEN_SEED,
    MANIFEST_PATH,
    SIDES,
    load_manifest,
)

#: sha256 of `eval/cac-split.json` as frozen by
#: `LM: freeze CAC split (shared with Wave C training — no re-split)`.
FROZEN_MANIFEST_SHA256 = (
    "50c4b98433aea6b58c863a7482a6f7e6a047226e3db2fb5b966362a13a5e6452"
)

#: The UD release the ids were drawn from, and the sha256 of its archive.
FROZEN_RELEASE = "r2.18"
FROZEN_ARCHIVE_SHA256 = (
    "06397524a12604dbeddc447fef4ed9c6298d355c2ffd148b7eb05152c8573ff0"
)

#: 24,709 sentences, 80/10/10.
FROZEN_COUNTS = {"train": 19767, "dev": 2471, "test": 2471}


def test_the_manifest_exists():
    """It is committed evidence, not a build output."""
    assert MANIFEST_PATH.exists(), (
        "the frozen CAC split manifest is missing — no CAC-derived read may "
        "happen without it (LM-16/S-6)"
    )


def test_the_manifest_is_frozen():
    """⚠ Read this module's docstring before touching the literal."""
    digest = hashlib.sha256(MANIFEST_PATH.read_bytes()).hexdigest()
    assert digest == FROZEN_MANIFEST_SHA256


def test_the_manifest_describes_the_sole_oracle():
    manifest = load_manifest()
    assert manifest.corpus == CORPUS
    assert manifest.seed == FROZEN_SEED
    assert manifest.release == FROZEN_RELEASE
    assert manifest.sha256 == FROZEN_ARCHIVE_SHA256


def test_the_counts_are_the_frozen_ones():
    assert load_manifest().counts == FROZEN_COUNTS


def test_the_sides_are_disjoint():
    manifest = load_manifest()
    train, dev, test = (manifest.side(name) for name in SIDES)
    assert not (train & dev)
    assert not (train & test)
    assert not (dev & test)


def test_every_id_appears_exactly_once():
    manifest = load_manifest()
    ids = [i for name in SIDES for i in getattr(manifest, name)]
    assert len(ids) == len(set(ids)) == sum(FROZEN_COUNTS.values())
