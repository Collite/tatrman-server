# Rule-pack evaluation

**11/11 passed** · lane `default`

| kind | passed | total |
|---|---|---|
| decoy | 6 | 6 |
| hero | 2 | 2 |
| paraphrase | 3 | 3 |

| case | kind | result | detail |
|---|---|---|---|
| `hero-cs-invoices` | hero | ✅ | faktury_zakaznika |
| `hero-cs-role` | hero | ✅ | role |
| `para-cs-invoices-word-order` | paraphrase | ✅ | faktury_zakaznika |
| `para-cs-invoices-declined` | paraphrase | ✅ | faktury_zakaznika |
| `para-cs-role-inflected` | paraphrase | ✅ | role |
| `decoy-cs-invoices-no-customer` | decoy | ✅ | — |
| `decoy-cs-customer-no-invoices` | decoy | ✅ | — |
| `decoy-cs-wrong-order` | decoy | ✅ | — |
| `decoy-cs-unrelated` | decoy | ✅ | — |
| `decoy-cs-role-word-only` | decoy | ✅ | — |
| `decoy-en-invoices` | decoy | ✅ | — |

Diagnostics observed: `NLS-NLP-011`
