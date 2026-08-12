# SPDX-License-Identifier: Apache-2.0
"""RV-P8.1 T2(d) — what actually goes on the wire to llm-gateway.

Two things are worth pinning here and neither is visible from the engine's side:
the request the gateway receives (temperature 0 — RV-12 — the configured model
class, the virtual key, and the LG 2.0 route rather than the legacy one), and the
retry posture, which deliberately differs from the wheel's shared loop.
"""

from __future__ import annotations

import httpx
import pytest

from nlp_service.engines.llm_gateway import (
    CHAT_PATH,
    GatewaySpec,
    GatewayUnavailable,
    LlmGatewayClient,
)

OK_BODY = {"choices": [{"message": {"content": '{"tokens": []}'}}]}


class FakeResponse:
    def __init__(self, status_code: int, body: dict | None = None):
        self.status_code = status_code
        self._body = body or {}

    def json(self) -> dict:
        return self._body


class FakeClient:
    """Stands in for `httpx.Client`, recording every request it is handed."""

    calls: list[dict] = []
    script: list = []

    def __init__(self, *_, timeout=None, **__):
        self.timeout = timeout

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def post(self, url, json=None, headers=None):
        FakeClient.calls.append(
            {"url": url, "json": json, "headers": headers or {}, "timeout": self.timeout}
        )
        nxt = FakeClient.script.pop(0) if len(FakeClient.script) > 1 else FakeClient.script[0]
        if isinstance(nxt, Exception):
            raise nxt
        return nxt


@pytest.fixture(autouse=True)
def fake_httpx(monkeypatch):
    FakeClient.calls = []
    FakeClient.script = [FakeResponse(200, OK_BODY)]
    monkeypatch.setattr(httpx, "Client", FakeClient)
    monkeypatch.setattr("time.sleep", lambda *_: None)
    return FakeClient


def a_client(**over) -> LlmGatewayClient:
    spec = {"url": "http://llm-gateway:8080", "model": "claude-haiku-4-5", "api_key": "ttrk-x"}
    spec.update(over)
    return LlmGatewayClient(GatewaySpec(**spec))


class TestTheRequest:
    def test_temperature_is_pinned_to_zero_and_the_model_is_the_configured_one(self):
        a_client().chat(system="s", user="u")
        body = FakeClient.calls[0]["json"]
        assert body["temperature"] == 0
        assert body["model"] == "claude-haiku-4-5"
        assert [m["role"] for m in body["messages"]] == ["system", "user"]

    def test_it_posts_to_the_lg_2_0_route_not_the_legacy_one(self):
        """`services/chrono` and `services/geo` post to `/api/v1/chat/responses`
        — the LEGACY gateway. This repo's gateway is OpenAI-compatible, and
        copying the older client would have 404'd on every call, which looks
        exactly like the absent-engine degrade working."""
        a_client().chat(system="s", user="u")
        assert FakeClient.calls[0]["url"] == "http://llm-gateway:8080" + CHAT_PATH

    def test_the_virtual_key_rides_as_a_bearer_token(self):
        a_client().chat(system="s", user="u")
        assert FakeClient.calls[0]["headers"]["Authorization"] == "Bearer ttrk-x"

    def test_no_key_sends_no_header(self):
        """A blank key must not become `Bearer ` — the gateway rejects the whole
        request on a malformed one, and a 401 that says 'no key' is a better
        thing to read in a log than a 400 that says the header was junk."""
        a_client(api_key="").chat(system="s", user="u")
        assert "Authorization" not in FakeClient.calls[0]["headers"]

    def test_the_configured_timeout_reaches_the_transport(self):
        a_client(timeout_seconds=15).chat(system="s", user="u")
        assert FakeClient.calls[0]["timeout"] == 15


class TestRetryPosture:
    def test_a_4xx_is_not_retried(self):
        """The wheel's `BackendClient` retries every non-200 on purpose —
        MorphoDiTa answers 400 while it is still registering its model. Against a
        gateway that rule spends money to be told the same thing: a 401 is a key
        problem and a 400 is a body problem, and neither improves on a second
        attempt."""
        FakeClient.script = [FakeResponse(401)]
        with pytest.raises(GatewayUnavailable, match="refused"):
            a_client().chat(system="s", user="u")
        assert len(FakeClient.calls) == 1

    def test_a_5xx_is_retried_then_gives_up(self):
        FakeClient.script = [FakeResponse(503)]
        with pytest.raises(GatewayUnavailable, match="did not answer"):
            a_client(max_retries=2).chat(system="s", user="u")
        assert len(FakeClient.calls) == 3

    def test_a_429_is_retried(self):
        """The gateway rate-limits and budget-checks before any upstream work, so
        a 429 is a real 'come back' rather than a bad request."""
        FakeClient.script = [FakeResponse(429), FakeResponse(200, OK_BODY)]
        assert a_client(max_retries=1).chat(system="s", user="u")
        assert len(FakeClient.calls) == 2

    def test_a_transport_failure_is_retried_then_becomes_unavailable(self):
        FakeClient.script = [httpx.ConnectError("no route to host")]
        with pytest.raises(GatewayUnavailable, match="transport"):
            a_client(max_retries=1).chat(system="s", user="u")
        assert len(FakeClient.calls) == 2

    @pytest.mark.parametrize(
        "outcome", [httpx.ConnectError("no route to host"), FakeResponse(503)],
        ids=["transport", "5xx"],
    )
    def test_the_backoff_never_runs_after_the_final_attempt(self, monkeypatch, outcome):
        """Backing off before giving up is dead time inside a live request.

        Against a gateway that is simply down it was also the MAJORITY of the
        wait: three attempts fail immediately on connect, and the shipped
        `max_retries: 2` then spent 0.2 + 0.4 + 0.6 s, the last of which bought
        nothing because there was no further attempt to space out. Two gaps
        between three attempts, never three.
        """
        slept: list[float] = []
        monkeypatch.setattr("time.sleep", lambda seconds: slept.append(seconds))
        FakeClient.script = [outcome]

        with pytest.raises(GatewayUnavailable):
            a_client(max_retries=2).chat(system="s", user="u")

        assert len(FakeClient.calls) == 3
        assert slept == [0.2, 0.4]


class TestTheResponse:
    def test_a_200_with_no_content_is_a_failure_not_an_empty_analysis(self):
        FakeClient.script = [FakeResponse(200, {"choices": []})]
        with pytest.raises(GatewayUnavailable, match="no message content"):
            a_client().chat(system="s", user="u")

    def test_a_200_with_blank_content_is_a_failure(self):
        FakeClient.script = [FakeResponse(200, {"choices": [{"message": {"content": "   "}}]})]
        with pytest.raises(GatewayUnavailable, match="empty content"):
            a_client().chat(system="s", user="u")
