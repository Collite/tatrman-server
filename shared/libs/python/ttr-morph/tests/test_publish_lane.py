# SPDX-License-Identifier: Apache-2.0
"""The `morph/v*` lane, asserted from the repo rather than from a runner.

There is no actionlint in this repo's CI, and the failures these tests catch are
ones a workflow file cannot report about itself — it only runs when a tag is
already cut, which is the wrong moment to find out that the tag also started a
module image build that has nothing to build.

Deliberately shallow: the *shape* of the lane, not its steps. A test that
asserted step order would fail on every legitimate edit and teach the next
person to delete it.
"""

from __future__ import annotations

from pathlib import Path

import pytest
import yaml

#: tests/ -> ttr-morph -> python -> libs -> shared -> the repo root.
WORKFLOWS = Path(__file__).resolve().parents[5] / ".github" / "workflows"
PUBLISH = WORKFLOWS / "publish-morph.yml"
IMAGES = WORKFLOWS / "release-image.yml"

pytestmark = pytest.mark.skipif(
    not PUBLISH.exists(), reason="not running inside the tatrman-server checkout"
)


def load(path: Path) -> dict:
    # `on:` is YAML 1.1's boolean `True`; pyyaml parses the key as such.
    return yaml.safe_load(path.read_text(encoding="utf-8"))


@pytest.fixture(scope="module")
def publish() -> dict:
    return load(PUBLISH)


def triggers(document: dict) -> dict:
    return document.get("on") or document[True]


def test_the_lane_fires_on_the_contracts_tag(publish):
    assert triggers(publish)["push"]["tags"] == ["morph/v*"]


def test_the_lane_has_a_dry_run_input(publish):
    """T6: a `workflow_dispatch` dry run, so the lane can be exercised without
    spending a version number."""
    inputs = triggers(publish)["workflow_dispatch"]["inputs"]
    assert inputs["dry_run"]["type"] == "boolean"
    assert set(inputs) >= {"dry_run", "layers", "skip_eval"}


def test_a_dispatch_can_never_publish(publish):
    """Jobs 2 and 3 are gated on `public`, and `public` is forced false for a
    manual run — including one started from a `-RELEASE` ref."""
    derive = next(
        step
        for step in publish["jobs"]["compile"]["steps"]
        if step.get("id") == "ver"
    )
    assert "PUBLIC=false" in derive["run"]
    assert 'github.event_name }}" == "workflow_dispatch"' in derive["run"]

    for job in ("release", "expand"):
        assert publish["jobs"][job]["if"] == "needs.compile.outputs.public == 'true'"


def test_all_three_contract_jobs_are_present(publish):
    """Contracts §10: compile+verify, release+image, exporter re-run (C-O2)."""
    assert set(publish["jobs"]) == {"compile", "release", "expand"}
    assert publish["jobs"]["expand"]["needs"] == ["compile", "release"]


def test_the_release_job_attaches_every_asset_the_contract_names(publish):
    step = next(
        step
        for step in publish["jobs"]["release"]["steps"]
        if str(step.get("uses", "")).startswith("softprops/action-gh-release")
    )
    files = step["with"]["files"]
    assert "cs.morph.snap" in files
    assert "*.morph.part" in files  # the separable share-alike members (C-F3)
    assert "NOTICE-morph.md" in files


def test_the_release_job_pushes_the_validation_vehicle(publish):
    """`ttr-morph` stays off PyPI, so the image is how a world repo validates
    its own layer files (contracts §10, NLB-P5 T3)."""
    step = next(
        step
        for step in publish["jobs"]["release"]["steps"]
        if str(step.get("uses", "")).startswith("docker/build-push-action")
    )
    assert "nlp-morph-tools" in step["with"]["tags"]
    assert step["with"]["file"].endswith("ttr-morph/Dockerfile")
    assert (Path(__file__).resolve().parents[1] / "Dockerfile").exists()


def test_the_image_lane_ignores_the_morph_tag():
    """⚑ `release-image.yml` triggers on `*/v*`. Without this exclusion every
    snapshot tag starts a module build for a module that does not exist, and
    leaves a red X beside a green publish."""
    tags = triggers(load(IMAGES))["push"]["tags"]
    assert "!morph/v*" in tags
