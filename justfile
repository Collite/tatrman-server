# tatrman-server — task runner
# Run `just` to list recipes. Mirrors the tatrman repo's justfile conventions
# (safe, confirming `package` release recipe); adds the Kotlin + Python build
# lanes this repo needs.

set shell := ["bash", "-uc"]

# The 17 container-image modules release-image.yml builds (per-module `<module>/v*`
# tag → ghcr.io/collite/<module>). Kept in lockstep with release-image.yml's map.
image_modules := "veles ttr-query ttr-translate ttr-validate ttr-dispatch ttr-fuzzy ttr-llm-gateway ttr-nlp ttr-meta-mcp ttr-query-mcp ttr-fuzzy-mcp ttr-nlp-mcp ttr-worker-postgres ttr-worker-mssql ttr-worker-polars ttr-identity health"

# List available recipes
default:
    @just --list

# ── Kotlin lanes ─────────────────────────────────────────────────────────────

# Full Gradle build: compile + the mocked `test` gate + ktlint (what CI runs).
build:
    ./gradlew build

# Mocked unit/PR gate only (no ktlint, no componentTest).
test:
    ./gradlew test

# The real-dependency (Testcontainers) component tier — separate from `test`.
test-component:
    ./gradlew componentTest

# ktlint check (autofix: `just fmt`).
lint:
    ./gradlew ktlintCheck

# ktlint autoformat.
fmt:
    ./gradlew ktlintFormat

# One module, e.g. `just build-kt :services:veles` / `just test-kt :services:ttr-query`.
build-kt module:
    ./gradlew {{module}}:build
test-kt module:
    ./gradlew {{module}}:test

# ── Python lanes (uv + ruff + pytest) — services/ttr-nlp, workers/ttr-worker-polars ─

# Resolve + install the frozen lock (what the Dockerfile's `uv sync --frozen` does).
build-py path:
    cd {{path}} && uv sync --frozen

# pytest; trailing args pass through, e.g. `just test-py workers/ttr-worker-polars -m component`.
test-py path *args:
    cd {{path}} && uv run pytest {{args}}

# ruff lint (autofix: `uv run ruff check --fix .`).
lint-py path:
    cd {{path}} && uv run ruff check .

# ── Conformance (RG-P6.S2 — the three-tier instrument) ───────────────────────

# The grounding eval corpus (RG-P3.S2.T7): consolidate the per-service goldens
# (chrono/geo/money) + the hand-authored supplemental into the bulk + e2e corpora.
eval-grounding-build:
    cd eval/grounding && python3 build_corpus.py

# Grounding HERMETIC tier: corpus-validity + the pure report logic. No deployed
# stack, no network — this is the part the gating tier runs.
eval-grounding-test:
    #!/usr/bin/env bash
    set -euo pipefail
    cd eval/grounding
    test -d .venv || python3 -m venv .venv
    .venv/bin/pip -q install pytest pytest-asyncio
    .venv/bin/python -m pytest tests/ -q

# The LIVE grounding eval (bulk → grounding-mcp, e2e → Golem /v2/chat). Needs a
# deployed stack, so it is NOT gating — it is the non-gating extended tier
# (RG-P6.S2.T3 / SV-P4). Bulk gate: pass-rate ≥ 80%, LLM-fallback ≤ 10%.
eval-grounding:
    cd eval/grounding && .venv/bin/python run_eval.py

# RG-P6.S2.T1 — the GATING service-level conformance tier (the SV-P3 instrument):
# the three service-level corpora — ENTITIES_ONLY (resolver), Q-17 match-quality
# (fuzzy), grounding hermetic — run self-contained, no DFP dependency. Green is
# required; CI gates on it. Provenance + corpus hashes: conformance/README.md.
conformance-service-level:
    #!/usr/bin/env bash
    set -euo pipefail
    ./gradlew :services:ttr-resolver:test --tests '*Q20ParityTest*' :services:ttr-fuzzy:test --tests '*MatchQualityCorpusTest*'
    just eval-grounding-test

# ── Release (tag-driven; mirrors tatrman's `package`) ────────────────────────

# Cut a release tag. Two families, both read the version from the git tag:
#   • server-libs   → publish.yml: the 11 org.tatrman:* Maven libs → GH Packages
#                     (staging) always, + Maven Central only for a bare x.y.z
#                     version (public release). A prerelease suffix
#                     (e.g. 0.9.5-rc.1) stays on GH Packages only — see
#                     publish.yml's lane-gating comment and tatrman's
#                     PUBLISHING.md § Release lanes (Central's free tier can't
#                     absorb fast-iteration publishing).
#   • <image module> → release-image.yml: one container image → ghcr.io/collite/<m>.
#
# ⚠️  Published versions are PERMANENT — GitHub Packages + GHCR can't be deleted,
# and a released Maven Central version is immutable. This confirms before pushing
# and refuses a dirty tree. Make the build green first (`just build`).
#
# Usage (args in order: <which> <level> [version]; `which` = the tag prefix):
#   just package server-libs patch            # Maven libs, patch bump (0.9.0 -> 0.9.1)
#   just package server-libs set 0.9.2        # Maven libs, explicit version
#   just package server-libs set 0.9.2-rc.1   # Maven libs, WIP — GH Packages only
#   just package veles set 0.9.2              # the veles image only
#   just package ttr-query patch              # the ttr-query image, patch bump
#
# `which` MUST be `server-libs` or one of the 17 image modules
# (veles, ttr-query, ttr-translate, ttr-validate, ttr-dispatch, ttr-fuzzy,
#  ttr-llm-gateway, ttr-nlp, ttr-meta-mcp, ttr-query-mcp, ttr-fuzzy-mcp,
#  ttr-nlp-mcp, ttr-worker-postgres, ttr-worker-mssql, ttr-worker-polars,
#  ttr-identity, health) — the exact tag prefixes the workflows trigger on. A
# prefix no workflow listens for creates a dead tag that publishes nothing; the
# recipe rejects those up front. Cut a release: server-libs or one image module.
package which="server-libs" level="patch" version="":
    #!/usr/bin/env bash
    set -euo pipefail

    LEVEL="{{level}}"
    CUSTOM_VERSION="{{version}}"
    PREFIX="{{which}}"   # tag prefix — MUST match a trigger in .github/workflows/

    # Map the tag prefix → what it publishes, and reject any prefix no workflow
    # listens for. Keep this in lockstep with publish.yml (server-libs) +
    # release-image.yml (the image modules).
    if [ "$PREFIX" = "server-libs" ]; then
        TARGET_DESC="the 11 org.tatrman:* Maven libs → GH Packages + Maven Central"
    elif echo " {{image_modules}} " | grep -q " $PREFIX "; then
        TARGET_DESC="the ${PREFIX} container image → ghcr.io/collite/${PREFIX}"
    else
        echo "❌ Unknown release prefix '$PREFIX'."
        echo "   Valid: server-libs | {{image_modules}}"
        echo "   (the tag prefixes .github/workflows/{publish,release-image}.yml trigger on)."
        echo "   Note: the first arg is the PREFIX, not the bump level —"
        echo "   e.g. 'just package server-libs set 0.9.2'."
        exit 1
    fi

    case "$LEVEL" in
        major|minor|patch|set) ;;
        *) echo "❌ Level must be 'major', 'minor', 'patch', or 'set'."; exit 1 ;;
    esac
    if [ "$LEVEL" = "set" ] && [ -z "$CUSTOM_VERSION" ]; then
        echo "❌ 'set' requires a version. E.g. just package server-libs set 0.9.2"; exit 1
    fi

    # A release must come from a clean, committed state — CI checks out the tag,
    # and pushing the tag carries its commit to the remote.
    if [ -n "$(git status --porcelain)" ]; then
        echo "❌ Working tree is dirty — commit or stash before cutting a release."; exit 1
    fi

    BRANCH=$(git rev-parse --abbrev-ref HEAD)
    if [ "$BRANCH" != "master" ] && [ "$BRANCH" != "main" ]; then
        read -p "⚠️  On branch '$BRANCH', not master. Tag this commit anyway? [y/N] " -n 1 -r; echo ""
        [[ ${REPLY:-} =~ ^[Yy]$ ]] || { echo "❌ Aborting."; exit 1; }
    fi

    # Latest released version = highest strict X.Y.Z under <prefix>/v* (skip pre-releases).
    LATEST=$(git tag -l "${PREFIX}/v*" | sed "s|^${PREFIX}/v||" | grep -E '^[0-9]+\.[0-9]+\.[0-9]+$' | sort -V | tail -n 1 || true)
    LATEST="${LATEST:-0.0.0}"

    if [ "$LEVEL" = "set" ]; then
        if ! [[ "$CUSTOM_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]]; then
            echo "❌ '$CUSTOM_VERSION' is not a valid semver (X.Y.Z)."; exit 1
        fi
        NEW_VERSION="$CUSTOM_VERSION"
    else
        IFS='.' read -r MAJOR MINOR PATCH <<< "$LATEST"
        case "$LEVEL" in
            major) MAJOR=$((MAJOR + 1)); MINOR=0; PATCH=0 ;;
            minor) MINOR=$((MINOR + 1)); PATCH=0 ;;
            patch) PATCH=$((PATCH + 1)) ;;
        esac
        NEW_VERSION="${MAJOR}.${MINOR}.${PATCH}"
    fi

    NEW_TAG="${PREFIX}/v${NEW_VERSION}"
    if git rev-parse -q --verify "refs/tags/${NEW_TAG}" >/dev/null; then
        echo "❌ Tag ${NEW_TAG} already exists."; exit 1
    fi

    # Lane gating (SV — Central quota, mirrors tatrman's justfile): only
    # server-libs ever touches Maven Central, and only a bare x.y.z version
    # reaches it — a prerelease suffix (e.g. 0.9.5-rc.1) stays on GH Packages
    # (see publish.yml). Image modules always go to GHCR only, regardless.
    if [ "$PREFIX" = "server-libs" ]; then
        if [[ "$NEW_VERSION" == *-* ]]; then
            TARGET_DESC="the 11 org.tatrman:* Maven libs → GH Packages ONLY (prerelease — Central step skipped)"
        else
            TARGET_DESC="the 11 org.tatrman:* Maven libs → GH Packages + Maven Central (PUBLIC — counts against Central quota)"
        fi
    fi

    echo "────────────────────────────────────────────────────────────"
    echo "  Latest released : ${LATEST}"
    echo "  New version      : ${NEW_VERSION}   →  tag ${NEW_TAG}"
    echo "  Commit           : $(git rev-parse --short HEAD) on ${BRANCH}"
    echo "  Publishes        : ${TARGET_DESC}"
    echo "  ⚠️  Published versions are PERMANENT — they cannot be deleted."
    echo "────────────────────────────────────────────────────────────"
    read -p "Create and push ${NEW_TAG}? [y/N] " -n 1 -r; echo ""
    [[ ${REPLY:-} =~ ^[Yy]$ ]] || { echo "❌ Aborting."; exit 1; }

    git tag -a "${NEW_TAG}" -m "Release ${NEW_VERSION}"
    git push origin "${NEW_TAG}"
    echo "✅ Pushed ${NEW_TAG} — the matching workflow will publish: ${TARGET_DESC}"
    echo "   Watch it: gh run watch  (or the repo's Actions tab)"
