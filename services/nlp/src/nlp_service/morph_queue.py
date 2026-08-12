# SPDX-License-Identifier: Apache-2.0
"""The morph enrichment queue (LM contracts §6, S-4).

Every token the lexicon could not answer is a word the lexicon should learn, and
this is where it goes. The wheel's `annotate_morph` calls a `miss_sink` for each
one; `ReportToken` is the same event arriving from a consumer that saw a *wrong*
answer rather than a missing one. Both land here, and both are world-scoped:
**queues never cross worlds** (LM-5/S-4), because a world's vocabulary is the
world's own data and a shared queue is a data leak with an editorial UI on it.

Three sinks, chosen by one config line:

``none``
    Off. `ReportToken` answers ``accepted=false`` with ``LM-MORPH-007``, which
    is the honest answer for a sink that is not wired rather than an error the
    caller has to special-case.
``dir:<path>``
    A JSONL spool, one file per world, deduped on ``(world, token)`` — the
    shape LM contracts §6 spells out: ``{world, token, verdict, context_span?,
    count, first_seen, last_seen}``.
``url:<address>``
    POSTed to morph-studio's ``/ingest``. **With the spool underneath it**, not
    instead of it: a report that cannot be delivered is written to disk and
    retried, because the alternative is that a morph-studio restart costs every
    miss collected during it — and a miss nobody recorded is a word the lexicon
    never learns.

**Two properties this module is built around.**

*The hot path is a dict update.* `report()` never touches the disk or the
network. A ten-word Czech sentence with nothing in the lexicon would otherwise
rewrite a spool file ten times inside one `RunPipeline`. The write happens once,
in `flush()`, which the pipeline runner calls after the run and the `ReportToken`
servicer calls on its single event. The cost of that choice is bounded and worth
naming: a crash between a report and its flush loses at most one request's
misses, and the word is reported again the next time anyone types it.

*Retention is world config, so retention is per-world files.* One mixed file
cannot be swept by a policy that differs per world, and the sweep is not
optional — a queue is a pile of user-typed tokens, and 90 days is the default
because somebody has to say a number.

**The span is dropped here, not filtered later.** `spans: false` is the default
for every world (S-4), and a world that says so never has a ``context_span``
written to disk at all. Dropping it at the sink rather than at the reader is the
difference between a privacy posture and a privacy intention.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Callable, Iterable, Mapping

from ttrnlp.morph import VERDICT_MISS, VERDICT_RESOLVED_WRONG

from nlp_service.config import (
    SINK_DIR_PREFIX,
    SINK_NONE,
    SINK_URL_PREFIX,
    MorphConfig,
    MorphWorldConfig,
)

logger = logging.getLogger(__name__)

#: The verdicts a queue entry may carry (LM contracts §6). `miss` is the only
#: one the chain itself emits; `resolved_wrong` is a human's judgement arriving
#: through `ReportToken`.
VERDICTS = frozenset({VERDICT_MISS, VERDICT_RESOLVED_WRONG})

#: The endpoint a `url:` sink posts to (LM contracts §7).
INGEST_PATH = "/ingest"

_SPOOL_SUFFIX = ".jsonl"


def _now() -> datetime:
    return datetime.now(UTC)


def _stamp(moment: datetime) -> str:
    return moment.astimezone(UTC).isoformat(timespec="seconds")


def _parse_stamp(text: str) -> datetime | None:
    try:
        parsed = datetime.fromisoformat(text)
    except (TypeError, ValueError):
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=UTC)


@dataclass
class Report:
    """One queue entry — LM contracts §6, field for field.

    `count` is why this is a record and not an append: the same unknown word in
    a hundred sentences is one thing to curate, ranked by how much it costs, not
    a hundred lines to page through. `first_seen`/`last_seen` are what make the
    retention sweep possible and what tell an editor whether a word is a spike
    or a habit.
    """

    world: str
    token: str
    verdict: str = VERDICT_MISS
    count: int = 1
    first_seen: str = ""
    last_seen: str = ""
    context_span: str = ""

    @property
    def key(self) -> tuple[str, str]:
        return (self.world, self.token)

    def as_dict(self) -> dict:
        """The spool line. `context_span` is present only when it is real —
        contracts §6 writes it `context_span?` and a world that opted out must
        not leave an empty field behind implying it once opted in."""
        payload = {
            "world": self.world,
            "token": self.token,
            "verdict": self.verdict,
            "count": self.count,
            "first_seen": self.first_seen,
            "last_seen": self.last_seen,
        }
        if self.context_span:
            payload["context_span"] = self.context_span
        return payload

    @classmethod
    def from_dict(cls, payload: Mapping) -> "Report | None":
        """Read one spool line back, or `None` if it is not one.

        A spool is a file on a volume other things can touch. A half-written
        line from a killed process must cost that line and not the queue.
        """
        world = str(payload.get("world") or "")
        token = str(payload.get("token") or "")
        if not world or not token:
            return None
        try:
            count = int(payload.get("count", 1))
        except (TypeError, ValueError):
            count = 1
        return cls(
            world=world,
            token=token,
            verdict=str(payload.get("verdict") or VERDICT_MISS),
            count=max(count, 1),
            first_seen=str(payload.get("first_seen") or ""),
            last_seen=str(payload.get("last_seen") or ""),
            context_span=str(payload.get("context_span") or ""),
        )


class QueueSink:
    """The interface the pipeline and the servicer hold.

    `report` returns whether the report was accepted — the exact bit
    `ReportTokenResponse.accepted` carries.
    """

    enabled: bool = False

    def report(
        self,
        world: str,
        token: str,
        verdict: str = VERDICT_MISS,
        *,
        context_span: str = "",
    ) -> bool:
        raise NotImplementedError

    def flush(self) -> None:
        """Persist whatever `report` accumulated. Cheap when nothing changed."""

    def sweep(self) -> int:
        """Drop entries past their world's retention. Returns how many."""
        return 0

    def close(self) -> None:
        self.flush()

    # A miss sink in the wheel's shape: `(world, token, verdict)`, positional.
    def miss_sink(self) -> Callable[[str, str, str], None]:
        def sink(world: str, token: str, verdict: str) -> None:
            self.report(world, token, verdict)

        return sink


class NullSink(QueueSink):
    """No sink configured. Every report is refused, and that is the answer."""

    enabled = False

    def report(
        self,
        world: str,
        token: str,
        verdict: str = VERDICT_MISS,
        *,
        context_span: str = "",
    ) -> bool:
        return False


class SpoolSink(QueueSink):
    """The `dir:` sink: JSONL per world, deduped, swept, atomically rewritten."""

    enabled = True

    def __init__(
        self,
        directory: str | Path,
        worlds: Mapping[str, MorphWorldConfig],
        *,
        clock: Callable[[], datetime] = _now,
    ):
        self._dir = Path(directory)
        self._worlds = dict(worlds)
        self._clock = clock
        self._reports: dict[tuple[str, str], Report] = {}
        self._dirty: set[str] = set()
        self._read()

    # ---- reading ----------------------------------------------------------

    def _path(self, world: str) -> Path:
        # `validate_morph` already refused a world id that is not filename-safe;
        # this is the second lock on the same door, because the id reaching here
        # from `ReportToken` came off the wire and the check above is what says
        # it matched a configured world at all.
        return self._dir / f"{world}{_SPOOL_SUFFIX}"

    def _read(self) -> None:
        if not self._dir.is_dir():
            return
        for path in sorted(self._dir.glob(f"*{_SPOOL_SUFFIX}")):
            for line in path.read_text(encoding="utf-8").splitlines():
                line = line.strip()
                if not line:
                    continue
                try:
                    payload = json.loads(line)
                except json.JSONDecodeError:
                    logger.warning("morph spool %s: skipping unreadable line", path)
                    continue
                report = Report.from_dict(payload) if isinstance(payload, dict) else None
                if report is None:
                    logger.warning("morph spool %s: skipping malformed entry", path)
                    continue
                self._reports[report.key] = report

    def pending(self) -> list[Report]:
        """Everything currently spooled, oldest first-seen first."""
        return sorted(self._reports.values(), key=lambda r: (r.first_seen, r.token))

    def world_config(self, world: str) -> MorphWorldConfig | None:
        return self._worlds.get(world)

    # ---- writing ----------------------------------------------------------

    def report(
        self,
        world: str,
        token: str,
        verdict: str = VERDICT_MISS,
        *,
        context_span: str = "",
    ) -> bool:
        policy = self._worlds.get(world)
        if policy is None:
            # LM-MORPH-007's second half. A report for a world this deployment
            # does not serve is not an error to fix here — it is a caller
            # pointed at the wrong front, or a world that has not been declared
            # yet — and spooling it would put one world's tokens in another
            # world's file, which is the one thing S-4 forbids outright.
            return False
        if not token:
            return False
        if not policy.spans:
            context_span = ""

        moment = _stamp(self._clock())
        existing = self._reports.get((world, token))
        if existing is None:
            self._reports[(world, token)] = Report(
                world=world,
                token=token,
                verdict=verdict,
                count=1,
                first_seen=moment,
                last_seen=moment,
                context_span=context_span,
            )
        else:
            existing.count += 1
            existing.last_seen = moment
            # A `resolved_wrong` outranks a `miss` and never the other way
            # round: somebody looked at an answer and said it was wrong, and the
            # next automatic miss for the same token must not quietly demote
            # that judgement back to "we have never seen this word".
            if verdict == VERDICT_RESOLVED_WRONG:
                existing.verdict = verdict
            if context_span and not existing.context_span:
                existing.context_span = context_span
        self._dirty.add(world)
        return True

    def flush(self) -> None:
        for world in sorted(self._dirty):
            self._write(world)
        self._dirty.clear()

    def _write(self, world: str) -> None:
        rows = [r for r in self.pending() if r.world == world]
        path = self._path(world)
        if not rows:
            path.unlink(missing_ok=True)
            return
        self._dir.mkdir(parents=True, exist_ok=True)
        body = "".join(
            json.dumps(row.as_dict(), ensure_ascii=False, sort_keys=False) + "\n"
            for row in rows
        )
        # Atomic rewrite: a reader (morph-studio ingesting the spool) must never
        # see a half-written file, and a killed process must leave the previous
        # one intact rather than a truncated one.
        tmp = path.with_name(path.name + ".tmp")
        tmp.write_text(body, encoding="utf-8")
        os.replace(tmp, path)

    def sweep(self) -> int:
        """Drop entries whose `last_seen` is past their world's retention."""
        now = self._clock()
        dropped = 0
        for key, report in list(self._reports.items()):
            policy = self._worlds.get(report.world)
            if policy is None or policy.retention_days <= 0:
                continue
            last = _parse_stamp(report.last_seen)
            if last is None:
                # No usable timestamp: keep it and say so. Deleting a report
                # because its clock field is unreadable throws away the one
                # thing the queue exists to hold.
                logger.warning(
                    "morph spool: %r in world %r has no readable last_seen — "
                    "kept, and it will never be swept until it is reported again",
                    report.token,
                    report.world,
                )
                continue
            if now - last > timedelta(days=policy.retention_days):
                del self._reports[key]
                self._dirty.add(report.world)
                dropped += 1
        if dropped:
            self.flush()
        return dropped

    def drop(self, keys: Iterable[tuple[str, str]]) -> None:
        """Forget delivered reports (the `url:` sink's acknowledgement)."""
        for key in keys:
            report = self._reports.pop(key, None)
            if report is not None:
                self._dirty.add(report.world)
        self.flush()


class MemorySpool(SpoolSink):
    """A `url:` sink with no `spool_dir`: dedup and retry, but no disk.

    Legitimate on a dev box and wrong on a cluster, which is why
    `validate_morph` warns about it rather than accepting it silently.
    """

    def __init__(
        self,
        worlds: Mapping[str, MorphWorldConfig],
        *,
        clock: Callable[[], datetime] = _now,
    ):
        super().__init__(Path("/nonexistent"), worlds, clock=clock)

    def _read(self) -> None:
        return

    def _write(self, world: str) -> None:
        return


#: Deliver a batch. Async because the real one is an HTTP POST and the whole
#: point is that it never runs on the hot path; injectable because a unit suite
#: that needed a listening morph-studio would not be a unit suite.
Sender = Callable[[str, list[dict]], "asyncio.Future | object"]


class HttpSink(QueueSink):
    """The `url:` sink: spool first, POST after, never lose a report.

    The order is the contract. `report()` writes into the spool and returns
    accepted; delivery happens later, from a background task, and only a
    *delivered* report is dropped from the spool. A morph-studio that is down,
    slow, or being redeployed costs latency in the enrichment loop and nothing
    at all in the request that produced the miss.
    """

    enabled = True

    def __init__(
        self,
        address: str,
        spool: SpoolSink,
        *,
        sender: Sender | None = None,
        timeout_seconds: float = 5.0,
    ):
        self._address = address.rstrip("/")
        self._spool = spool
        self._sender = sender or _http_sender(timeout_seconds)
        self._task: asyncio.Task | None = None

    @property
    def endpoint(self) -> str:
        if self._address.endswith(INGEST_PATH):
            return self._address
        return f"{self._address}{INGEST_PATH}"

    @property
    def spool(self) -> SpoolSink:
        return self._spool

    def report(
        self,
        world: str,
        token: str,
        verdict: str = VERDICT_MISS,
        *,
        context_span: str = "",
    ) -> bool:
        return self._spool.report(world, token, verdict, context_span=context_span)

    def flush(self) -> None:
        self._spool.flush()
        self._schedule()

    def sweep(self) -> int:
        return self._spool.sweep()

    def _schedule(self) -> None:
        """Kick delivery onto the loop, if there is one and it is idle.

        No loop (a synchronous test, a CLI) means the reports stay spooled until
        something asks; a delivery already in flight means this one is a no-op,
        because the next `deliver` reads the spool afresh and will pick up
        whatever arrived meanwhile.
        """
        try:
            loop = asyncio.get_running_loop()
        except RuntimeError:
            return
        if self._task is not None and not self._task.done():
            return
        self._task = loop.create_task(self.deliver())

    async def deliver(self) -> int:
        """POST everything spooled. Returns how many reports were delivered."""
        pending = self._spool.pending()
        if not pending:
            return 0
        payload = [report.as_dict() for report in pending]
        try:
            await self._sender(self.endpoint, payload)
        except Exception as exc:  # noqa: BLE001 — every failure means "keep them"
            logger.warning(
                "morph queue: %d report(s) could not be delivered to %s (%s) — "
                "they stay spooled and will be retried",
                len(payload),
                self.endpoint,
                exc,
            )
            return 0
        self._spool.drop(report.key for report in pending)
        logger.info(
            "morph queue: delivered %d report(s) to %s", len(payload), self.endpoint
        )
        return len(payload)

    def close(self) -> None:
        self._spool.flush()


def _http_sender(timeout_seconds: float) -> Sender:
    async def send(endpoint: str, payload: list[dict]) -> None:
        import httpx

        async with httpx.AsyncClient(timeout=timeout_seconds) as client:
            response = await client.post(endpoint, json={"reports": payload})
            response.raise_for_status()

    return send


def build_sink(
    config: MorphConfig,
    *,
    clock: Callable[[], datetime] = _now,
    sender: Sender | None = None,
) -> QueueSink:
    """The sink `morph.queue.sink` names. `validate_morph` already vetted it."""
    sink = config.queue.sink.strip()
    if not sink or sink == SINK_NONE:
        return NullSink()
    if sink.startswith(SINK_DIR_PREFIX):
        return SpoolSink(
            sink[len(SINK_DIR_PREFIX) :].strip(), config.worlds, clock=clock
        )
    if sink.startswith(SINK_URL_PREFIX):
        address = sink[len(SINK_URL_PREFIX) :].strip()
        spool_dir = config.queue.spool_dir.strip()
        spool: SpoolSink = (
            SpoolSink(spool_dir, config.worlds, clock=clock)
            if spool_dir
            else MemorySpool(config.worlds, clock=clock)
        )
        return HttpSink(address, spool, sender=sender)
    # Unreachable through `validate_morph`; reachable through a hand-built
    # config in a test, and a silently-off queue is worse than a loud one.
    raise ValueError(f"unknown morph queue sink {sink!r}")


async def run_retention(
    sink: QueueSink, *, interval_seconds: int, sleep=asyncio.sleep
) -> None:
    """The daily sweep (LM contracts §6). Runs until cancelled.

    Boot's own sweep is not here — it happens once, synchronously, where the
    sink is built, because a service that was down for longer than the retention
    window must not serve a stale queue for a day first.
    """
    while True:
        await sleep(interval_seconds)
        try:
            dropped = sink.sweep()
        except Exception:  # noqa: BLE001
            # A sweep that raises must not kill the timer: the next one may well
            # succeed, and a dead timer is a queue that grows forever in silence.
            logger.exception("morph queue retention sweep failed")
            continue
        if dropped:
            logger.info("morph queue retention: dropped %d expired report(s)", dropped)


__all__ = [
    "INGEST_PATH",
    "VERDICTS",
    "HttpSink",
    "MemorySpool",
    "NullSink",
    "QueueSink",
    "Report",
    "SpoolSink",
    "build_sink",
    "run_retention",
]
