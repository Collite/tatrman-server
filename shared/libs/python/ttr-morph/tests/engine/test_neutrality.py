# SPDX-License-Identifier: Apache-2.0
"""NLS-P8.1 T7 — the driver has no language in it (LM-2).

The claim the whole design rests on is that a second language is a second data
file. That claim is only true while it is enforced: the first time a rule about
Czech is easier to write in the driver than in the tables, the tables stop
being the description of the language and nobody notices until sk arrives.

So this is a grep, made a test. It runs on every file under `engine/`,
docstrings and comments included — a comment that has to spell a Czech word to
explain the code is a sign the code knows about Czech.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

ENGINE = Path(__file__).resolve().parents[2] / "src" / "ttrmorph" / "engine"
SOURCES = sorted(ENGINE.glob("*.py"))

#: Every letter Czech has and the Latin alphabet does not, in both cases. A
#: superset of the arc's own grep (`áéíóúůýžščřďťň`) — the extra letters cost
#: nothing and the two that are missing from it are the ones an author reaches
#: for without thinking.
CZECH_LETTERS = re.compile(r"[áčďéěíňóřšťúůýžÁČĎÉĚÍŇÓŘŠŤÚŮÝŽ]")


def test_there_are_sources_to_check():
    assert len(SOURCES) >= 4


@pytest.mark.parametrize("path", SOURCES, ids=lambda p: p.name)
def test_no_czech_letter_appears_in_the_driver(path: Path):
    text = path.read_text(encoding="utf-8")
    offenders = [
        f"{path.name}:{n}: {line.strip()}"
        for n, line in enumerate(text.splitlines(), start=1)
        if CZECH_LETTERS.search(line)
    ]
    assert not offenders, "language leaked into the driver:\n" + "\n".join(offenders)


def test_the_tables_are_where_the_language_lives():
    """The other half of the same claim.

    A driver with no Czech in it proves nothing if the tables are empty.
    """
    tables = Path(__file__).resolve().parents[2] / "src" / "ttrmorph" / "seed"
    data = tables / "data" / "cs" / "vzory.yaml"
    assert CZECH_LETTERS.search(data.read_text(encoding="utf-8"))
