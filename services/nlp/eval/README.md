# nlp Evaluation

This directory contains the evaluation infrastructure for the nlp NLP service
(formerly Kadmos; forked from ai-platform `infra/nlp/eval`, unchanged — same
harness + corpus, so the kantheon results equal the ai-platform original's at the
fork point).

## Corpus

The corpus lives in `corpus/seed.jsonl` (50 hand-curated Czech questions with expected
parses and entity bindings), carried over verbatim from the ai-platform original.

### Adding New Fixtures

1. Add a JSON line to `corpus/seed.jsonl`
2. Ensure `charStart`/`charEnd` are correct byte offsets
3. Include at least tokens and entities

## Running Evaluation

```bash
# Against local K3s deployment (port-forwards the nlp pod on 7270)
just eval-nlp

# Against a specific URL (e.g. a local `uv run` instance on 7270)
uv run python eval/run_eval.py --url http://localhost:7270

# With output files
uv run python eval/run_eval.py \
    --url http://localhost:7270 \
    --output-json eval/reports/metrics.json \
    --output-md eval/reports/report.md

# The rule-pack lane (NLS-P4.T3). It talks gRPC, so it takes `--target`, a bare
# host:port — NOT `--url`, which is the REST mirror's base URL and would be
# handed to insecure_channel as a DNS name.
uv run python eval/run_eval.py --rules \
    --target localhost:7271 \
    --lane option \
    --output-json eval/reports/metrics.json \
    --output-md eval/reports/report.md
```

`--target` defaults to `localhost:7271` and `--lane` to `$NLP_LANE`; the lane is
a label for the report only — the front decides its own.

## Metrics

- **Token F1**: Tokenization accuracy with character-offset alignment
- **Lemma Accuracy**: Exact lemma match rate
- **POS F1**: Universal Dependencies POS tag F1
- **NER F1**: Named entity span F1
- **Alignment ambiguity rate**: When engines tokenize the same input differently

## Architecture

- `run_eval.py`: Main eval harness - reads corpus, calls NLP service in COMPARE mode,
  computes per-engine metrics
- `corpus/`: JSONL corpus files
- `reports/`: Generated comparison reports
