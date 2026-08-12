# SPDX-License-Identifier: Apache-2.0
"""Write the OpenAPI document the frontend generates its client from.

    python scripts/dump_openapi.py frontend/openapi.json

**Why a committed file and not a live fetch.** The frontend's types are
generated from this document, and `npm run api` has to work on a laptop with no
database, no Postgres and no running service — which a `curl localhost:7290`
step does not. Committing it also makes the codegen input reviewable: a PR that
changes a response shape shows the change in the diff, next to the code that
caused it.

The file is kept honest by `tests/test_openapi.py`, which builds the document
from the app and fails if it differs. That is the whole guard: a backend change
that forgot the codegen step cannot merge silently, and the failure names the
command that fixes it.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from morph_studio.api import create_app  # noqa: E402
from morph_studio.config import Settings  # noqa: E402

#: The document must not depend on the deployment that produced it. This world
#: id appears nowhere in the schema — only in prose defaults — but pinning it
#: means two people running the script get byte-identical files.
SPEC_WORLD = "spec"


def document() -> dict:
    """The OpenAPI document, from the app with no database attached."""
    app = create_app(Settings(world=SPEC_WORLD, db_url="sqlite+pysqlite:///:memory:"))
    return app.openapi()


def main(argv: list[str]) -> int:
    target = Path(argv[1]) if len(argv) > 1 else Path("frontend/openapi.json")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(
        json.dumps(document(), indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    print(f"wrote {target}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
