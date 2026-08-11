# SPDX-License-Identifier: Apache-2.0
"""NLS-P3.2.T1 — the REST mirror gains no pipeline surface (NL-16).

An absence needs a test, because nothing else will notice it. The REST app exists
for `/healthz`, `/readyz`, `/version` and a dev `/v1/analyze`; gRPC is the service
contract (contracts §1). `RunPipeline` returns a *document* — annotation sets,
typed features, stable ids — and a JSON mirror of that would immediately become a
second wire format for the same thing, with its own idea of how a feature value
is encoded and no field numbers to keep it honest.

The pressure to add one is real and will come from somewhere convenient (a curl
in a runbook, a dashboard). This test is the answer: if a `/v1/pipeline` route
ever appears, it fails, and whoever added it has to change a test that says why
not.
"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from nlp_service.api.routes import create_app


@pytest.fixture(scope="module")
def client() -> TestClient:
    return TestClient(create_app())


@pytest.mark.parametrize(
    "path",
    [
        "/v1/pipeline",
        "/v1/pipelines",
        "/v1/run-pipeline",
        "/v1/reload-packs",
        "/v1/packs",
    ],
)
def test_no_pipeline_route_exists(client, path):
    assert client.post(path, json={}).status_code == 404
    assert client.get(path).status_code == 404


def test_the_declared_routes_are_only_health_version_and_dev_analyze():
    """Enumerated rather than probed, so a new route has to be added here too."""
    paths = {
        route.path
        for route in create_app().routes
        if getattr(route, "path", "").startswith(("/v1", "/health", "/ready", "/version"))
    }
    assert paths == {"/healthz", "/readyz", "/version", "/v1/analyze"}


def test_the_health_endpoints_still_work(client):
    assert client.get("/healthz").status_code == 200
    assert client.get("/version").status_code == 200
