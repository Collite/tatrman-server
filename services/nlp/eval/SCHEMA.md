# NLP Eval Corpus Schema

## Overview

This directory contains evaluation corpora for the NLP service. The corpus is used
to measure per-engine quality on tokenization, lemmatization, POS tagging, and NER.

## Schema

Each line in a `.jsonl` file is a JSON object with the following fields:

| Field | Type | Description |
|-------|------|-------------|
| `id` | string | Unique identifier (e.g., `cs-q-001`) |
| `question` | string | Input text (Czech question or phrase) |
| `lang` | string | Language code (`cs`, `en`) |
| `expected` | object | Ground truth annotations (see below) |

### `expected` Object

| Field | Type | Description |
|-------|------|-------------|
| `tokens` | array | Expected tokens as list of `{text, lemma, upos}` |
| `lemmas` | array | Expected lemmas in order (for accuracy measurement) |
| `entities` | array | Expected NER entities as `{text, label, charStart, charEnd}` |
| `functionId` | string | Target domain function (optional, e.g., `find_customer`) |
| `args` | object | Expected resolved args (optional) |

### Example Entry

```json
{
  "id": "cs-q-001",
  "question": "Kdo je zákazník Shell UK?",
  "lang": "cs",
  "expected": {
    "tokens": [
      {"text": "Kdo", "lemma": "kdo", "upos": "PRON"},
      {"text": "je", "lemma": "být", "upos": "AUX"},
      {"text": "zákazník", "lemma": "zákazník", "upos": "NOUN"},
      {"text": "Shell", "lemma": "Shell", "upos": "PROPN"},
      {"text": "UK", "lemma": "UK", "upos": "PROPN"}
    ],
    "lemmas": ["kdo", "být", "zákazník", "Shell", "UK"],
    "entities": [
      {"text": "Shell UK", "label": "ORG", "charStart": 14, "charEnd": 22}
    ],
    "functionId": "find_customer",
    "args": {"name": "Shell UK"}
  }
}
```

## Categories

Seed corpus questions are curated to cover:

1. **Simple lookups** - Direct entity mentions (customer name, order ID)
2. **Multi-entity** - Questions with multiple named entities
3. **Ambiguity** - Words that could be multiple entity types
4. **Follow-up phrasings** - Variations of the same underlying query
5. **Czech morphology** - Challenges specific to Czech (inflection, compounding)

## Corpus Files

- `seed.jsonl` - Initial 50-question seed corpus (hand-curated)
- Additional corpus files may be added over time for regression testing

## Adding New Fixtures

1. Add a new JSON line to the appropriate corpus file
2. Ensure `charStart`/`charEnd` are accurate (byte offsets in the UTF-8 string)
3. Include at least tokens and entities for evaluation
4. Run `just eval-nlp` to update metrics

## Metrics Computed

- **Token F1** - Tokenization accuracy
- **Lemma accuracy** - Exact lemma match rate
- **UD POS F1** - POS tagging accuracy (using `upos` field)
- **NER span F1** - Named entity recognition quality
- **Alignment ambiguity rate** - When engines tokenize differently
