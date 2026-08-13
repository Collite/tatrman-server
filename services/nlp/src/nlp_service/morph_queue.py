# SPDX-License-Identifier: Apache-2.0
"""The morph enrichment queue (LM contracts §6, S-4).

Every token the lexicon could not answer is a word the lexicon should learn, and
this is where it goes. The wheel's `annotate_morph` calls a `miss_sink` for each
one; `ReportToken` is the same event arriving from a consumer that saw a *wrong*
answer rather than a missing one. Both land here, and both are world-scoped:
**queues never cross worlds** (LM-5/S-4), because a world's vocabulary is the
world's own data and a shared queue is a data leak with an editorial UI on it.

**⚑ This queue is a collection point, not an ownership claim — LM-10 routing is
decided studio-side, by design.** Every non-lexicon answer is reported to the
one world this front serves, including ordinary common nouns that LM-10 will
route to the *core* analytical lexicon. That is not the routing rule leaking:
`route()` runs in `morph_studio.store.apply_cascade`, once, when the miss is
ingested — and it has to, because routing needs what the front does not have.
It needs a proposal (which part of speech the guesser reached) and it needs the
world's model vocabulary; the front has neither, and giving it both would mean
shipping the cascade into the query path to answer a question nobody asks until
curation.

So a token's presence in this spool says "this front could not answer it", never
"this belongs to this world". The layer is decided later, it is stamped on the
queue item as `layer`/`routed_by`, and a human can overrule it (`reroute`).
Read `ttrmorph.enrich.cascade.route` for the rule itself.

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
from typing import Callable, Iterable, Mapping, Sequence

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

    async def drain(self) -> None:
        """Wait for whatever `flush` started to reach the disk.

        `flush` is deliberately non-blocking on an event loop (see
        `SpoolSink.flush`), which leaves callers who need the *file* — a test
        asserting the spool's bytes, a shutdown path, a handler that has just
        promised a caller `accepted=true` — with nothing to wait on. This is it.
        """

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
        #: The in-flight offloaded write, if any (`flush`/`_drain`).
        self._writer: asyncio.Task | None = None
        self._read()

    # ---- reading ----------------------------------------------------------

    def _path(self, world: str) -> Path:
        # `validate_morph` already refused a world id that is not filename-safe;
        # this is the second lock on the same door, because the id reaching here
        # from `ReportToken` came off the wire and the check above is what says
        # it matched a configured world at all.
        return self._dir / f"{world}{_SPOOL_SUFFIX}"

    def _read(self) -> None:
        """Load the spool — this deployment's worlds, and no others.

        ⚑ By declared world, not by glob. A spool directory is a volume, and
        volumes are shared and outlive the config that made them: remove a world
        from `morph.worlds`, or point two fronts at one PVC, and a glob picks up
        the other world's file. Every consequence of that is a boundary
        violation. The tokens get POSTed to *this* front's studio — one world's
        user-typed vocabulary delivered into another world's editorial queue,
        which is the leak S-4 forbids outright — and `drop()` then unlinks the
        file, so the world that owned it loses its queue as well.

        `report()` has always refused an undeclared world (`LM-MORPH-007`). This
        is the same rule applied on the way in, where it was missing.
        """
        if not self._dir.is_dir():
            return
        for world in sorted(self._worlds):
            path = self._path(world)
            if not path.is_file():
                continue
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
                if report.world != world:
                    # The filename is the world. A row claiming another one is a
                    # hand-edited or misrouted file, and honouring the field over
                    # the name is how a single line moves a token between worlds.
                    logger.warning(
                        "morph spool %s: entry claims world %r — skipped (the "
                        "file name is the world, and a queue never crosses one)",
                        path,
                        report.world,
                    )
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
        """Persist the dirty worlds — off the event loop when there is one.

        ⚑ `Runner.run` calls this at the end of *every* pipeline run, from
        inside `async def RunPipeline`, and `chain.resolve` reports a miss for
        every answer the lexicon did not give — which on a Czech deployment is
        most of them. A full re-serialise of the spool plus a blocking
        `write_text` and `os.replace`, on the loop, once per request, is a stall
        every other request in flight pays for.

        So: when a loop is running the rows are snapshotted here (they must be —
        `report()` mutates them from this same thread) and the encode-and-write
        goes to a worker thread. With no loop — a test, a CLI, `close()` on the
        way down — it happens inline exactly as before. The file write itself is
        unchanged, and `_dirty` is cleared only once the bytes have landed, so a
        dropped task costs a retry and never a report.
        """
        if not self._dirty:
            return
        try:
            loop = asyncio.get_running_loop()
        except RuntimeError:
            self._flush_now()
            return
        if self._writer is None or self._writer.done():
            self._writer = loop.create_task(self._drain())

    def _flush_now(self) -> None:
        for world in sorted(self._dirty):
            self._write(world)
        self._dirty.clear()

    async def drain(self) -> None:
        writer = self._writer
        if writer is not None and not writer.done():
            await writer
        # `_drain` loops until nothing is dirty, so anything still marked here
        # is a write that failed and logged. Retrying inline gives the caller a
        # definite answer either way rather than a silently empty file.
        #
        # ⛑⛑ And the retry is GUARDED, because `RunPipeline` awaits this before
        # it answers (`grpc_server.py`). Unguarded, a spool directory the process
        # cannot write — a volume that did not mount, a read-only filesystem, an
        # image whose `/var/lib/nlp` is root-owned — came out of the retry as a
        # `PermissionError` and turned **every Czech query into UNKNOWN**. Found
        # on the arc-gate-7 stack (NLS-P9.3 T7), where it was the whole failure:
        # the lexicon was loaded, the answer was computed, and the request died
        # writing a record of what it had missed.
        #
        # The queue is a SIDE EFFECT of answering. Losing a miss is a cost —
        # the word is reported again the next time somebody types it — and
        # losing the answer is a fault. `_drain` already took this posture; the
        # inline path had simply not been given it.
        if self._dirty:
            try:
                self._flush_now()
            except Exception:  # noqa: BLE001 — never at the answer's expense
                logger.exception(
                    "morph spool: could not write %s — the reports stay in "
                    "memory and the next flush retries. The pipeline's answer "
                    "is unaffected",
                    ", ".join(sorted(self._dirty)),
                )

    async def _drain(self) -> None:
        """Write dirty worlds until there are none, one batch at a time.

        Coalescing rather than one task per flush: a second flush arriving while
        this one is in the thread leaves its worlds marked and is picked up by
        the loop below, so writes for one world can never overlap or land out of
        order — which for a whole-file rewrite would mean an older spool
        overwriting a newer one.
        """
        while self._dirty:
            worlds = sorted(self._dirty)
            self._dirty.clear()
            # Snapshotted on this thread, encoded on the other: `as_dict` copies,
            # so the worker never touches a `Report` that `report()` may be
            # mutating underneath it, nor iterates `_reports` while it grows.
            batch = [(world, self._snapshot(world)) for world in worlds]
            try:
                await asyncio.to_thread(self._put_all, batch)
            except Exception:  # noqa: BLE001 — a failed write must not lose rows
                self._dirty.update(worlds)
                logger.exception(
                    "morph spool: could not write %s — the reports stay in "
                    "memory and the next flush retries",
                    ", ".join(worlds),
                )
                return

    def _snapshot(self, world: str) -> list[dict] | None:
        """This world's spool as plain dicts, or None if it has no rows left."""
        rows = [r.as_dict() for r in self.pending() if r.world == world]
        return rows or None

    def _write(self, world: str) -> None:
        self._put(world, self._snapshot(world))

    def _put_all(self, batch: Sequence[tuple[str, list[dict] | None]]) -> None:
        for world, rows in batch:
            self._put(world, rows)

    def _put(self, world: str, rows: list[dict] | None) -> None:
        """Encode and write one world's file. Runs in a worker thread."""
        path = self._path(world)
        if rows is None:
            path.unlink(missing_ok=True)
            return
        self._dir.mkdir(parents=True, exist_ok=True)
        body = "".join(
            json.dumps(row, ensure_ascii=False, sort_keys=False) + "\n" for row in rows
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

    def drop_delivered(self, delivered: Iterable[tuple[tuple[str, str], int, str]]) -> int:
        """`drop`, but only for reports that have not moved since they were sent.

        ⚑ Delivery awaits an HTTP POST, and `report()` mutates the stored
        `Report` **in place** — bumping `count`, moving `last_seen`, upgrading a
        `miss` to `resolved_wrong`. Dropping by key afterwards therefore deleted
        whatever arrived during that await, unsent: a `ReportToken` verdict a
        person typed could be acknowledged with `accepted=true` and then
        destroyed a few milliseconds later, which is the one outcome this
        module's "never lose a report" contract is written against.

        Compared on `(count, verdict)` rather than a timestamp because those are
        exactly the fields a concurrent `report()` changes, and `last_seen` has
        one-second resolution — too coarse to notice the window it would be
        checking for.

        Returns how many were kept back for the next delivery.
        """
        kept = 0
        for key, count, verdict in delivered:
            report = self._reports.get(key)
            if report is None:
                continue
            if report.count != count or report.verdict != verdict:
                kept += 1
                continue
            del self._reports[key]
            self._dirty.add(report.world)
        self.flush()
        return kept

    def close(self) -> None:
        # Inline, whatever the loop is doing: a write scheduled onto a loop that
        # is about to stop is a spool file that never gets written, and shutdown
        # is precisely when the spool has to be on disk.
        self._flush_now()


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

    def _put(self, world: str, rows: list[dict] | None) -> None:
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

    async def drain(self) -> None:
        await self._spool.drain()

    def _schedule(self) -> None:
        """Kick delivery onto the loop, if there is one and it is idle.

        No loop (a synchronous test, a CLI) means the reports stay spooled until
        something asks; a delivery already in flight means this one is a no-op,
        because `deliver` loops until the spool is empty and will pick up
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
        """POST everything spooled, until nothing new is left.

        ⛑⛑ **The loop is the fix, and `_schedule` depends on it.** A report that
        arrives while a POST is in flight finds `_schedule` a no-op — the task is
        not done — so if `deliver` returned after one round that report would sit
        spooled until the *next* pipeline run happened to flush, which on a quiet
        deployment is however long until somebody types another unknown word.

        Found by the arc-gate-7 walk (NLS-P9.3 T7), where it was ~50% flaky and
        looked like nothing at all: the FIRST word learned always arrived
        promptly, and the second one — reported while the first one's delivery
        and the overlay reload it triggers were still in flight — took three more
        queries to turn up. `_schedule`'s docstring already described this loop;
        only the loop was missing.

        Rounds are bounded by what has been attempted, not by a count: a report
        `drop_delivered` legitimately KEEPS (it changed while in flight) must not
        make this spin.
        """
        delivered = 0
        attempted: set = set()
        while True:
            pending = [
                report for report in self._spool.pending() if report.key not in attempted
            ]
            if not pending:
                return delivered
            attempted.update(report.key for report in pending)
            payload = [report.as_dict() for report in pending]
            # What was sent, as values rather than as the mutable rows themselves
            # — see `drop_delivered`. Taken here, before the await, because after
            # it the objects may no longer describe what went over the wire.
            sent = [(report.key, report.count, report.verdict) for report in pending]
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
                return delivered
            kept = self._spool.drop_delivered(sent)
            delivered += len(payload)
            logger.info(
                "morph queue: delivered %d report(s) to %s", len(payload), self.endpoint
            )
            if kept:
                logger.debug(
                    "morph queue: %d report(s) changed while in flight and stay "
                    "spooled for the next delivery",
                    kept,
                )

    def close(self) -> None:
        self._spool.close()


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
