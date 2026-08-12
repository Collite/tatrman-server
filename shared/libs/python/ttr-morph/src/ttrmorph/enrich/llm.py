# SPDX-License-Identifier: Apache-2.0
"""The LLM classifier leg (LM-14, contracts §8; NLS-P9.2 T5).

**A classification task, never a generation task.** The model is asked which
lemma, part of speech and *pattern* a word has — a closed choice out of an
inventory printed into the prompt — and never for the forms themselves. That is
the whole design: picking `hrad-proper` out of a list is something a cheap model
does well, while writing out fourteen Czech case forms is something it does
plausibly and wrongly, and a plausible wrong form goes straight into an artifact
that a query then matches against. The engine generates; the model only points.

**Its answer is a proposal like any other.** It comes back through the same
`Proposal` shape as the guesser, it is auto-validated by the same engine check
(`guesser.validates`), and it carries a confidence that on its own is
deliberately below the auto-validate line: an LLM proposal that no other leg
agrees with meets a human. What it is really for is the tie-break —
guesser-and-model agreement is what lets a word skip the queue.

**The air-gap switch is the absence of an address.** `from_env` returns ``None``
when the deployment has not configured a gateway, and the cascade then runs
guesser → human with nothing else said. This mirrors the ruling
`services/nlp` already made for its emulated engine: a gateway with no address
cannot be called at all, whereas a *keyless* one is an ordinary local setup, so
the key warns and the address decides. (The task list's shorthand says "unset
key ⇒ leg skipped"; treating a missing key as the switch would make a working
local gateway unusable, and the two readings agree everywhere else — an
air-gapped deployment configures neither.)

**It is never on a hot path, and that is enforced rather than intended.** This
module is imported by `morph-studio` and by nothing else; the NLS-P9.2 Verify
step greps `services/nlp` and the wheel for it and expects nothing. A front that
called a language model to lemmatize a token would have given up every property
the LM effort exists to deliver — determinism, latency, and an air-gappable
deployment.
"""

from __future__ import annotations

import json
import logging
import os
import re
import time
from collections.abc import Callable, Mapping
from dataclasses import dataclass

from ttrmorph.engine.tables import load
from ttrmorph.enrich.guesser import Proposal

logger = logging.getLogger(__name__)

#: `proposal.source` for everything this module produces (contracts §7).
SOURCE_LLM = "llm"

#: Bumped whenever the prompt below changes meaning. It rides in the stored
#: proposal's provenance note, so a re-run under a new template is visibly not
#: the same answer as the old one — the same rule `services/nlp` applies to its
#: emulated engine's `model_version` (S-1).
TEMPLATE_VERSION = "1"

#: The OpenAI-compatible data-plane route the repo's LG 2.0 gateway serves.
#: Same path as `nlp_service.engines.llm_gateway`, and for the same reason: the
#: legacy `infra/llm-gateway` route (`/api/v1/chat/responses`) is a different
#: service, and posting there returns a 404 that looks exactly like a working
#: air-gap.
CHAT_PATH = "/v1/chat/completions"

#: The leg never samples. A classifier that answered differently on two
#: identical queue items would make the audit trail fiction.
TEMPERATURE = 0

#: What an LLM-only proposal is worth. Below `AUTO_VALIDATE_CONFIDENCE` by
#: construction: the model has no way to check itself, so its unaccompanied
#: answer is a suggestion for a human, and its value in the cascade is
#: agreement with the deterministic leg.
LLM_CONFIDENCE = 0.60

#: Environment keys. `MORPH_LLM_URL` and `MORPH_LLM_MODEL` are the switch.
ENV_URL = "MORPH_LLM_URL"
ENV_MODEL = "MORPH_LLM_MODEL"
ENV_API_KEY = "MORPH_LLM_API_KEY"
ENV_TIMEOUT = "MORPH_LLM_TIMEOUT_SECONDS"

#: Haiku-class by ruling (architecture §7) — a classifier, not an author.
DEFAULT_MODEL = "claude-haiku-4-5-20251001"

#: UD v2. A tag outside this set is an invention, not a tag.
_UPOS = frozenset(
    "ADJ ADP ADV AUX CCONJ DET INTJ NOUN NUM PART PRON PROPN PUNCT SCONJ SYM "
    "VERB X".split()
)

#: Models fence JSON even when told not to. Stripping the fence is not being
#: lenient about the schema — everything inside it is still validated.
_FENCE = re.compile(r"^\s*```(?:json)?\s*(.*?)\s*```\s*$", re.DOTALL)


class LlmError(RuntimeError):
    """The leg could not produce a usable proposal."""


class LlmUnavailable(LlmError):
    """The gateway could not be reached or would not serve. Degrade to human."""


class LlmRefused(LlmError):
    """The gateway answered, and the answer was not a proposal we may store."""


@dataclass(frozen=True)
class LlmSpec:
    url: str
    model: str = DEFAULT_MODEL
    api_key: str = ""
    timeout_seconds: int = 20
    max_retries: int = 2


#: A transport is ``(system, user) -> assistant content``. One callable, so a
#: test can drive the whole leg — prompt construction, parsing, validation —
#: without a socket, and so an estate with a different gateway can hand its own
#: in without this module knowing what a gateway is.
Transport = Callable[[str, str], str]


class LlmLeg:
    """The classifier, over one transport."""

    def __init__(
        self,
        spec: LlmSpec,
        *,
        transport: Transport | None = None,
        lang: str = "cs",
    ):
        self.spec = spec
        self.lang = lang
        self._transport = transport or _http_transport(spec)

    @classmethod
    def from_env(
        cls,
        env: Mapping[str, str] | None = None,
        *,
        transport: Transport | None = None,
        lang: str = "cs",
    ) -> LlmLeg | None:
        """The configured leg, or ``None`` for a deployment without one.

        ``None`` is not an error and is not logged as one: an air-gapped world
        running guesser → human is a supported deployment, not a degraded one.
        """
        env = os.environ if env is None else env
        url = (env.get(ENV_URL) or "").strip()
        model = (env.get(ENV_MODEL) or DEFAULT_MODEL).strip()
        if not url or not model:
            return None
        api_key = (env.get(ENV_API_KEY) or "").strip()
        if not api_key:
            logger.warning(
                "%s is set with no %s — the gateway will reject every call "
                "unless it is deployed without key validation",
                ENV_URL,
                ENV_API_KEY,
            )
        timeout = env.get(ENV_TIMEOUT) or ""
        spec = LlmSpec(
            url=url,
            model=model,
            api_key=api_key,
            timeout_seconds=int(timeout) if timeout.strip().isdigit() else 20,
        )
        return cls(spec, transport=transport, lang=lang)

    # ── the one public call ─────────────────────────────────────────────────

    def classify(self, token: str, *, context: str = "") -> Proposal:
        """One proposal for one observed form.

        Args:
            token: The surface form, as observed.
            context: The optional span the world's config allowed us to keep
                (contracts §6). Passed through when there is one — *ženu* is a
                noun or a verb depending on the sentence — and never required.

        Raises:
            LlmUnavailable: The gateway did not answer.
            LlmRefused: It answered something that is not a valid proposal.
        """
        content = self._transport(self._system(), self._user(token, context))
        data = _parse(content)
        return self._validate(data)

    # ── prompt ──────────────────────────────────────────────────────────────

    def _system(self) -> str:
        tables = load(self.lang)
        vzory = ", ".join(tables.vzory)
        flags = ", ".join(tables.flag_order)
        return (
            "You are a Czech morphological classifier. Given one word form, you "
            "identify its dictionary entry.\n\n"
            "Answer with a single JSON object and nothing else:\n"
            '{"lemma": "<citation form>", "upos": "<UD tag>", '
            '"vzor": "<pattern name>", "flags": ["<flag>", ...]}\n\n'
            "Rules:\n"
            "- `lemma` is the citation form (nominative singular for nouns, "
            "masculine nominative singular for adjectives, infinitive for verbs).\n"
            "- `upos` is one of: " + " ".join(sorted(_UPOS)) + "\n"
            "- `vzor` MUST be one of these pattern names, exactly as written: "
            + vzory
            + "\n"
            "- `flags` is a possibly empty subset of: " + flags + "\n"
            "- Do NOT write out any inflected forms. The forms are generated "
            "from the pattern you choose; your only job is to choose it.\n"
            "- If you are unsure of the pattern, choose the closest one rather "
            "than inventing a name. An invented name is discarded."
        )

    def _user(self, token: str, context: str) -> str:
        if context:
            return f"Word form: {token}\nSeen in: {context}"
        return f"Word form: {token}"

    # ── answer ──────────────────────────────────────────────────────────────

    def _validate(self, data: Mapping[str, object]) -> Proposal:
        """The closed inventory, enforced on our side.

        Printing the inventory in the prompt is a request; this is the check.
        A vzor name the tables do not have would reach the database, fail to
        generate, and sit in the queue as a proposal no reviewer can act on.
        """
        tables = load(self.lang)
        lemma = str(data.get("lemma") or "").strip()
        upos = str(data.get("upos") or "").strip().upper()
        vzor = str(data.get("vzor") or "").strip()
        raw_flags = data.get("flags") or ()
        if isinstance(raw_flags, str):
            raw_flags = [raw_flags]
        flags = tuple(
            sorted(str(flag).strip() for flag in raw_flags if str(flag).strip())
        )

        if not lemma:
            raise LlmRefused("the answer carries no lemma")
        if upos not in _UPOS:
            raise LlmRefused(f"{upos!r} is not a UD part of speech")
        if vzor not in tables.vzory:
            raise LlmRefused(
                f"{vzor!r} is not a pattern in the {self.lang} tables — the "
                "inventory is closed and printed in the prompt"
            )
        unknown = [flag for flag in flags if flag not in tables.flags]
        if unknown:
            raise LlmRefused(f"unknown flags {unknown!r}")

        return Proposal(
            lemma=lemma,
            upos=upos,
            vzor=vzor,
            flags=flags,
            confidence=LLM_CONFIDENCE,
            source=SOURCE_LLM,
        )


def _parse(content: str) -> Mapping[str, object]:
    fenced = _FENCE.match(content or "")
    body = fenced.group(1) if fenced else (content or "")
    try:
        data = json.loads(body)
    except ValueError as exc:
        raise LlmRefused(f"the answer is not JSON: {exc}") from exc
    if not isinstance(data, Mapping):
        raise LlmRefused(f"the answer is a {type(data).__name__}, not an object")
    return data


def _http_transport(spec: LlmSpec) -> Transport:
    """The default transport: the repo's llm-gateway, OpenAI-compatible.

    Retry posture copied from `nlp_service.engines.llm_gateway`, deliberately:
    retry transport failures, 5xx and 429; never retry a 4xx. A 4xx is a bad
    key, a rejected body or a model this key may not use, and retrying it
    spends money to be told the same thing again.
    """

    def send(system: str, user: str) -> str:
        import httpx  # optional: the `[llm]` extra, and only this leg needs it

        payload = {
            "model": spec.model,
            "temperature": TEMPERATURE,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
        }
        headers = {"Content-Type": "application/json"}
        if spec.api_key:
            headers["Authorization"] = f"Bearer {spec.api_key}"
        url = spec.url.rstrip("/") + CHAT_PATH
        last = ""

        for attempt in range(spec.max_retries + 1):
            more = attempt < spec.max_retries
            try:
                with httpx.Client(timeout=spec.timeout_seconds) as client:
                    response = client.post(url, json=payload, headers=headers)
            except Exception as exc:  # noqa: BLE001 — httpx's tree, plus DNS
                last = f"transport: {exc}"
                if more:
                    time.sleep(0.2 * (attempt + 1))
                continue

            if response.status_code == 200:
                return _content(response.json())
            last = f"HTTP {response.status_code}"
            if response.status_code < 500 and response.status_code != 429:
                raise LlmUnavailable(f"llm-gateway refused the request ({last})")
            if more:
                time.sleep(0.2 * (attempt + 1))

        raise LlmUnavailable(f"llm-gateway did not answer ({last})")

    return send


def _content(body: Mapping[str, object]) -> str:
    try:
        content = body["choices"][0]["message"]["content"]  # type: ignore[index]
    except (KeyError, IndexError, TypeError) as exc:
        raise LlmUnavailable(
            "llm-gateway returned 200 with no message content"
        ) from exc
    if not isinstance(content, str) or not content.strip():
        raise LlmUnavailable("llm-gateway returned 200 with empty content")
    return content


__all__ = [
    "CHAT_PATH",
    "DEFAULT_MODEL",
    "ENV_API_KEY",
    "ENV_MODEL",
    "ENV_URL",
    "LLM_CONFIDENCE",
    "SOURCE_LLM",
    "TEMPLATE_VERSION",
    "LlmError",
    "LlmLeg",
    "LlmRefused",
    "LlmSpec",
    "LlmUnavailable",
    "Transport",
]
