# SPDX-License-Identifier: Apache-2.0
"""The shape a tokenizer profile has. No language lives here (LM-2).

A profile is **data**: an ordered list of match rules, a set of attach rules,
and the small vocabularies those rules consult. The driver in
`ttrnlp.morph.tokenize` reads this shape and nothing else, which is what makes
"cs first, sk later" a new data module rather than a new branch in the scanner.

The two-stage shape — *match*, then *attach* — is the whole design. A single
regex pass cannot decide whether the period in ``31.`` belongs to the numeral,
because that depends on what comes **after** the period; and it cannot decide
whether the period in ``č.`` belongs to the word, because that depends on a
vocabulary. Both questions are about a character the scanner has already walked
past, so they are answered by rules that may extend the token just produced.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field

#: Every kind a `Token` may carry (contracts §5 / p7-1 T3).
KINDS = frozenset({"word", "number", "ordinal", "abbrev", "code", "punct", "sym"})


@dataclass(frozen=True)
class Rule:
    """One match rule: a regex anchored at the cursor, and the kind it yields.

    Rules are tried in order and the first that matches wins — no longest-match
    arbitration between rules. Order *is* the disambiguation, so the profile
    that owns the order owns the semantics: cs puts codes before numbers
    because ``123-ABC`` is a code whose first characters are a perfectly good
    number, and a longest-match rule set would have to encode that anyway.
    """

    name: str
    kind: str
    pattern: str
    regex: re.Pattern[str] = field(init=False, repr=False, compare=False)

    def __post_init__(self) -> None:
        if self.kind not in KINDS:
            raise ValueError(f"rule {self.name!r}: unknown kind {self.kind!r}")
        object.__setattr__(self, "regex", re.compile(self.pattern))


@dataclass(frozen=True)
class AttachRule:
    """Absorb one character into the token just produced, re-typing it.

    Args:
        after_kind: Only tokens of this kind may absorb.
        char: The character to absorb, if it is the very next one.
        becomes: The kind the extended token takes.
        when_text_in: If given, the token's casefolded text must be in this set
            — the vocabulary lane (abbreviations).
        when_following: If given, a regex that must match the text *after* the
            absorbed character — the context lane (ordinals).

    Both conditions are optional and both are data. A rule with neither is
    unconditional; a rule with both requires both.
    """

    after_kind: str
    char: str
    becomes: str
    when_text_in: frozenset[str] | None = None
    when_following: str | None = None
    following_regex: re.Pattern[str] | None = field(
        init=False, repr=False, compare=False
    )

    def __post_init__(self) -> None:
        if self.becomes not in KINDS:
            raise ValueError(f"attach rule: unknown kind {self.becomes!r}")
        compiled = re.compile(self.when_following) if self.when_following else None
        object.__setattr__(self, "following_regex", compiled)


@dataclass(frozen=True)
class Profile:
    """A language's tokenization data.

    ``rules`` are tried in order at every cursor position; ``attach`` rules run
    against the token each match produced. A profile needs no catch-all rule:
    the driver emits an unmatched non-space character as ``sym`` so that the
    scanner always advances (the fuzz floor, p7-1 T5).
    """

    name: str
    rules: tuple[Rule, ...]
    attach: tuple[AttachRule, ...] = ()


__all__ = ["KINDS", "AttachRule", "Profile", "Rule"]
