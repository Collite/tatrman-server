# SPDX-License-Identifier: Apache-2.0
"""NLS-P7.1 T1/T4/T5 — the tokenizer's golden matrix, and its floor.

**The golden pairs are the specification.** Each case in ``golden/`` is an input
line (``<case>.txt``) and the exact token list it must produce
(``<case>.expected.json``: ``text``, ``start``, ``end``, ``kind``). They were
authored from LM-9's closed hard-case list — decimal comma with grouped
thousands, date ordinals, abbreviations, hyphenation, codes/SKUs, a plain
sentence, and the hero sentence verbatim — and if a case here disagrees with the
tokenizer, the tokenizer is wrong.

**Regenerating.** ``uv run pytest tests/morph/test_tokenize.py --update-golden``
rewrites every ``*.expected.json`` from the current output and fails the run, so
a regeneration can never be mistaken for a passing test. Read the diff: an
expectation that changed without a deliberate profile change is a regression
that just rewrote its own evidence. (Same pattern as the P1 conformance runner:
the matrix is checked in, not computed.)

The invariants below (T5) are what the golden files cannot express: they hold
for *every* input, including ones no one thought to write down.
"""

from __future__ import annotations

import json
import random
import sys
import unicodedata
from pathlib import Path

import pytest

from ttrnlp.morph.profiles import UnknownProfile
from ttrnlp.morph.tokenize import Token, tokenize

GOLDEN = Path(__file__).parent / "golden"


def _cases() -> list[str]:
    return sorted(p.stem for p in GOLDEN.glob("*.txt"))


def _read_input(case: str) -> str:
    # The trailing newline is the file's, not the case's — one `rstrip("\n")`,
    # so a case that deliberately ends in whitespace keeps it.
    return (GOLDEN / f"{case}.txt").read_text(encoding="utf-8").rstrip("\n")


def _as_dicts(tokens: list[Token]) -> list[dict]:
    return [
        {"text": t.text, "start": t.start, "end": t.end, "kind": t.kind} for t in tokens
    ]


@pytest.mark.parametrize("case", _cases())
def test_golden(case: str, update_golden: bool):
    text = _read_input(case)
    actual = _as_dicts(tokenize(text))
    expected_path = GOLDEN / f"{case}.expected.json"

    if update_golden:
        expected_path.write_text(
            json.dumps(actual, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        pytest.fail(f"--update-golden rewrote {expected_path.name}; review the diff")

    expected = json.loads(expected_path.read_text(encoding="utf-8"))
    assert actual == expected


@pytest.mark.parametrize("case", _cases())
def test_every_golden_token_is_concatenation_safe(case: str):
    """The offsets are the contract, not the text.

    Everything downstream — the annotator, the gazetteer, the rule engine, the
    wire payload — slices the document by these offsets. A token whose ``text``
    is not what its span covers would make each of them disagree with the next
    in a different way.
    """
    text = _read_input(case)
    tokens = tokenize(text)
    for token in tokens:
        assert text[token.start : token.end] == token.text
        assert 0 <= token.start < token.end <= len(text)
    starts = [t.start for t in tokens]
    assert starts == sorted(starts)
    for earlier, later in zip(tokens, tokens[1:], strict=False):
        assert earlier.end <= later.start, "tokens must not overlap"


# ── the hard cases, said out loud ────────────────────────────────────────────
#
# The golden files already pin these. Stating the interesting ones here as well
# is not duplication: a golden file records *what* the answer is, and these
# record *why anyone cared* — which is the part a future reader needs when a
# case starts failing.


def test_a_grouped_decimal_is_one_number_token():
    tokens = tokenize("1 234,50 Kč")
    assert [(t.text, t.kind) for t in tokens] == [
        ("1 234,50", "number"),
        ("Kč", "word"),
    ]


def test_a_date_keeps_its_ordinal_periods():
    assert [t.text for t in tokenize("31. 12. 2025")] == ["31.", "12.", "2025"]


def test_a_sentence_final_period_is_not_an_ordinal():
    tokens = tokenize("Tržby vzrostly v roce 2025.")
    assert tokens[-2].text == "2025"
    assert tokens[-1].text == "." and tokens[-1].kind == "punct"


def test_an_abbreviation_keeps_its_period():
    assert [(t.text, t.kind) for t in tokenize("č. tis. např.")] == [
        ("č.", "abbrev"),
        ("tis.", "abbrev"),
        ("např.", "abbrev"),
    ]


def test_a_word_that_is_not_an_abbreviation_loses_the_period():
    assert [(t.text, t.kind) for t in tokenize("rok.")] == [
        ("rok", "word"),
        (".", "punct"),
    ]


@pytest.mark.parametrize("compound", ["e-shop", "Praha-východ", "česko-slovenský"])
def test_a_hyphenated_compound_is_one_word(compound: str):
    assert [(t.text, t.kind) for t in tokenize(compound)] == [(compound, "word")]


@pytest.mark.parametrize("code", ["AB-123/X", "INV-2026/0042", "123-ABC"])
def test_a_code_is_one_token(code: str):
    """Including the one that starts with digits.

    ``123-ABC`` is why the code rule runs before the number rule: read
    number-first it is *123*, a hyphen and *ABC*, and the list holding the SKU
    would never match it.
    """
    assert [(t.text, t.kind) for t in tokenize(code)] == [(code, "code")]


def test_the_hero_sentence():
    text = "Porovnej tržby Kauflandu za loňský rok s letošním"
    assert [t.text for t in tokenize(text)] == text.split()
    assert {t.kind for t in tokenize(text)} == {"word"}


# ── T5: determinism and the fuzz floor ───────────────────────────────────────


def test_the_same_input_twice_gives_identical_tokens():
    text = "Faktura č. 42 na 1 234,50 Kč (AB-123/X) z 31. 12. 2025."
    assert tokenize(text) == tokenize(text)


def test_empty_input_gives_no_tokens():
    assert tokenize("") == []
    assert tokenize("   \n\t ") == []


def test_whitespace_only_between_tokens_is_dropped():
    tokens = tokenize("  a\t\nb  ")
    assert [(t.text, t.start, t.end) for t in tokens] == [("a", 2, 3), ("b", 5, 6)]


def _random_text(rng: random.Random) -> str:
    # Every plane the BMP offers, plus the characters the cs profile argues
    # about, so the fuzz is not just Latin noise.
    alphabet = " \t\n.,;:!?-/()%€$" + "0123456789" + "abcžščřýáíé" + "ABCŽŠČŘÝÁÍÉ"
    chars = [rng.choice(alphabet) for _ in range(rng.randint(0, 40))]
    chars += [chr(rng.randrange(0x20, 0x2FFF)) for _ in range(rng.randint(0, 20))]
    rng.shuffle(chars)
    return "".join(chars)


@pytest.mark.parametrize("seed", range(200))
def test_fuzz_never_crashes_and_stays_concatenation_safe(seed: int):
    """Seeded, so a failure is reproducible by its parameter id alone."""
    text = _random_text(random.Random(seed))
    tokens = tokenize(text)
    covered = 0
    for token in tokens:
        assert text[token.start : token.end] == token.text
        assert token.start >= covered
        covered = token.end
    # Nothing but whitespace may be dropped: every non-space character belongs
    # to exactly one token (the `sym` fallback is what guarantees it).
    kept = "".join(text[t.start : t.end] for t in tokens)
    assert "".join(kept.split()) == "".join(text.split())


def test_fuzz_is_deterministic():
    text = _random_text(random.Random(7))
    assert tokenize(text) == tokenize(text)


def test_combining_marks_do_not_split_a_word():
    """NFD input is still one word.

    Text arrives from wherever the caller got it, and macOS filesystems and some
    web forms hand over decomposed Czech. ``\\u030c`` (combining caron) is not a
    word character to ``\\w``, so if the profile ever loses its grip on this the
    symptom is *tržby* tokenizing as three tokens on one machine and one on
    another — the exact class of bug the one-substrate rule exists to prevent.
    """
    composed = "tržby"
    decomposed = unicodedata.normalize("NFD", composed)
    assert decomposed != composed
    assert [t.text for t in tokenize(decomposed)] == [decomposed]


def test_an_unknown_profile_is_refused_by_name():
    with pytest.raises(UnknownProfile) as excinfo:
        tokenize("ahoj", profile="sk")
    assert "cs" in str(excinfo.value)


def test_recursion_is_not_involved():
    """A long line is a loop, not a stack.

    Documents arrive as whole paragraphs; a recursive scanner would die on one
    and only in production.
    """
    text = "faktura č. 1, " * 5000
    before = sys.getrecursionlimit()
    tokens = tokenize(text)
    assert sys.getrecursionlimit() == before
    assert len(tokens) == 20000
