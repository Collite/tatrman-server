# SPDX-License-Identifier: Apache-2.0
"""Three parser combinators PAMPAC does not usably provide.

All three subclass PAMPAC's public ``PampacParser`` ABC and compose with the
stock combinators. None is a *patch* to gatenlp — the NL-2 vendored-subset budget
(">2 local patches") is untouched, and the pin stays a pin.

**ZeroWidth** — the DSL's ``after:`` / ``notafter:`` / ``not:``.

gatenlp ships a ``Lookahead``, and in 1.0.8 it cannot run at all::

    ret = self.parser.parse(location, context).issuccess()   # ret is a bool
    if ret.issuccess():                                      # AttributeError

(``gatenlp/pam/pampac/pampac_parsers.py:742``. The multi-result branch below it
is dead too: it clears ``allres`` inside the success case, so it always fails.)

Even fixed, its shape is not ours: ``Lookahead(a, b)`` means "a, but only if b
follows", whereas the DSL needs a standalone zero-width *assertion step* that
can sit anywhere in a sequence and contributes nothing to the match span.

**Conjunction** — the DSL's ``all: [...]``, JAPE's ``{A, !B}``.

``And`` exists but concatenates every member's results into one Success, so a
two-member conjunction yields two results that downstream parsers read as
alternatives, and the combined span is whichever one is looked at first. A
conjunction is one match at one position, so it needs one result.

**Named** — the DSL's ``bind:`` on anything PAMPAC will not name.

PAMPAC's ``name=`` is not universal. ``Or`` and the ``Filter`` that
``covering()``/``within()`` return have no ``name`` at all, and ``Seq`` — the
obvious "wrap it in a one-element sequence" — opens with ``assert
len(parsers) > 1``. Setting ``.name`` on the inner parser is no answer either:
where the compiler already named it, an outer ``bind:`` would erase the inner
one and the rule would fire while writing nothing.
"""

from __future__ import annotations

from gatenlp import Span
from gatenlp.pam.pampac.data import Failure, Result, Success
from gatenlp.pam.pampac.pampac_parsers import PampacParser


class ZeroWidth(PampacParser):
    """Assert that a parser does (or does not) match here, consuming nothing.

    On success the returned location is the *input* location and the span is
    empty at that offset, so the assertion never widens the match — which is
    what "zero-width" has to mean for ``notafter`` to be usable as the last step
    of a rule without changing what the rule produces.
    """

    def __init__(
        self, parser: PampacParser, *, negate: bool = False, name: str | None = None
    ):
        self.parser = parser
        self.negate = negate
        self.name = name

    def parse(self, location, context):
        matched = self.parser.parse(location, context).issuccess()
        if matched == self.negate:
            return Failure(
                context=context,
                location=location,
                parser=self.__class__.__name__,
                message=(
                    "negative lookahead matched" if self.negate else "lookahead failed"
                ),
            )
        offset = location.text_location
        span = Span(offset, offset)
        matches = (
            dict(span=span, location=location, name=self.name) if self.name else None
        )
        return Success(Result(matches=matches, location=location, span=span), context)


class Conjunction(PampacParser):
    """Every member must match at this position; the result is one match.

    The span and continuation location come from the member that consumed the
    most, so ``{!B, A}`` behaves like ``{A, !B}`` — a zero-width member must not
    decide where the conjunction ends just by being written first. Match info
    from every member is merged, so a binding inside any member survives.
    """

    def __init__(self, *parsers: PampacParser, name: str | None = None):
        if not parsers:
            raise ValueError("a conjunction needs at least one member")
        self.parsers = parsers
        self.name = name

    def parse(self, location, context):
        merged: list[dict] = []
        widest = None
        for parser in self.parsers:
            ret = parser.parse(location, context)
            if not ret.issuccess():
                return Failure(
                    context=context,
                    location=location,
                    parser=self.__class__.__name__,
                    message="not every member of `all:` matched",
                )
            result = ret.result("first")
            merged.extend(result.matches)
            if widest is None or result.span.end > widest.span.end:
                widest = result

        span = Span(widest.span.start, widest.span.end)
        if self.name:
            merged.append(dict(span=span, location=widest.location, name=self.name))
        return Success(
            Result(matches=merged, location=widest.location, span=span), context
        )


class Named(PampacParser):
    """Bind a name to whatever a parser matched, leaving the parser alone.

    Every result of the inner parser gains one match entry for ``name``, spanning
    that result. Location and span are passed through untouched, so wrapping is
    invisible to the rest of the tree.

    The entry carries no ``ann`` key, because a span is all this can honestly
    report: the wrapped parser may have matched several annotations or none. That
    is the same thing ``bind:`` on a group or a repetition means, and the
    compiler's ``annotation_binds`` already says so — so ``update:`` and feature
    getters are rejected against these names rather than quietly reading nothing.
    """

    def __init__(self, parser: PampacParser, name: str):
        self.parser = parser
        self.name = name

    def parse(self, location, context):
        ret = self.parser.parse(location, context)
        if not ret.issuccess():
            return ret
        named = [
            Result(
                matches=[
                    *result.matches,
                    dict(span=result.span, location=result.location, name=self.name),
                ],
                location=result.location,
                span=result.span,
            )
            for result in ret
        ]
        return Success(named, context)


__all__ = ["Conjunction", "Named", "ZeroWidth"]
