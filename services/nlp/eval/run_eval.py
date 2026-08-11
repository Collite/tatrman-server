#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""NLP evaluation harness.

Two modes, two questions.

**Default** — runs `eval/corpus/seed.jsonl` through `Analyze` and computes
per-engine token/lemma/POS/NER metrics over REST. That is the RG-P1 question:
which engine reads Czech better.

**`--rules`** (NLS-P4.T3) — runs `eval/corpus/rules.jsonl` through `RunPipeline`
over gRPC and scores what the rule packs actually produced: the `QueryPattern`'s
query id, and every declared parameter, exact-match. That is a different question
and deliberately a harsher one. A pack is not "mostly right": a query id that is
almost the right id routes to nothing, and a parameter that is almost the right
span queries for the wrong customer.

**Decoys are half the corpus and carry the weight.** A rules corpus of positive
cases only would be passed by a pack that fires on everything, which is the most
likely way for a pack to be wrong — an over-broad LHS matches the hero AND every
sentence near it, and the hero test alone would stay green. So a `decoy` case
asserts that NO QueryPattern was produced, and it counts as a failure when one
was.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import httpx


@dataclass
class EvalEntry:
    """Single evaluation entry from corpus."""
    id: str
    question: str
    lang: str
    expected: dict


@dataclass
class EngineMetrics:
    """Metrics for a single engine."""
    name: str
    token_f1: float = 0.0
    lemma_accuracy: float = 0.0
    pos_f1: float = 0.0
    ner_f1: float = 0.0
    errors: int = 0
    total: int = 0


@dataclass
class SpanAlignResult:
    """Result of aligning tokens by character offsets."""
    aligned: int
    ambiguous: int
    unaligned: int


def load_corpus(path: Path) -> list[EvalEntry]:
    """Load evaluation corpus from JSONL file."""
    entries = []
    with path.open() as f:
        for line in f:
            data = json.loads(line)
            entries.append(EvalEntry(
                id=data["id"],
                question=data["question"],
                lang=data["lang"],
                expected=data["expected"],
            ))
    return entries


def analyze_text(
    base_url: str,
    text: str,
    language: str,
    ops: set[str],
    mode: str = "COMPARE",
    timeout: int = 60,
) -> dict[str, Any]:
    """Call NLP service and return response."""
    payload = {
        "text": text,
        "language": language,
        "ops": list(ops),
        "mode": mode,
        "engineHints": {},
    }

    try:
        with httpx.Client(timeout=timeout) as client:
            resp = client.post(f"{base_url}/v1/analyze", json=payload)
            if resp.status_code == 200:
                return resp.json()
            else:
                return {"error": f"HTTP {resp.status_code}"}
    except Exception as e:
        return {"error": str(e)}


def compute_token_metrics(
    expected_tokens: list[dict],
    actual_tokens: list[dict],
) -> tuple[float, float]:
    """Compute token F1 and lemma accuracy.

    Uses character-offset alignment when tokenization differs.
    Returns (token_f1, lemma_accuracy).
    """
    if not expected_tokens and not actual_tokens:
        return 1.0, 1.0
    if not expected_tokens or not actual_tokens:
        return 0.0, 0.0

    # Build expected token spans
    expected_spans = []
    for tok in expected_tokens:
        cs = tok.get("charStart", tok.get("char_start", -1))
        ce = tok.get("charEnd", tok.get("char_end", -1))
        if cs >= 0 and ce > cs:
            expected_spans.append({
                "text": tok["text"],
                "lemma": tok.get("lemma", ""),
                "upos": tok.get("upos", ""),
                "char_start": cs,
                "char_end": ce,
            })

    # Build actual token spans
    actual_spans = []
    for tok in actual_tokens:
        cs = tok.get("charStart", tok.get("char_start", -1))
        ce = tok.get("charEnd", tok.get("char_end", -1))
        if cs >= 0 and ce > cs:
            actual_spans.append({
                "text": tok["text"],
                "lemma": tok.get("lemma", ""),
                "upos": tok.get("upos", ""),
                "char_start": cs,
                "char_end": ce,
            })

    # Align by character offsets
    expected_aligned = []
    actual_aligned = []

    for es in expected_spans:
        matching = [a for a in actual_spans
                   if a["char_start"] == es["char_start"] and a["char_end"] == es["char_end"]]
        if matching:
            expected_aligned.append(es)
            actual_aligned.append(matching[0])
        else:
            # No exact span match - try best effort
            best_match = None
            best_overlap = 0
            for a in actual_spans:
                # Check if spans overlap
                overlap_start = max(es["char_start"], a["char_start"])
                overlap_end = min(es["char_end"], a["char_end"])
                if overlap_end > overlap_start:
                    overlap = overlap_end - overlap_start
                    if overlap > best_overlap:
                        best_overlap = overlap
                        best_match = a
            if best_match and best_overlap > 0:
                expected_aligned.append(es)
                actual_aligned.append(best_match)

    # Compute token F1
    tp = sum(1 for e, a in zip(expected_aligned, actual_aligned)
            if e["text"] == a["text"])
    fp = len(actual_aligned) - tp
    fn = len(expected_aligned) - tp
    precision = tp / (tp + fp) if (tp + fp) > 0 else 0.0
    recall = tp / (tp + fn) if (tp + fn) > 0 else 0.0
    f1 = 2 * precision * recall / (precision + recall) if (precision + recall) > 0 else 0.0

    # Compute lemma accuracy (exact match on aligned tokens)
    lemma_matches = sum(1 for e, a in zip(expected_aligned, actual_aligned)
                       if e["lemma"].lower() == a["lemma"].lower())
    lemma_accuracy = lemma_matches / len(expected_aligned) if expected_aligned else 0.0

    return f1, lemma_accuracy


def compute_pos_metrics(
    expected_tokens: list[dict],
    actual_tokens: list[dict],
) -> float:
    """Compute UD POS F1 using aligned tokens."""
    if not expected_tokens and not actual_tokens:
        return 1.0
    if not expected_tokens or not actual_tokens:
        return 0.0

    expected_spans = []
    for tok in expected_tokens:
        cs = tok.get("charStart", tok.get("char_start", -1))
        ce = tok.get("charEnd", tok.get("char_end", -1))
        if cs >= 0 and ce > cs:
            expected_spans.append({
                "upos": tok.get("upos", ""),
                "char_start": cs,
                "char_end": ce,
            })

    actual_spans = []
    for tok in actual_tokens:
        cs = tok.get("charStart", tok.get("char_start", -1))
        ce = tok.get("charEnd", tok.get("char_end", -1))
        if cs >= 0 and ce > cs:
            actual_spans.append({
                "upos": tok.get("upos", ""),
                "char_start": cs,
                "char_end": ce,
            })

    # Align by character offsets
    expected_aligned = []
    actual_aligned = []

    for es in expected_spans:
        matching = [a for a in actual_spans
                   if a["char_start"] == es["char_start"] and a["char_end"] == es["char_end"]]
        if matching:
            expected_aligned.append(es)
            actual_aligned.append(matching[0])
        else:
            best_match = None
            best_overlap = 0
            for a in actual_spans:
                overlap_start = max(es["char_start"], a["char_start"])
                overlap_end = min(es["char_end"], a["char_end"])
                if overlap_end > overlap_start:
                    overlap = overlap_end - overlap_start
                    if overlap > best_overlap:
                        best_overlap = overlap
                        best_match = a
            if best_match and best_overlap > 0:
                expected_aligned.append(es)
                actual_aligned.append(best_match)

    # Compute POS F1
    tp = sum(1 for e, a in zip(expected_aligned, actual_aligned)
            if e["upos"] == a["upos"])
    fp = len(actual_aligned) - tp
    fn = len(expected_aligned) - tp
    precision = tp / (tp + fp) if (tp + fp) > 0 else 0.0
    recall = tp / (tp + fn) if (tp + fn) > 0 else 0.0
    f1 = 2 * precision * recall / (precision + recall) if (precision + recall) > 0 else 0.0

    return f1


def compute_ner_metrics(
    expected_entities: list[dict],
    actual_entities: list[dict],
) -> float:
    """Compute NER span F1."""
    if not expected_entities and not actual_entities:
        return 1.0
    if not expected_entities or not actual_entities:
        return 0.0

    tp = 0
    fp = 0
    fn = 0

    matched_expected = set()
    matched_actual = set()

    for i, exp_ent in enumerate(expected_entities):
        exp_start = exp_ent.get("charStart", exp_ent.get("char_start", -1))
        exp_end = exp_ent.get("charEnd", exp_ent.get("char_end", -1))
        exp_label = exp_ent.get("label", "")

        for j, act_ent in enumerate(actual_entities):
            if j in matched_actual:
                continue
            act_start = act_ent.get("charStart", act_ent.get("char_start", -1))
            act_end = act_ent.get("charEnd", act_ent.get("char_end", -1))
            act_label = act_ent.get("label", "")

            if exp_start == act_start and exp_end == act_end and exp_label == act_label:
                tp += 1
                matched_expected.add(i)
                matched_actual.add(j)
                break

    fn = len(expected_entities) - len(matched_expected)
    fp = len(actual_entities) - len(matched_actual)

    precision = tp / (tp + fp) if (tp + fp) > 0 else 0.0
    recall = tp / (tp + fn) if (tp + fn) > 0 else 0.0
    f1 = 2 * precision * recall / (precision + recall) if (precision + recall) > 0 else 0.0

    return f1


def run_evaluation(
    base_url: str,
    corpus_path: Path,
    engines: list[str] | None = None,
) -> dict[str, Any]:
    """Run evaluation on corpus and return per-engine metrics."""
    entries = load_corpus(corpus_path)

    # Track metrics per engine
    engine_metrics: dict[str, EngineMetrics] = {}
    all_engine_names: set[str] = set()

    for entry in entries:
        print(f"Evaluating {entry.id}: {entry.question[:50]}...")

        response = analyze_text(base_url, entry.question, entry.lang, {"NER"})

        if "error" in response:
            print(f"  Error: {response['error']}")
            continue

        # Get all engines that returned results
        by_engine = response.get("byEngine", {})
        all_engine_names.update(by_engine.keys())

        # Initialize metrics for new engines
        for eng_name in by_engine.keys():
            if eng_name not in engine_metrics:
                engine_metrics[eng_name] = EngineMetrics(name=eng_name)

        # Evaluate each engine
        expected = entry.expected
        expected_tokens = expected.get("tokens", [])
        expected_entities = expected.get("entities", [])

        for eng_name, eng_result in by_engine.items():
            metrics = engine_metrics[eng_name]
            metrics.total += 1

            if eng_result.get("error"):
                metrics.errors += 1
                continue

            actual_tokens = eng_result.get("tokens", [])
            actual_entities = eng_result.get("entities", [])

            # Compute metrics
            token_f1, lemma_acc = compute_token_metrics(expected_tokens, actual_tokens)
            pos_f1 = compute_pos_metrics(expected_tokens, actual_tokens)
            ner_f1 = compute_ner_metrics(expected_entities, actual_entities)

            # Running average
            n = metrics.total - metrics.errors
            if n > 0:
                metrics.token_f1 = (metrics.token_f1 * (n - 1) + token_f1) / n
                metrics.lemma_accuracy = (metrics.lemma_accuracy * (n - 1) + lemma_acc) / n
                metrics.pos_f1 = (metrics.pos_f1 * (n - 1) + pos_f1) / n
                metrics.ner_f1 = (metrics.ner_f1 * (n - 1) + ner_f1) / n

    # Build result summary
    summary = {
        "corpus_size": len(entries),
        "engines": {},
    }

    for eng_name, metrics in sorted(engine_metrics.items()):
        n = metrics.total - metrics.errors
        summary["engines"][eng_name] = {
            "token_f1": round(metrics.token_f1, 4),
            "lemma_accuracy": round(metrics.lemma_accuracy, 4),
            "pos_f1": round(metrics.pos_f1, 4),
            "ner_f1": round(metrics.ner_f1, 4),
            "errors": metrics.errors,
            "total": metrics.total,
            "evaluated": n,
        }

    return summary


def generate_markdown_report(summary: dict[str, Any], output_path: Path | None = None) -> str:
    """Generate markdown summary report."""
    lines = [
        "# NLP Engine Evaluation Report",
        "",
        f"**Corpus size:** {summary['corpus_size']} questions",
        "",
        "## Per-Engine Metrics",
        "",
        "| Engine | Token F1 | Lemma Acc | POS F1 | NER F1 | Errors | Total |",
        "|--------|---------|-----------|--------|--------|--------|-------|",
    ]

    for eng_name, metrics in summary["engines"].items():
        lines.append(
            f"| {eng_name} | {metrics['token_f1']:.4f} | {metrics['lemma_accuracy']:.4f} "
            f"| {metrics['pos_f1']:.4f} | {metrics['ner_f1']:.4f} | "
            f"{metrics['errors']} | {metrics['total']} |"
        )

    lines.extend([
        "",
        "## Metrics Explanation",
        "",
        "- **Token F1**: Tokenization accuracy using character-offset alignment",
        "- **Lemma Accuracy**: Exact lemma match rate on aligned tokens",
        "- **POS F1**: UD POS tag F1 on aligned tokens",
        "- **NER F1**: Named entity span F1",
    ])

    report = "\n".join(lines)

    if output_path:
        output_path.write_text(report)
        print(f"Report written to {output_path}")

    return report


def main():
    parser = argparse.ArgumentParser(description="Run NLP evaluation harness")
    parser.add_argument("--url", default="http://localhost:8080", help="NLP service base URL")
    # The two lanes speak different protocols, so they need different addresses.
    # `--url` is a REST base URL and `--target` is a gRPC `host:port`; handing
    # the former to `grpc.aio.insecure_channel` resolves "http://localhost:8080"
    # as a DNS name and every case fails before the front is even contacted.
    parser.add_argument(
        "--target",
        default="localhost:7271",
        help="gRPC host:port of the nlp front (the --rules lane; NOT a URL)",
    )
    parser.add_argument(
        "--lane",
        default=os.getenv("NLP_LANE", ""),
        help="lane label for the report (default: $NLP_LANE). The front decides "
        "its own lane — this only records which one the run was against.",
    )
    parser.add_argument("--corpus", default="", help="Corpus file path")
    parser.add_argument("--output-json", help="Output JSON metrics to file")
    parser.add_argument("--output-md", help="Output markdown report to file")
    parser.add_argument(
        "--rules",
        action="store_true",
        help="score rule packs through RunPipeline (gRPC) instead of engines "
        "through Analyze (REST) — NLS-P4.T3",
    )
    args = parser.parse_args()

    default_corpus = "eval/corpus/rules.jsonl" if args.rules else "eval/corpus/seed.jsonl"
    corpus_path = Path(args.corpus or default_corpus)
    if not corpus_path.exists():
        print(f"Error: Corpus file not found: {corpus_path}")
        sys.exit(1)

    if args.rules:
        return _main_rules(args, corpus_path)

    print(f"Running evaluation on {corpus_path} against {args.url}")
    summary = run_evaluation(args.url, corpus_path)

    # Output JSON
    print("\n=== Summary ===")
    print(json.dumps(summary, indent=2))

    if args.output_json:
        Path(args.output_json).write_text(json.dumps(summary, indent=2))
        print(f"JSON metrics written to {args.output_json}")

    # Generate markdown report
    if args.output_md:
        generate_markdown_report(summary, Path(args.output_md))
    else:
        print("\n" + generate_markdown_report(summary))


# ── the rules lane (NLS-P4.T3) ───────────────────────────────────────────────


@dataclass
class RuleCase:
    """One line of `rules.jsonl`."""

    id: str
    kind: str  # hero | paraphrase | decoy
    text: str
    lang: str
    pipeline: str
    expected_query: str | None
    expected_params: dict
    agent: str = ""
    note: str = ""

    @property
    def is_decoy(self) -> bool:
        return self.expected_query is None


@dataclass
class RuleOutcome:
    case: RuleCase
    produced_query: str | None = None
    produced_params: dict = None  # type: ignore[assignment]
    passed: bool = False
    reason: str = ""
    messages: list = None  # type: ignore[assignment]


def load_rule_corpus(path: Path) -> list[RuleCase]:
    cases = []
    with path.open(encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            raw = json.loads(line)
            expected = raw.get("expected") or {}
            cases.append(
                RuleCase(
                    id=raw["id"],
                    kind=raw.get("kind", "hero"),
                    text=raw["text"],
                    lang=raw.get("lang", ""),
                    pipeline=raw.get("pipeline", "query-patterns"),
                    expected_query=expected.get("query"),
                    expected_params=expected.get("params") or {},
                    agent=raw.get("agent", ""),
                    note=raw.get("note", ""),
                )
            )
    return cases


def score_rule_case(case: RuleCase, patterns: list[dict], messages: list) -> RuleOutcome:
    """Exact match on the query id and every declared parameter.

    Deliberately unforgiving. A query id that is almost right routes to nothing,
    and a parameter span that is almost right queries for the wrong customer —
    there is no partial credit available downstream, so there is none here.
    """
    outcome = RuleOutcome(case=case, produced_params={}, messages=messages)

    if case.is_decoy:
        if patterns:
            outcome.reason = (
                f"decoy matched: produced {[p.get('query') for p in patterns]}"
            )
            return outcome
        outcome.passed = True
        return outcome

    if not patterns:
        outcome.reason = "no QueryPattern produced"
        return outcome
    if len(patterns) > 1:
        # Not a near-miss: two answers to one question means the consumer has to
        # choose, and nothing downstream is equipped to.
        outcome.reason = f"{len(patterns)} QueryPatterns produced, expected 1"
        return outcome

    (produced,) = patterns
    outcome.produced_query = produced.get("query")
    outcome.produced_params = {
        key: value for key, value in produced.items() if key != "query"
    }

    if outcome.produced_query != case.expected_query:
        outcome.reason = (
            f"query {outcome.produced_query!r} != expected {case.expected_query!r}"
        )
        return outcome

    for name, expected_value in case.expected_params.items():
        actual = outcome.produced_params.get(name)
        if actual != expected_value:
            outcome.reason = f"param {name}: {actual!r} != expected {expected_value!r}"
            return outcome

    outcome.passed = True
    return outcome


def run_rules_evaluation(target: str, corpus_path: Path, *, lane: str = "") -> dict[str, Any]:
    """Run every case through `RunPipeline` and score it.

    `target` is a gRPC `host:port` — this lane does not use the REST mirror,
    which has no pipeline surface by design (NL-16).
    """
    import asyncio

    from ttrnlp.client.grpc import NlpClient

    cases = load_rule_corpus(corpus_path)

    async def run_all() -> list[RuleOutcome]:
        outcomes = []
        async with NlpClient(target) as client:
            for case in cases:
                print(f"Evaluating {case.id}: {case.text[:50]}...")
                result = await client.run_pipeline(
                    case.text, case.pipeline, language=case.lang
                )
                patterns = [
                    dict(a.features)
                    for a in result.document.annset("").with_type("QueryPattern")
                ]
                outcomes.append(
                    score_rule_case(
                        case,
                        patterns,
                        [
                            {"code": m.code, "severity": m.severity, "message": m.message}
                            for m in result.messages
                        ],
                    )
                )
        return outcomes

    outcomes = asyncio.run(run_all())
    return summarize_rules(outcomes, lane=lane)


def summarize_rules(outcomes: list[RuleOutcome], *, lane: str = "") -> dict[str, Any]:
    by_kind: dict[str, dict[str, int]] = {}
    for outcome in outcomes:
        bucket = by_kind.setdefault(outcome.case.kind, {"passed": 0, "total": 0})
        bucket["total"] += 1
        bucket["passed"] += int(outcome.passed)

    passed = sum(1 for o in outcomes if o.passed)
    return {
        "mode": "rules",
        "lane": lane,
        "total": len(outcomes),
        "passed": passed,
        "failed": len(outcomes) - passed,
        "by_kind": by_kind,
        "cases": [
            {
                "id": o.case.id,
                "kind": o.case.kind,
                "passed": o.passed,
                "reason": o.reason,
                "expected_query": o.case.expected_query,
                "produced_query": o.produced_query,
                "produced_params": o.produced_params,
                "messages": [m["code"] for m in (o.messages or [])],
            }
            for o in outcomes
        ],
    }


def generate_rules_report(summary: dict[str, Any], output_path: Path | None = None) -> str:
    lines = [
        "# Rule-pack evaluation",
        "",
        f"**{summary['passed']}/{summary['total']} passed**"
        + (f" · lane `{summary['lane']}`" if summary["lane"] else ""),
        "",
        "| kind | passed | total |",
        "|---|---|---|",
    ]
    for kind, counts in sorted(summary["by_kind"].items()):
        lines.append(f"| {kind} | {counts['passed']} | {counts['total']} |")

    lines += ["", "| case | kind | result | detail |", "|---|---|---|---|"]
    for case in summary["cases"]:
        mark = "✅" if case["passed"] else "❌"
        detail = case["reason"] or (case["produced_query"] or "—")
        lines.append(f"| `{case['id']}` | {case['kind']} | {mark} | {detail} |")

    codes = sorted({c for case in summary["cases"] for c in case["messages"]})
    if codes:
        lines += ["", f"Diagnostics observed: {', '.join(f'`{c}`' for c in codes)}"]

    report = "\n".join(lines) + "\n"
    if output_path:
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(report, encoding="utf-8")
        print(f"Report written to {output_path}")
    return report


def _main_rules(args, corpus_path: Path) -> None:
    print(f"Running RULE evaluation on {corpus_path} against {args.target}")
    summary = run_rules_evaluation(args.target, corpus_path, lane=args.lane)

    print("\n=== Summary ===")
    print(json.dumps(summary, indent=2))

    if args.output_json:
        path = Path(args.output_json)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(summary, indent=2), encoding="utf-8")
        print(f"JSON metrics written to {path}")

    if args.output_md:
        generate_rules_report(summary, Path(args.output_md))
    else:
        print("\n" + generate_rules_report(summary))

    if summary["failed"]:
        sys.exit(1)


if __name__ == "__main__":
    main()
