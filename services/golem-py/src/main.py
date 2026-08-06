# SPDX-License-Identifier: Apache-2.0
"""golem-py's entry point — one turn, from the command line.

⚑ The OS Golem has **no server surface of its own** at RV-P4, and that is deliberate
rather than unfinished: the conversation door is the platform's (Iris ⇄ the Kotlin
Golem), and what P4 ships is the LOOP — the thing both shells run. A gRPC/HTTP front
here would be a second door nobody has specified.

So the image is a CLI: it drives one turn against a live core and prints the outcome as
JSON. That is exactly what the P4.4 live drill needs, and it keeps the service honest
about what it is.

    uv run python src/main.py --question "Zobraz tržby za rok 2025" --core localhost:7276
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "generated"))

from golem_py.core_client import DEFAULT_TARGET, LiveCore  # noqa: E402
from golem_py.deps import Deps  # noqa: E402
from golem_py.gate_client import LiveGate  # noqa: E402
from golem_py.graph import run_turn  # noqa: E402
from golem_py.ladder import LadderConfig, load_default  # noqa: E402
from golem_py.state import ResolutionState  # noqa: E402


def _parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(prog="golem-py", description=__doc__)
    parser.add_argument("--question", required=True, help="the user's question")
    parser.add_argument("--locale", default="cs")
    parser.add_argument("--core", default=DEFAULT_TARGET, help="resolver host:port")
    parser.add_argument("--conversation-id", default="golem-py-cli")
    parser.add_argument("--profile", default="CHAT_QUICK")
    parser.add_argument("--subject", default="", help="OBO caller subject")
    parser.add_argument(
        "--ladder",
        default=None,
        help="path to a golem-ladder/v1 file (default: the shipped zero-rung config)",
    )
    parser.add_argument("--verbose", action="store_true")
    return parser.parse_args(argv)


async def _run(args: argparse.Namespace) -> int:
    ladder = LadderConfig.load(args.ladder) if args.ladder else load_default()
    deps = Deps(core=LiveCore(args.core), gate=LiveGate(args.core), ladder=ladder)
    state = ResolutionState(
        question=args.question,
        locale=args.locale,
        conversation_id=args.conversation_id,
        profile=args.profile,
        caller_subject=args.subject,
    )
    output = await run_turn(state, deps)
    print(
        json.dumps(
            {"type": type(output).__name__, **output.model_dump(mode="json")},
            indent=2,
            ensure_ascii=False,
        )
    )
    return 0


def main(argv: list[str] | None = None) -> int:
    args = _parse_args(argv)
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(levelname)s %(message)s",
    )
    return asyncio.run(_run(args))


if __name__ == "__main__":
    raise SystemExit(main())
