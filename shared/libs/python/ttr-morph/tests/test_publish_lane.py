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
    assert set(inputs) >= {"dry_run", "layers"}


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


# ── the layer list, the gate, and C-O2 (NLS-P8.4) ────────────────────────────

LEXICON = Path(__file__).resolve().parents[1] / "lexicon" / "cs"
LAYERS = LEXICON / "LAYERS"
EXPORT = WORKFLOWS / "export-lists.yml"
JUSTFILE = WORKFLOWS.parents[1] / "justfile"


def declared_layers() -> list[str]:
    return [
        line.strip()
        for line in LAYERS.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    ]


def test_every_layer_file_on_disk_is_named_in_LAYERS():
    """⚑ The check that would have caught it: the glob this replaced sorted its
    matches and missed the hand seed entirely, and the artifact it produced was
    a valid snapshot without 196 curated entries in it."""
    on_disk = {path.name for path in LEXICON.glob("*.morph.yaml")}
    assert set(declared_layers()) == on_disk


def test_the_layer_order_puts_the_human_last():
    """Precedence: a corpus row is evidence a form exists, an inflection table
    is a paradigm, a human overrules both — and must be able to do it by adding
    one line rather than by re-running an importer."""
    assert declared_layers()[-1] == "core-hand.morph.yaml"


def test_the_lane_and_the_justfile_read_the_SAME_layer_list(publish):
    step = next(
        step
        for step in publish["jobs"]["compile"]["steps"]
        if step.get("id") == "layers"
    )
    assert "LAYERS" in step["run"]
    assert "LAYERS" in JUSTFILE.read_text(encoding="utf-8")


def test_the_eval_gate_has_no_off_switch(publish):
    """NLS-P8.2 left `--skip-eval` so this lane could exist before the harness
    did. A gate with an off switch is a gate that is off."""
    text = PUBLISH.read_text(encoding="utf-8")
    assert "skip_eval" not in text
    assert "--skip-eval" not in text

    step = next(
        step
        for step in publish["jobs"]["compile"]["steps"]
        if step.get("name") == "Eval-harness gate"
    )
    assert "ttr-morph eval" in step["run"] and "--gate" in step["run"]


def test_the_release_gate_does_not_read_the_oracle(publish):
    """The test side is shared with Wave C training (LM-16/S-6). A release lane
    that downloaded and read it on every tag would be doing that unwatched."""
    step = next(
        step
        for step in publish["jobs"]["compile"]["steps"]
        if step.get("name") == "Eval-harness gate"
    )
    assert "--cac" not in step["run"]


def test_the_gate_reference_is_committed():
    assert (Path(__file__).resolve().parents[1] / "eval" / "baseline.json").exists()


def test_the_c_o2_dispatch_target_exists(publish):
    """It used to be a warning-and-continue, because the workflow did not exist.
    It does now, and a C-O2 dispatch that quietly does not happen is the exact
    failure C-O2 exists to prevent."""
    assert EXPORT.exists()
    step = next(
        step
        for step in publish["jobs"]["expand"]["steps"]
        if "gh workflow run" in str(step.get("run", ""))
    )
    assert "::error::" in step["run"]


def test_the_dispatch_names_the_tag_it_was_cut_from(publish):
    """The lists must be expanded from the artifact that was published, not
    from a rebuild of the ref — ⚑ verify from the artifact."""
    step = next(
        step
        for step in publish["jobs"]["expand"]["steps"]
        if "gh workflow run" in str(step.get("run", ""))
    )
    assert "snapshot_tag" in step["run"]


def test_the_export_lane_is_dispatchable_and_survives_zero_targets():
    """An empty target list is the documented state — tatrman-server owns no
    exported lists, because the exporter is world-side (NL-17)."""
    export = load(EXPORT)
    assert "workflow_dispatch" in triggers(export)
    assert set(triggers(export)["workflow_dispatch"]["inputs"]) >= {
        "reason",
        "open_pr",
        "snapshot_tag",
    }
    step = next(
        step
        for step in export["jobs"]["export"]["steps"]
        if step.get("name") == "Nothing to re-export"
    )
    assert step["if"] == "steps.targets.outputs.count == '0'"


def test_the_targets_file_parses_and_is_honest():
    targets = load(WORKFLOWS.parent / "export-targets.yaml")
    assert targets["targets"] == []
