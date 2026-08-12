# SPDX-License-Identifier: Apache-2.0
"""The Czech tokenizer profile — data only (LM-9, LM-2).

Every decision below is a *cs* decision. They are here, in a data module, and
not in the scanner, because the scanner is the part that must also serve sk,
and a scanner that knows about Czech ordinals is a scanner that has to be
edited to learn Slovak ones.

**The period is never a decimal separator.** Czech writes decimals with a comma
(``1 234,50``) and marks ordinals with a period (``31. 12. 2025``). Letting the
period do both jobs is what makes ``31.12.2025`` unanalysable: read as decimals
it is *31.12* followed by *.2025*. So the number rule takes a comma only, and
the period always belongs either to a numeral (ordinal), to a word
(abbreviation) or to the sentence.

**Which period belongs to the numeral is a question about what follows.** A
period followed by a digit or a lowercase word continues the phrase — ``31. 12.
2025``, ``5. patra`` — so the numeral absorbs it. A period followed by an
uppercase letter or by nothing at all ends a sentence — ``…v roce 2025.`` — so
it stays a token of its own. The rule is a heuristic and it is wrong for a
sentence that ends in an ordinal; the alternative (never attach) is wrong for
every date, which is the form the query domain actually contains.

**Which period belongs to a word is a question about vocabulary**, so it is a
list. The list is deliberately short and domain-shaped: this is the analytical
and business vocabulary (FI-1/GI-1), not Czech at large.
"""

from __future__ import annotations

from ttrnlp.morph.profiles.base import AttachRule, Profile, Rule

#: Combining diacritical marks, folded into every word-ish character class.
#:
#: ``\w`` does not match U+0301 and friends, so decomposed (NFD) text — which is
#: what macOS filesystems and a fair number of web forms hand over — would
#: tokenize *tržby* as ``tr``, ``z``, a caron, ``by``. The tokenizer cannot
#: normalise its input away: every offset it emits indexes into the caller's
#: string, and NFC-ing first would shift them all. So the character classes
#: accept the marks instead and the text stays exactly as the caller holds it.
_MARK = "\u0300-\u036f"
_W = rf"[\w{_MARK}]"

#: Thousands separators seen in practice: plain, no-break, narrow no-break.
_THOUSANDS = " \u00a0\u202f"

#: Abbreviations whose trailing period is part of the token. Casefolded, and
#: written WITHOUT the period — the attach rule supplies it.
#:
#: Multi-period abbreviations (``s.r.o.``, ``a.s.``) are deliberately absent:
#: they need a different rule shape (a pattern, not a word plus a period), and
#: pretending they work would be worse than tokenizing them as several tokens,
#: which is what the resolver's own lists already handle.
ABBREVIATIONS = frozenset(
    {
        # counting and quantity
        "č",
        "čís",
        "ks",
        "tis",
        "mil",
        "mld",
        "hod",
        "min",
        "sek",
        "str",
        # discourse
        "např",
        "atd",
        "apod",
        "tzv",
        "tj",
        "resp",
        "mj",
        "popř",
        "zejm",
        # documents and law — the invoice/contract vocabulary
        "odst",
        "písm",
        "zák",
        "vyhl",
        "obch",
        "obr",
        "tab",
        "kap",
        "ev",
        "var",
        # addresses
        "ul",
        "nám",
        "tř",
    }
)

#: What may follow an ordinal's period: a digit (``31. 12.``) or a lowercase
#: word (``5. patra``), after optional space. Anything else ends a sentence.
#:
#: No ``^`` anchor: the driver applies this with ``regex.match(text, pos)``,
#: which anchors at ``pos`` — while ``^`` would still only match at the true
#: start of the string, so an anchored pattern silently never fires except on
#: the first character of the document.
_ORDINAL_CONTEXT = rf"[{_THOUSANDS}\t]*(?:\d|[a-z0-9áäčďéěíĺľňóôöŕřšťúůüýž])"

#: Codes and SKUs — ``AB-123/X``, ``INV-2026/0042``. The lookahead is what
#: separates a code from a hyphenated word: ``Praha-východ`` and ``e-shop``
#: have the same shape and no digit, so they fall through to the word rule and
#: stay one token there. Without the digit test, every hyphenated Czech
#: compound would be typed ``code`` and the rules that match words would miss
#: it.
_CODE = rf"(?=[\w\-/{_MARK}]*\d){_W}+(?:[-/]{_W}+)+"

#: Numbers. First alternative: grouped thousands with an optional decimal part
#: (``1 234,50``) — one token, spaces and all, which is why every offset test
#: asserts ``text[start:end] == token.text`` rather than assuming tokens are
#: space-free. Second: a plain integer or decimal. The trailing ``(?!\w)``
#: keeps ``2025x`` from splitting into a number and a word.
_NUMBER = (
    rf"\d{{1,3}}(?:[{_THOUSANDS}]\d{{3}})+(?:,\d+)?(?!{_W})"
    rf"|\d+(?:,\d+)?(?!{_W})"
)

#: Words, including hyphenated compounds (``e-shop``, ``Praha-východ``).
_WORD = rf"{_W}+(?:-{_W}+)*"

#: Structural punctuation — what prose is built from. Everything else non-space
#: that no rule claimed is emitted as ``sym`` by the driver: currency signs,
#: maths, ``@``, ``#``, emoji. The split matters to rule authors, who want
#: "ignore the punctuation" to mean commas and periods, not to swallow the ``%``
#: that makes a number a percentage.
_PUNCT = r"[.,;:!?…()\[\]{}\"'„“”‚‘’«»–—/\\-]"

#: Endings the fallback chain may strip from an unknown form, longest first.
#:
#: This is a *guess* list, not a paradigm: the real endings live in the vzor
#: tables in `ttr-morph`, and anything that reaches this list is a word the
#: lexicon does not have. It is short and biased towards the noun cases the
#: query domain actually produces (*Kauflandu*, *Kauflandem*, *Nováková*),
#: because a long list guesses confidently and wrongly — and every guess it
#: makes is a token the enrichment loop then has to un-learn.
GUESS_SUFFIXES = (
    "ovi",
    "ami",
    "ách",
    "ími",
    "ých",
    "ému",
    "ého",
    "ům",
    "ám",
    "em",
    "ou",
    "mi",
    "ým",
    "ím",
    "ě",
    "u",
    "y",
    "e",
    "a",
    "o",
    "i",
    "í",
    "ý",
    "á",
    "é",
)

CS = Profile(
    name="cs",
    guess_suffixes=GUESS_SUFFIXES,
    rules=(
        Rule(name="code", kind="code", pattern=_CODE),
        Rule(name="number", kind="number", pattern=_NUMBER),
        Rule(name="word", kind="word", pattern=_WORD),
        Rule(name="punct", kind="punct", pattern=_PUNCT),
    ),
    attach=(
        AttachRule(
            after_kind="word",
            char=".",
            becomes="abbrev",
            when_text_in=ABBREVIATIONS,
        ),
        AttachRule(
            after_kind="number",
            char=".",
            becomes="ordinal",
            when_following=_ORDINAL_CONTEXT,
        ),
    ),
)

__all__ = ["ABBREVIATIONS", "CS"]
