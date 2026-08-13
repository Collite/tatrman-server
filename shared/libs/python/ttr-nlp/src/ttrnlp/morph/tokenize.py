# SPDX-License-Identifier: Apache-2.0
"""The deterministic rule tokenizer (LM-9) — one substrate for everything.

Why the suite owns a tokenizer at all: **every statistical component consumes
pretokenized input**. The moment two components tokenize independently, their
offsets disagree somewhere, and an offset disagreement between the lemmatizer
and the rule engine is a rule that fires on a laptop and not in production. One
tokenizer, run once, and everything downstream annotates the spans it produced.

**Deterministic, and cheap.** A regex cascade over profile data: no model, no
learned boundaries, no scoring. Same input, same tokens, every time, on every
machine — which is what lets a golden-file suite be the specification.

**What it does not do.** No sentence splitting. The morph pipeline does not need
sentences (the annotator works token by token and the rule engine matches within
the document), and the languages that *do* need them get sentence boundaries
from the engine ops that already produce them. Adding a second, worse sentence
splitter here would just be a second opinion nothing asked for.

Contractions are not tokenizer magic either: ``abych`` is one token, and it is
the *lexicon* that knows it decomposes to *aby* + *bych* (`Analysis.parts`).
Splitting it here would put morphology in the scanner and give the rule engine
spans no engine would ever agree with.

Usage::

    from ttrnlp.morph import tokenize
    for token in tokenize("Porovnej tržby Kauflandu", profile="cs"):
        ...  # Token(text, start, end, kind)

Every token satisfies ``text[token.start:token.end] == token.text`` — including
``1 234,50``, which contains a space. Consumers slice, they do not re-split.
"""

from __future__ import annotations

from dataclasses import dataclass

from ttrnlp.morph.profiles import Profile, get_profile

#: Emitted for any non-space character no rule claimed. The driver never stalls.
FALLBACK_KIND = "sym"


@dataclass(frozen=True)
class Token:
    """One token: its text, its half-open span, and what kind of thing it is.

    ``kind`` is one of ``word``, ``number``, ``ordinal``, ``abbrev``, ``code``,
    ``punct``, ``sym``. The morph annotator looks up only the kinds that can
    have a lemma; the rest are carried, not analysed.
    """

    text: str
    start: int
    end: int
    kind: str


def _attach(text: str, token: Token, profile: Profile) -> Token:
    """Apply the first attach rule that fits, or return the token unchanged.

    First, not all: a character can only be absorbed once, and two rules that
    both want the same period would otherwise produce a token whose text is not
    the text it covers.
    """
    for rule in profile.attach:
        if token.kind != rule.after_kind:
            continue
        if text[token.end : token.end + 1] != rule.char:
            continue
        if rule.when_text_in is not None and token.text.casefold() not in (
            rule.when_text_in
        ):
            continue
        if rule.following_regex is not None and not rule.following_regex.match(
            text, token.end + 1
        ):
            continue
        end = token.end + 1
        return Token(
            text=text[token.start : end],
            start=token.start,
            end=end,
            kind=rule.becomes,
        )
    return token


def tokenize(text: str, *, profile: str = "cs") -> list[Token]:
    """Split ``text`` into tokens using a language profile.

    Args:
        text: The text to tokenize. Offsets are Python character indices — the
            same units the annotation model and the engine adapters use, which
            matters for Czech (``zákazníka`` is 9 characters and 11 bytes).
        profile: Language profile name (`ttrnlp.morph.profiles`).

    Returns:
        Tokens in document order, non-overlapping, whitespace excluded except
        where a rule deliberately spans it (grouped thousands).

    Raises:
        UnknownProfile: If no profile is registered under that name.
    """
    prof = get_profile(profile)
    tokens: list[Token] = []
    pos = 0
    length = len(text)

    while pos < length:
        if text[pos].isspace():
            pos += 1
            continue

        token = None
        for rule in prof.rules:
            match = rule.regex.match(text, pos)
            if match and match.end() > match.start():
                token = Token(
                    text=match.group(),
                    start=match.start(),
                    end=match.end(),
                    kind=rule.kind,
                )
                break

        if token is None:
            # No rule claimed this character. Emit it rather than skip it: a
            # dropped character is a hole in the offsets everything downstream
            # trusts, and this is also the guarantee that makes the fuzz floor
            # (p7-1 T5) hold — the cursor always advances.
            token = Token(text=text[pos], start=pos, end=pos + 1, kind=FALLBACK_KIND)

        token = _attach(text, token, prof)
        tokens.append(token)
        pos = token.end

    return tokens


__all__ = ["FALLBACK_KIND", "Token", "tokenize"]
