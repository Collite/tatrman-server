# SPDX-License-Identifier: Apache-2.0
"""NLS-P9.1 T1/T4 — the morph enrichment queue (LM contracts §6, S-4).

Four things are under test and they are different in kind.

*The spool shape* is a contract with another service: morph-studio reads these
files, and `{world, token, verdict, context_span?, count, first_seen, last_seen}`
is written down in contracts §6. So the keys are asserted as literals rather
than round-tripped, because a round-trip passes happily when both sides are
wrong together.

*The dedup* is what makes the queue usable at all. The same unknown word in a
hundred sentences must be one row with `count: 100`, not a hundred rows an
editor has to page through.

*The span drop* is the S-4 privacy posture, and it is asserted on the BYTES —
what a world that said `spans: false` gets is a file with no `context_span` key
in it anywhere, not a reader that ignores the field.

*The `url:` sink's failure path* is the one that matters most and is easiest to
skip: a morph-studio that is down must cost latency in the enrichment loop and
nothing at all in the request that produced the miss, and every undelivered
report must still be there afterwards.
"""

from __future__ import annotations

import json
from datetime import UTC, datetime, timedelta

import pytest
from ttrnlp.morph import VERDICT_RESOLVED_WRONG

from nlp_service.config import MorphConfig, MorphQueueConfig, MorphWorldConfig
from nlp_service.morph_queue import (
    HttpSink,
    MemorySpool,
    NullSink,
    SpoolSink,
    build_sink,
    run_retention,
)

WORLD = "dfp"
NOW = datetime(2026, 8, 12, 9, 0, tzinfo=UTC)


class Clock:
    """A clock a test can move, so retention can be asserted without waiting."""

    def __init__(self, now: datetime = NOW):
        self.now = now

    def __call__(self) -> datetime:
        return self.now

    def advance(self, **kwargs) -> None:
        self.now += timedelta(**kwargs)


def worlds(spans: bool = False, retention_days: int = 90):
    return {WORLD: MorphWorldConfig(spans=spans, retention_days=retention_days)}


@pytest.fixture
def clock():
    return Clock()


@pytest.fixture
def spool(tmp_path, clock):
    return SpoolSink(tmp_path / "queue", worlds(), clock=clock)


def lines(directory, world: str = WORLD) -> list[dict]:
    path = directory / "queue" / f"{world}.jsonl"
    if not path.exists():
        return []
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]


# ── the spool shape ──────────────────────────────────────────────────────────


def test_a_report_lands_in_the_shape_contracts_6_spells_out(tmp_path, spool):
    assert spool.report(WORLD, "Kauflandu")
    spool.flush()

    (row,) = lines(tmp_path)
    assert row == {
        "world": "dfp",
        "token": "Kauflandu",
        "verdict": "miss",
        "count": 1,
        "first_seen": "2026-08-12T09:00:00+00:00",
        "last_seen": "2026-08-12T09:00:00+00:00",
    }


def test_the_hot_path_writes_nothing(tmp_path, spool):
    """`report` is a dict update. A ten-word sentence with nothing in the
    lexicon must not rewrite a file ten times inside one RunPipeline."""
    for token in "Porovnej tržby Kauflandu za loňský rok".split():
        spool.report(WORLD, token)
    assert lines(tmp_path) == []

    spool.flush()
    assert len(lines(tmp_path)) == 6


def test_one_file_per_world(tmp_path, clock):
    """Retention is world config, so one mixed file could not be swept by a
    policy that differs per world."""
    sink = SpoolSink(
        tmp_path / "queue",
        {
            "dfp": MorphWorldConfig(),
            "other": MorphWorldConfig(retention_days=7),
        },
        clock=clock,
    )
    sink.report("dfp", "a")
    sink.report("other", "b")
    sink.flush()

    assert [r["token"] for r in lines(tmp_path, "dfp")] == ["a"]
    assert [r["token"] for r in lines(tmp_path, "other")] == ["b"]


# ── dedup ────────────────────────────────────────────────────────────────────


def test_the_same_token_twice_is_one_line_with_a_count(tmp_path, spool, clock):
    spool.report(WORLD, "Kauflandu")
    clock.advance(hours=3)
    spool.report(WORLD, "Kauflandu")
    spool.flush()

    (row,) = lines(tmp_path)
    assert row["count"] == 2
    assert row["first_seen"] == "2026-08-12T09:00:00+00:00"
    assert row["last_seen"] == "2026-08-12T12:00:00+00:00"


def test_dedup_is_per_world_not_per_token(tmp_path, clock):
    sink = SpoolSink(
        tmp_path / "queue",
        {"dfp": MorphWorldConfig(), "other": MorphWorldConfig()},
        clock=clock,
    )
    sink.report("dfp", "Kauflandu")
    sink.report("other", "Kauflandu")
    sink.flush()
    assert lines(tmp_path, "dfp")[0]["count"] == 1
    assert lines(tmp_path, "other")[0]["count"] == 1


def test_a_human_verdict_outranks_a_later_automatic_miss(spool):
    """Somebody looked at an answer and said it was wrong. The next automatic
    miss for the same token must not quietly demote that back to "never seen"."""
    spool.report(WORLD, "má", "resolved_wrong")
    spool.report(WORLD, "má", "miss")
    (row,) = spool.pending()
    assert row.verdict == "resolved_wrong"


def test_the_spool_is_read_back_on_construction(tmp_path, clock):
    first = SpoolSink(tmp_path / "queue", worlds(), clock=clock)
    first.report(WORLD, "Kauflandu")
    first.flush()

    clock.advance(days=1)
    second = SpoolSink(tmp_path / "queue", worlds(), clock=clock)
    second.report(WORLD, "Kauflandu")
    second.flush()

    (row,) = lines(tmp_path)
    assert row["count"] == 2, "a restart must not start the count again"


def test_a_corrupt_line_costs_that_line_and_not_the_queue(tmp_path, clock):
    directory = tmp_path / "queue"
    directory.mkdir()
    (directory / f"{WORLD}.jsonl").write_text(
        '{"world": "dfp", "token": "ok", "count": 4}\n'
        "{ this is not json\n"
        '{"world": "dfp"}\n',
        encoding="utf-8",
    )
    sink = SpoolSink(directory, worlds(), clock=clock)
    assert [r.token for r in sink.pending()] == ["ok"]


def test_the_rewrite_is_atomic(tmp_path, spool):
    """A reader ingesting the spool must never meet a half-written file, and a
    killed process must leave the previous one intact rather than truncated."""
    spool.report(WORLD, "a")
    spool.flush()
    assert not list((tmp_path / "queue").glob("*.tmp"))


# ── S-4: the span ────────────────────────────────────────────────────────────


def test_a_token_only_world_never_writes_the_span_to_disk(tmp_path, spool):
    spool.report(WORLD, "Kauflandu", context_span="Porovnej tržby Kauflandu za rok")
    spool.flush()

    raw = (tmp_path / "queue" / f"{WORLD}.jsonl").read_text(encoding="utf-8")
    assert "context_span" not in raw
    assert "Porovnej" not in raw


def test_a_world_that_opted_in_keeps_it(tmp_path, clock):
    sink = SpoolSink(tmp_path / "queue", worlds(spans=True), clock=clock)
    sink.report(WORLD, "Kauflandu", context_span="tržby Kauflandu za rok")
    sink.flush()
    assert lines(tmp_path)[0]["context_span"] == "tržby Kauflandu za rok"


def test_spans_off_is_the_default(tmp_path):
    assert MorphWorldConfig().spans is False
    assert MorphWorldConfig().retention_days == 90


# ── worlds ───────────────────────────────────────────────────────────────────


def test_a_world_this_front_does_not_serve_is_refused(tmp_path, spool):
    assert spool.report("someone-else", "Kauflandu") is False
    spool.flush()
    assert not list((tmp_path / "queue").glob("*.jsonl"))


def test_an_empty_token_is_refused(spool):
    assert spool.report(WORLD, "") is False


# ── retention ────────────────────────────────────────────────────────────────


def test_the_sweep_drops_what_is_past_its_worlds_retention(tmp_path, spool, clock):
    spool.report(WORLD, "old")
    clock.advance(days=91)
    spool.report(WORLD, "new")
    spool.flush()

    assert spool.sweep() == 1
    assert [r["token"] for r in lines(tmp_path)] == ["new"]


def test_retention_is_read_per_world(tmp_path, clock):
    sink = SpoolSink(
        tmp_path / "queue",
        {
            "dfp": MorphWorldConfig(retention_days=90),
            "short": MorphWorldConfig(retention_days=7),
        },
        clock=clock,
    )
    sink.report("dfp", "a")
    sink.report("short", "b")
    clock.advance(days=30)
    assert sink.sweep() == 1
    assert [r.world for r in sink.pending()] == ["dfp"]


def test_a_report_with_no_readable_timestamp_is_kept(tmp_path, clock):
    directory = tmp_path / "queue"
    directory.mkdir()
    (directory / f"{WORLD}.jsonl").write_text(
        '{"world": "dfp", "token": "x", "last_seen": "whenever"}\n', encoding="utf-8"
    )
    sink = SpoolSink(directory, worlds(), clock=clock)
    assert sink.sweep() == 0
    assert [r.token for r in sink.pending()] == ["x"]


def test_retention_zero_means_keep_everything(tmp_path, clock):
    sink = SpoolSink(tmp_path / "queue", worlds(retention_days=0), clock=clock)
    sink.report(WORLD, "x")
    clock.advance(days=4000)
    assert sink.sweep() == 0


@pytest.mark.asyncio
async def test_the_timer_survives_a_sweep_that_raises():
    """A dead timer is a queue that grows forever in silence."""
    calls = []

    class Exploding(NullSink):
        def sweep(self):
            calls.append(1)
            if len(calls) < 3:
                raise RuntimeError("disk gone")
            raise SystemExit  # stops the loop for the test

    async def no_sleep(_seconds):
        return

    with pytest.raises(SystemExit):
        await run_retention(Exploding(), interval_seconds=1, sleep=no_sleep)
    assert len(calls) == 3


# ── the `url:` sink ──────────────────────────────────────────────────────────


class Recorder:
    """A sender that records, and can be told to fail."""

    def __init__(self, fail: bool = False):
        self.fail = fail
        self.calls: list[tuple[str, list[dict]]] = []

    async def __call__(self, endpoint: str, payload: list[dict]) -> None:
        self.calls.append((endpoint, payload))
        if self.fail:
            raise ConnectionError("morph-studio is down")


@pytest.mark.asyncio
async def test_a_delivered_report_leaves_the_spool(tmp_path, clock):
    sender = Recorder()
    sink = HttpSink(
        "http://morph-studio:8000",
        SpoolSink(tmp_path / "queue", worlds(), clock=clock),
        sender=sender,
    )
    sink.report(WORLD, "Kauflandu")
    sink.flush()

    assert await sink.deliver() == 1
    endpoint, payload = sender.calls[0]
    assert endpoint == "http://morph-studio:8000/ingest"
    assert payload[0]["token"] == "Kauflandu"
    assert sink.spool.pending() == []
    assert lines(tmp_path) == []


@pytest.mark.asyncio
async def test_an_undelivered_report_stays_spooled(tmp_path, clock):
    """The property the whole sink exists for: never lose a miss."""
    sink = HttpSink(
        "http://morph-studio:8000",
        SpoolSink(tmp_path / "queue", worlds(), clock=clock),
        sender=Recorder(fail=True),
    )
    sink.report(WORLD, "Kauflandu")
    sink.flush()
    await sink.drain()

    assert await sink.deliver() == 0
    assert [r.token for r in sink.spool.pending()] == ["Kauflandu"]
    assert [r["token"] for r in lines(tmp_path)] == ["Kauflandu"]


@pytest.mark.asyncio
async def test_a_failed_delivery_is_retried_from_the_spool(tmp_path, clock):
    spool = SpoolSink(tmp_path / "queue", worlds(), clock=clock)
    failing = Recorder(fail=True)
    HttpSink("http://x", spool, sender=failing)
    spool.report(WORLD, "Kauflandu")
    spool.flush()

    working = Recorder()
    revived = HttpSink("http://x", spool, sender=working)
    assert await revived.deliver() == 1


@pytest.mark.asyncio
async def test_delivery_never_blocks_the_caller(tmp_path, clock):
    """`flush` schedules; it does not await. A morph-studio taking ten seconds
    must not add ten seconds to the RunPipeline that produced the miss."""
    import asyncio

    started = asyncio.Event()

    async def slow(endpoint, payload):
        started.set()
        await asyncio.sleep(10)

    sink = HttpSink(
        "http://x", SpoolSink(tmp_path / "queue", worlds(), clock=clock), sender=slow
    )
    sink.report(WORLD, "Kauflandu")
    sink.flush()  # returns immediately, having scheduled the write
    await sink.drain()  # ...which `drain` is how a caller waits for

    assert [r["token"] for r in lines(tmp_path)] == ["Kauflandu"]
    await asyncio.wait_for(started.wait(), timeout=1)
    for task in asyncio.all_tasks():
        if task is not asyncio.current_task():
            task.cancel()


@pytest.mark.asyncio
async def test_flush_does_not_write_the_spool_on_the_event_loop(tmp_path, clock):
    """⚑ `Runner.run` flushes at the end of every pipeline run, from inside
    `async def RunPipeline`, and `chain.resolve` reports a miss for every
    non-lexicon answer — so this is one whole-file rewrite per Czech request.
    It must not happen on the loop that is serving the other requests."""
    import asyncio

    spool = SpoolSink(tmp_path / "queue", worlds(), clock=clock)
    spool.report(WORLD, "Kauflandu")
    spool.flush()

    # Nothing on disk yet: the write is on a worker thread, not this one.
    assert lines(tmp_path) == []
    await spool.drain()
    assert [r["token"] for r in lines(tmp_path)] == ["Kauflandu"]

    # And with no loop at all — a CLI, a sync test, shutdown — it is inline.
    spool.report(WORLD, "Kaufland")
    await asyncio.to_thread(spool.flush)
    assert len(lines(tmp_path)) == 2


@pytest.mark.asyncio
async def test_a_report_arriving_mid_delivery_is_not_dropped(tmp_path, clock):
    """⚑ `deliver` snapshots the payload, awaits the POST, then forgets what it
    sent. A miss recorded during that await was deleted unsent — and a
    `ReportToken` verdict destroyed after `accepted=true` was returned is the
    one outcome this sink's whole design is against."""
    import asyncio

    spool = SpoolSink(tmp_path / "queue", worlds(), clock=clock)
    released = asyncio.Event()

    async def slow(endpoint, payload):
        # The window: the POST is in flight and the front keeps serving.
        spool.report(WORLD, "Kauflandu", VERDICT_RESOLVED_WRONG)
        released.set()

    sink = HttpSink("http://x", spool, sender=slow)
    sink.report(WORLD, "Kauflandu")
    sink.flush()
    await sink.drain()

    assert await sink.deliver() == 1
    await released.wait()

    kept = sink.spool.pending()
    assert [r.token for r in kept] == ["Kauflandu"]
    assert kept[0].verdict == VERDICT_RESOLVED_WRONG


def test_a_url_sink_with_no_spool_dir_holds_them_in_memory(clock):
    sink = HttpSink("http://x", MemorySpool(worlds(), clock=clock), sender=Recorder())
    assert sink.report(WORLD, "Kauflandu")
    sink.flush()
    assert [r.token for r in sink.spool.pending()] == ["Kauflandu"]


# ── build_sink ───────────────────────────────────────────────────────────────


def test_none_is_a_sink_that_refuses_everything():
    sink = build_sink(MorphConfig(worlds=worlds()))
    assert isinstance(sink, NullSink)
    assert sink.enabled is False
    assert sink.report(WORLD, "Kauflandu") is False


def test_dir_builds_a_spool(tmp_path):
    sink = build_sink(
        MorphConfig(
            queue=MorphQueueConfig(sink=f"dir:{tmp_path}/q"), worlds=worlds()
        )
    )
    assert isinstance(sink, SpoolSink)
    assert sink.enabled is True


def test_url_builds_an_http_sink_over_a_spool(tmp_path):
    sink = build_sink(
        MorphConfig(
            queue=MorphQueueConfig(
                sink="url:http://morph-studio:8000", spool_dir=f"{tmp_path}/q"
            ),
            worlds=worlds(),
        )
    )
    assert isinstance(sink, HttpSink)
    assert isinstance(sink.spool, SpoolSink)
    assert sink.endpoint == "http://morph-studio:8000/ingest"


def test_an_address_that_already_names_ingest_is_not_doubled(tmp_path):
    sink = build_sink(
        MorphConfig(
            queue=MorphQueueConfig(sink="url:http://morph-studio:8000/ingest"),
            worlds=worlds(),
        )
    )
    assert sink.endpoint == "http://morph-studio:8000/ingest"


def test_the_wheels_miss_sink_shape_is_what_annotate_morph_calls(spool):
    """`annotate_morph(miss_sink=...)` calls `(world, token, verdict)`
    positionally. A signature mismatch here would surface as a TypeError inside
    a request, one token in."""
    spool.miss_sink()(WORLD, "Kauflandu", "miss")
    assert [r.token for r in spool.pending()] == ["Kauflandu"]
