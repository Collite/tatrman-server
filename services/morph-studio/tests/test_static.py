# SPDX-License-Identifier: Apache-2.0
"""The built frontend, served by the backend (NLS-P9.3 T1).

One deployable. The FI-7 frontend is a static bundle and this service is the
only thing in front of it, so an nginx pod would add a chart, a Service, an
ingress rule and a CORS policy in order to serve files this process can already
read.

The interesting half is what the catch-all must NOT do: answer for the API. A
mistyped `/v1/queeu` that came back as 200 text/html would surface in a
generated client as a JSON parse error, far from its cause.
"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from morph_studio.api import create_app
from morph_studio.config import Settings

from .conftest import WORLD

INDEX = "<!doctype html><title>morph-studio</title><div id=app></div>"


@pytest.fixture
def dist(tmp_path):
    """A `frontend/dist` as vite writes one."""
    root = tmp_path / "dist"
    (root / "assets").mkdir(parents=True)
    (root / "index.html").write_text(INDEX, encoding="utf-8")
    (root / "assets" / "index-abc123.js").write_text("/* bundle */", encoding="utf-8")
    (root / "favicon.svg").write_text("<svg/>", encoding="utf-8")
    return root


@pytest.fixture
def served(dist, tmp_path):
    settings = Settings(
        world=WORLD,
        db_url="sqlite+pysqlite:///:memory:",
        export_dir=str(tmp_path / "export"),
        static_dir=str(dist),
    )
    with TestClient(create_app(settings, schema=True)) as client:
        yield client


def test_the_root_serves_the_app(served):
    response = served.get("/")
    assert response.status_code == 200
    assert "morph-studio" in response.text


def test_the_hashed_bundle_is_served_from_its_own_mount(served):
    response = served.get("/assets/index-abc123.js")
    assert response.status_code == 200
    assert "bundle" in response.text


def test_a_real_file_beats_the_fallback(served):
    assert served.get("/favicon.svg").text == "<svg/>"


def test_a_client_side_route_gets_the_app_not_a_404(served):
    """`/queue` and `/entry/12` are the router's, not the filesystem's."""
    for path in ("/queue", "/entry/12", "/word/Kauflandu"):
        response = served.get(path)
        assert response.status_code == 200, path
        assert "morph-studio" in response.text


def test_the_api_still_answers_with_the_frontend_mounted(served):
    """Routes registered before the catch-all keep their paths."""
    assert served.get("/v1/status").json()["world"] == WORLD
    assert served.get("/v1/machine").status_code == 200
    assert served.get("/healthz").json()["status"] == "ok"


def test_a_mistyped_api_path_is_a_404_and_not_the_app(served):
    """⚑ The one thing the catch-all must refuse.

    An unknown `/v1/...` answered with `index.html` is a 200 of HTML where a
    client expected JSON — the error surfaces as a parse failure in the
    browser, with nothing pointing at the typo that caused it.
    """
    response = served.get("/v1/queeu")
    assert response.status_code == 404
    assert "html" not in response.headers["content-type"]


def test_no_frontend_directory_is_a_supported_deployment(client):
    """`dfp` mode has no authoring UI by ruling (LM-12).

    The default `client` fixture sets no `static_dir`; the API answers and `/`
    is simply not a route.
    """
    assert client.get("/v1/status").status_code == 200
    assert client.get("/").status_code == 404


def test_a_configured_but_unbuilt_frontend_does_not_stop_the_api(tmp_path, caplog):
    """`npm run build` never ran. The bootstrap batch's ingests still work."""
    settings = Settings(
        world=WORLD,
        db_url="sqlite+pysqlite:///:memory:",
        export_dir=str(tmp_path / "export"),
        static_dir=str(tmp_path / "nothing-here"),
    )
    with TestClient(create_app(settings, schema=True)) as client:
        assert client.get("/v1/status").status_code == 200
        assert client.get("/").status_code == 404
