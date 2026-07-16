# tatrman-server — task runner
# Run `just` to list recipes.
#
# Recipe conventions (synced across kantheon/modeler/tatrman/tatrman-server,
# 2026-07-16 — see project/ for the cross-repo decision):
#   lint / build / test    bare = everything; `just build veles` (name) or
#                           `just build services/veles` (path) = one module.
#   lint-kt/build-kt/test-kt, lint-py/build-py/test-py   same name/path/bare
#                           rules, scoped to one language lane.
#   publish                 unified release entry point — see its own doc comment.

set shell := ["bash", "-uc"]

# The 22 container-image modules release-image.yml builds (per-module `<module>/v*`
# tag → ghcr.io/collite/<module>). Kept in lockstep with release-image.yml's map.
image_modules := "veles ttr-query ttr-translate ttr-validate ttr-dispatch ttr-fuzzy ttr-llm-gateway ttr-nlp chrono geo money ttr-grounding-mcp ttr-resolver ttr-meta-mcp ttr-query-mcp ttr-fuzzy-mcp ttr-nlp-mcp ttr-worker-postgres ttr-worker-mssql ttr-worker-polars ttr-identity health"

# List available recipes
default:
    @just --list

# ── Module resolution ─────────────────────────────────────────────────────────

# Resolve a bare module name to its on-disk path, searching services/ workers/
# tools/ infra/ shared/libs/{kotlin,python}/. A path (contains "/") passes
# through unchanged.
_resolve name:
    #!/usr/bin/env bash
    set -euo pipefail
    if [[ "{{name}}" == *"/"* ]]; then
        echo "{{name}}"
        exit 0
    fi
    roots=()
    for d in services workers tools infra shared/libs/kotlin shared/libs/python; do
        [ -d "$d" ] && roots+=("$d")
    done
    path=$(find "${roots[@]}" -mindepth 1 -maxdepth 1 -type d -name "{{name}}" -print -quit 2>/dev/null || true)
    if [ -z "$path" ]; then
        echo "❌ Module '{{name}}' not found under services/, workers/, tools/, infra/, shared/libs/{kotlin,python}/" >&2
        exit 1
    fi
    echo "$path"

# Which lane a resolved path builds under (kt | py).
_lang path:
    #!/usr/bin/env bash
    set -euo pipefail
    if [ -f "{{path}}/build.gradle.kts" ]; then echo kt
    elif [ -f "{{path}}/pyproject.toml" ]; then echo py
    else
        echo "❌ Can't tell what language {{path}} is (no build.gradle.kts / pyproject.toml)" >&2
        exit 1
    fi

# ── lint / build / test — bare = everything, name/path = one module ───────────

# Lint everything (Kotlin + Python). One module: `just lint veles`.
lint module="":
    #!/usr/bin/env bash
    set -euo pipefail
    if [ -z "{{module}}" ]; then
        just lint-kt
        just lint-py
        exit 0
    fi
    path=$(just _resolve "{{module}}")
    lang=$(just _lang "$path")
    case "$lang" in
        kt) just lint-kt "$path" ;;
        py) just lint-py "$path" ;;
    esac

# Build everything (Kotlin + Python). One module: `just build veles`.
build module="":
    #!/usr/bin/env bash
    set -euo pipefail
    if [ -z "{{module}}" ]; then
        just build-kt
        just build-py
        exit 0
    fi
    path=$(just _resolve "{{module}}")
    lang=$(just _lang "$path")
    case "$lang" in
        kt) just build-kt "$path" ;;
        py) just build-py "$path" ;;
    esac

# Test everything (Kotlin + Python). One module: `just test veles`.
test module="":
    #!/usr/bin/env bash
    set -euo pipefail
    if [ -z "{{module}}" ]; then
        just test-kt
        just test-py
        exit 0
    fi
    path=$(just _resolve "{{module}}")
    lang=$(just _lang "$path")
    case "$lang" in
        kt) just test-kt "$path" ;;
        py) just test-py "$path" ;;
    esac

# ── Kotlin lane ──────────────────────────────────────────────────────────────

# ktlint check across every module, or one: `just lint-kt` / `just lint-kt veles`.
# Autofix: `just fmt` (whole repo) — see below.
lint-kt module="":
    #!/usr/bin/env bash
    set -euo pipefail
    if [ -z "{{module}}" ]; then ./gradlew ktlintCheck
    else
        path=$(just _resolve "{{module}}")
        gradle_path=":$(echo "$path" | sed 's|/|:|g')"
        ./gradlew "${gradle_path}:ktlintCheck"
    fi

# ktlint autoformat (whole repo — no single-module form; matches the old `fmt`).
fmt:
    ./gradlew ktlintFormat

# Full Gradle build (compile + the mocked `test` gate + ktlint — what CI runs),
# or one module: `just build-kt` / `just build-kt veles` / `just build-kt services/veles`.
build-kt module="":
    #!/usr/bin/env bash
    set -euo pipefail
    if [ -z "{{module}}" ]; then ./gradlew build
    else
        path=$(just _resolve "{{module}}")
        gradle_path=":$(echo "$path" | sed 's|/|:|g')"
        ./gradlew "${gradle_path}:build"
    fi

# Mocked unit/PR gate only (no ktlint, no componentTest), or one module.
test-kt module="":
    #!/usr/bin/env bash
    set -euo pipefail
    if [ -z "{{module}}" ]; then ./gradlew test
    else
        path=$(just _resolve "{{module}}")
        gradle_path=":$(echo "$path" | sed 's|/|:|g')"
        ./gradlew "${gradle_path}:test"
    fi

# The real-dependency (Testcontainers) component tier — separate from `test`.
test-component:
    ./gradlew componentTest

# ── Python lane (uv + ruff + pytest) — services/ttr-nlp, workers/ttr-worker-polars ─

# ruff lint every Python module, or one: `just lint-py` / `just lint-py ttr-nlp`.
lint-py module="":
    #!/usr/bin/env bash
    set -euo pipefail
    if [ -z "{{module}}" ]; then
        for d in services/ttr-nlp workers/ttr-worker-polars; do just lint-py "$d"; done
        exit 0
    fi
    path=$(just _resolve "{{module}}")
    cd "$path" && uv run ruff check .

# Resolve + install the frozen lock (what the Dockerfile's `uv sync --frozen`
# does) for every Python module, or one.
build-py module="":
    #!/usr/bin/env bash
    set -euo pipefail
    if [ -z "{{module}}" ]; then
        for d in services/ttr-nlp workers/ttr-worker-polars; do just build-py "$d"; done
        exit 0
    fi
    path=$(just _resolve "{{module}}")
    cd "$path" && uv sync --frozen

# pytest every Python module, or one; trailing args pass through, e.g.
# `just test-py workers/ttr-worker-polars -m component`.
test-py module="" *args:
    #!/usr/bin/env bash
    set -euo pipefail
    if [ -z "{{module}}" ]; then
        for d in services/ttr-nlp workers/ttr-worker-polars; do just test-py "$d" {{args}}; done
        exit 0
    fi
    path=$(just _resolve "{{module}}")
    cd "$path" && uv run pytest {{args}}

# ── Conformance (RG-P6.S2 — the three-tier instrument) ───────────────────────

# The grounding eval corpus (RG-P3.S2.T7): consolidate the per-service goldens
# (chrono/geo/money) + the hand-authored supplemental into the bulk + e2e corpora.
eval-grounding-build:
    cd eval/grounding && python3 build_corpus.py

# Grounding HERMETIC tier: corpus-validity + the pure report logic. No deployed
# stack, no live service — the only network touch is a one-time install of the
# PINNED test deps (requirements-test.txt, RG-P6 review H), skipped once installed.
eval-grounding-test:
    #!/usr/bin/env bash
    set -euo pipefail
    cd eval/grounding
    test -d .venv || python3 -m venv .venv
    # Install only when the pinned marker is absent, and always from the pinned file
    # so a gate run is reproducible (no unpinned/floating pytest).
    if ! .venv/bin/python -c 'import pytest, pytest_asyncio' 2>/dev/null; then
        .venv/bin/pip -q install -r requirements-test.txt
    fi
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
    just conformance-verify-hashes
    ./gradlew \
      :services:ttr-resolver:test --tests '*Q20ParityTest*' \
        --tests '*CallsSeedConformanceTest*' --tests '*RefusalOverGuessConformanceTest*' \
      :services:ttr-fuzzy:test --tests '*MatchQualityCorpusTest*'
    just eval-grounding-test

# Pin the three-tier corpora by content hash (RG-P6 review I): the recorded provenance
# in conformance/README.md is now ENFORCED — a silent corpus edit (even whitespace /
# reordering that a semantic test would miss) fails the gate here.
conformance-verify-hashes:
    #!/usr/bin/env bash
    set -euo pipefail
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum -c conformance/corpus-hashes.sha256
    else
        shasum -a 256 -c conformance/corpus-hashes.sha256
    fi

# ── publish — unified release entry point ───────────────────────────────────────
#
# Tags the repo; the matching GitHub Actions workflow does the actual
# build+publish when it sees the tag — publish.yml for `bundle server-libs`
# (Maven), release-image.yml for everything else (GHCR container images).
#
# Internal targets (GH Packages staging / GHCR) get EVERY tag. The external
# target (Maven Central, `bundle server-libs` only — container images have no
# external registry) only fires when the tag is marked RELEASE — a published
# RELEASE version is ALWAYS the bare `x.y.z` (the `-RELEASE` marker is stripped
# before it ever reaches a registry; see publish.yml). This is the 2026-07-16
# change: previously bare tags went public and `-rc` suffixes stayed internal —
# inverted, because internal patches vastly outnumber real releases, and a
# release now needs to be marked explicitly.
#
# `what`: one of the 17 image modules (by name or path — veles, ttr-query,
#   ttr-translate, ttr-validate, ttr-dispatch, ttr-fuzzy, ttr-llm-gateway,
#   ttr-nlp, ttr-meta-mcp, ttr-query-mcp, ttr-fuzzy-mcp, ttr-nlp-mcp,
#   ttr-worker-postgres, ttr-worker-mssql, ttr-worker-polars, ttr-identity,
#   health — GHCR only, RELEASE accepted for interface uniformity but changes
#   nothing), or `bundle server-libs` (the 11-module Maven library set — GH
#   Packages always, + Maven Central on RELEASE).
#
# Usage:
#   just publish veles                          # internal (GHCR), patch bump
#   just publish veles set 0.9.2                 # internal, explicit version
#   just publish services/ttr-query patch          # image module, path form
#   just publish bundle server-libs                 # internal, patch bump
#   just publish bundle server-libs release set 0.9.2  # + Maven Central, explicit
publish *args:
    #!/usr/bin/env bash
    set -euo pipefail

    ARGS=({{args}})
    WHAT="${ARGS[0]:-}"
    NEXT=1
    if [ "$WHAT" = "bundle" ]; then
        WHAT="bundle ${ARGS[1]:-}"
        NEXT=2
    fi
    if [ -z "$WHAT" ] || [ "$WHAT" = "bundle " ]; then
        echo "❌ Usage: just publish <module|path|bundle server-libs> [release] [major|minor|patch|set VERSION]" >&2
        exit 1
    fi
    REST=("${ARGS[@]:$NEXT}")

    RELEASE=false
    if [ "${REST[0]:-}" = "release" ]; then
        RELEASE=true
        REST=("${REST[@]:1}")
    fi
    LEVEL="${REST[0]:-patch}"
    CUSTOM_VERSION="${REST[1]:-}"

    case "$LEVEL" in
        major|minor|patch|set) ;;
        *) echo "❌ Level must be 'major', 'minor', 'patch', or 'set'."; exit 1 ;;
    esac
    if [ "$LEVEL" = "set" ] && [ -z "$CUSTOM_VERSION" ]; then
        echo "❌ 'set' requires a version. E.g. just publish $WHAT set 0.9.2"; exit 1
    fi

    # Resolve WHAT -> tag PREFIX + human description.
    if [ "$WHAT" = "bundle server-libs" ]; then
        PREFIX=server-libs
        DESC="the 11 org.tatrman:* Maven libs"
    else
        MOD_PATH=$(just _resolve "$WHAT")
        MOD_NAME=$(basename "$MOD_PATH")
        if ! echo " {{image_modules}} " | grep -q " $MOD_NAME "; then
            echo "❌ '$MOD_NAME' ($MOD_PATH) is not a publishable image module." >&2
            echo "   Valid: {{image_modules}}" >&2
            exit 1
        fi
        PREFIX="$MOD_NAME"
        DESC="the ${MOD_NAME} container image → ghcr.io/collite/${MOD_NAME}"
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

    # Single version line per prefix — internal and RELEASE tags share it (a
    # RELEASE tag always mints a brand-new number, never reuses one already spent
    # by an internal tag), so a stripped RELEASE version never collides with an
    # already-published internal one on the same registry.
    LATEST=$(git tag -l "${PREFIX}/v*" | sed -E "s|^${PREFIX}/v||; s/-RELEASE\$//" | grep -E '^[0-9]+\.[0-9]+\.[0-9]+$' | sort -V | tail -n 1 || true)
    LATEST="${LATEST:-0.0.0}"

    if [ "$LEVEL" = "set" ]; then
        if ! [[ "$CUSTOM_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
            echo "❌ '$CUSTOM_VERSION' is not a valid bare semver (X.Y.Z) — RELEASE markers are added automatically, don't include one."; exit 1
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

    if git rev-parse -q --verify "refs/tags/${PREFIX}/v${NEW_VERSION}" >/dev/null || \
       git rev-parse -q --verify "refs/tags/${PREFIX}/v${NEW_VERSION}-RELEASE" >/dev/null; then
        echo "❌ Version ${NEW_VERSION} already used (as a bare or RELEASE tag) for ${PREFIX}."; exit 1
    fi

    NEW_TAG="${PREFIX}/v${NEW_VERSION}"
    [ "$RELEASE" = true ] && NEW_TAG="${NEW_TAG}-RELEASE"

    if [ "$WHAT" = "bundle server-libs" ]; then
        if [ "$RELEASE" = true ]; then
            LANES="GH Packages (internal) + Maven Central (PUBLIC — counts against Central quota) — published as bare ${NEW_VERSION}"
        else
            LANES="GH Packages (internal) ONLY — not marked RELEASE, no Central step runs"
        fi
    else
        LANES="ghcr.io/collite/${PREFIX}:${NEW_VERSION} (internal registry — no external lane exists for images)"
    fi

    echo "────────────────────────────────────────────────────────────"
    echo "  Latest published : ${LATEST}"
    echo "  New version      : ${NEW_VERSION}   →  tag ${NEW_TAG}"
    echo "  Commit           : $(git rev-parse --short HEAD) on ${BRANCH}"
    echo "  Publishes        : ${DESC}"
    echo "  Lanes            : ${LANES}"
    echo "  ⚠️  Published registry versions are PERMANENT — they cannot be deleted."
    echo "────────────────────────────────────────────────────────────"
    read -p "Create and push ${NEW_TAG}? [y/N] " -n 1 -r; echo ""
    [[ ${REPLY:-} =~ ^[Yy]$ ]] || { echo "❌ Aborting."; exit 1; }

    git tag -a "${NEW_TAG}" -m "Release ${NEW_VERSION}"
    git push origin "${NEW_TAG}"
    echo "✅ Pushed ${NEW_TAG} — the matching workflow will publish: ${LANES}"
    echo "   Watch it: gh run watch  (or the repo's Actions tab)"
