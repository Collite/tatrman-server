#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""NLP evaluation harness - runs corpus against NLP service and computes per-engine metrics."""

from __future__ import annotations

import argparse
import json
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
    parser.add_argument("--corpus", default="eval/corpus/seed.jsonl", help="Corpus file path")
    parser.add_argument("--output-json", help="Output JSON metrics to file")
    parser.add_argument("--output-md", help="Output markdown report to file")
    args = parser.parse_args()

    corpus_path = Path(args.corpus)
    if not corpus_path.exists():
        print(f"Error: Corpus file not found: {corpus_path}")
        sys.exit(1)

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


if __name__ == "__main__":
    main()
