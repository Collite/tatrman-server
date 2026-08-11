# SPDX-License-Identifier: Apache-2.0
"""The llm-gateway client the emulated engine talks through (RV-P8.1).

**Which gateway.** `services/llm-gateway` in this repo — the LG 2.0 service —
serves `POST /v1/chat/completions`, OpenAI-compatible, behind a `ttrk-` virtual
key it validates itself. That is not the endpoint `services/chrono` and
`services/geo` post to (`/api/v1/chat/responses`): those clients target the
LEGACY `infra/llm-gateway`, and copying them here would have produced a 404 on
every call — which looks exactly like the "degrade like an absent engine"
posture working.

**Why this is not `ttrnlp.client.backends.BackendClient`.** That class exists so
there is ONE retry loop for the four backend protocols, and its rule is to retry
every non-200 — deliberately, because MorphoDiTa answers 400 for a model id it
has not registered yet and the retry is what covers a rolling restart. Against a
gateway the same rule retries a malformed prompt and an invalid key, and every
retry that reaches a provider costs money. So the posture here is inverted: retry
transport failures, 5xx and 429; never retry a 4xx. Diverging from the wheel's
one-loop rule is a decision, and this paragraph is it.

**Failure is one shape.** Everything a caller can do about a gateway that will
not answer is the same thing — degrade like an absent engine — so exhausting the
retries raises `GatewayUnavailable` whatever the cause was. A body we cannot read
is a different failure and belongs to the engine, not here.
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass
from typing import Any

logger = logging.getLogger(__name__)

#: The OpenAI-compatible data-plane route (LG contracts §1.2).
CHAT_PATH = "/v1/chat/completions"

#: RV-12: the emulated engine never samples. Pinned here rather than per prompt
#: so no template can forget it.
TEMPERATURE = 0


class GatewayError(Exception):
    """The gateway did not give us something we can use."""


class GatewayUnavailable(GatewayError):
    """The gateway could not be reached, or would not serve. Degrade like absence."""


@dataclass(frozen=True)
class GatewaySpec:
    url: str
    model: str
    api_key: str = ""
    timeout_seconds: int = 15
    max_retries: int = 2


class LlmGatewayClient:
    """One chat call, retried where retrying is honest."""

    def __init__(self, spec: GatewaySpec):
        self.spec = spec

    def chat(self, *, system: str, user: str, purpose: str = "") -> str:
        """Send system+user at temperature 0 and return the message content.

        Raises:
            GatewayUnavailable: transport failure, 5xx or 429 after retries, a
                4xx (not retried), or a 2xx whose body carries no content.
        """
        import httpx  # a direct dependency of this service, unlike in the wheel

        payload: dict[str, Any] = {
            "model": self.spec.model,
            "temperature": TEMPERATURE,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
        }
        headers = {"Content-Type": "application/json"}
        if self.spec.api_key:
            headers["Authorization"] = f"Bearer {self.spec.api_key}"

        url = self.spec.url.rstrip("/") + CHAT_PATH
        last = ""

        for attempt in range(self.spec.max_retries + 1):
            try:
                with httpx.Client(timeout=self.spec.timeout_seconds) as client:
                    response = client.post(url, json=payload, headers=headers)
            except Exception as exc:  # noqa: BLE001 — httpx's tree, plus DNS
                last = f"transport: {exc}"
                logger.debug("llm-gateway %s attempt %d: %s", purpose, attempt, last)
                time.sleep(0.2 * (attempt + 1))
                continue

            if response.status_code == 200:
                return _content(response.json())

            last = f"HTTP {response.status_code}"
            if response.status_code < 500 and response.status_code != 429:
                # The request is the problem — a bad key, a body the gateway
                # rejected, a model this key may not use. Retrying spends money
                # to get the same answer.
                raise GatewayUnavailable(f"llm-gateway refused the request ({last})")
            time.sleep(0.2 * (attempt + 1))

        raise GatewayUnavailable(f"llm-gateway did not answer ({last})")


def _content(body: dict) -> str:
    """The assistant message from an OpenAI-shaped response.

    Strict on purpose: a 2xx with no content is a failure, not an empty analysis.
    """
    try:
        content = body["choices"][0]["message"]["content"]
    except (KeyError, IndexError, TypeError) as exc:
        raise GatewayUnavailable("llm-gateway returned 200 with no message content") from exc
    if not isinstance(content, str) or not content.strip():
        raise GatewayUnavailable("llm-gateway returned 200 with empty content")
    return content


__all__ = [
    "CHAT_PATH",
    "TEMPERATURE",
    "GatewayError",
    "GatewaySpec",
    "GatewayUnavailable",
    "LlmGatewayClient",
]
