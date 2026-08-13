# QA — our head-of-list lemma vs simplemma (read-only)

simplemma is a **second opinion, never a source**: nothing in this
comparison writes into a layer, and a disagreement is a row for a human
to look at rather than a defect on either side. Measured over the TRAIN
side of the frozen split, weighted by token count.

| outcome | tokens | share |
|---|--:|--:|
| both answer, same lemma | 259553 | 76.4% |
| both answer, different lemma | 12562 | 3.7% |
| not in our lexicon | 67667 | 19.9% |

## The disagreements worth a minute

Sorted by how often the form occurs. `gold` is CAC's own lemma, which
is the tie-break — it is the oracle both sides are eventually measured
against (contracts §11).

| form | CAC | ours | simplemma | tokens |
|---|---|---|---|--:|
| jejich | jeho | jejich | jeho | 712 |
| již | již | jenž | již | 431 |
| všech | všechno | všechno | všechen | 313 |
| let | rok | léto | rok | 301 |
| nás | já | my | já | 258 |
| její | jeho | její | jeho | 229 |
| více | více | více | hodně | 225 |
| nám | já | my | já | 213 |
| všechny | všechno | všechno | všechen | 212 |
| stále | stále | stálý | stále | 172 |
| tu | tady | ten | tady | 146 |
| našeho | můj | náš | můj | 141 |
| dobře | dobře | dobrý | dobře | 133 |
| letech | rok | léto | rok | 122 |
| našich | můj | náš | můj | 119 |
| naše | můj | náš | můj | 115 |
| jejichž | jejichž | jejichž | jenž | 98 |
| všechno | všechno | všechno | všechen | 85 |
| pomocí | pomocí | pomocí | pomoc | 75 |
| všem | všechno | všechno | všechen | 71 |
| plně | plně | plný | plně | 69 |
| ústavu | ústav | ústav | ústava | 68 |
| ně | on | ne | on | 66 |
| jejíž | jejíž | jejichž | jenž | 66 |
| celkem | celkem | celek | celkem | 65 |
| víc | více | více | hodně | 65 |
| buď | buď | být | buď | 63 |
| jenom | jenom | jen | jenom | 61 |
| Dále | dále | dále | Dále | 58 |
| spojení | spojení | spojený | spojení | 57 |
| zdraví | zdraví | zdraví | zdravý | 55 |
| případně | případně | případný | případně | 54 |
| skutečně | skutečně | skutečný | skutečně | 53 |
| všechny | všechen | všechno | všechen | 53 |
| všichni | všechno | všechno | všechen | 51 |
| rychle | rychle | rychlý | rychle | 49 |
| Všechny | všechno | všechno | všechen | 48 |
| prakticky | prakticky | praktický | prakticky | 48 |
| lety | rok | léto | rok | 48 |
| obecně | obecně | obecný | obecně | 47 |
