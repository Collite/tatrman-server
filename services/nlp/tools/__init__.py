# SPDX-License-Identifier: Apache-2.0
"""World-side tooling for the tatrman deployment of the NLP suite.

Not service code and not part of the wheel (NL-17): the suite is world-neutral,
and everything here knows something about TTR-M that the suite deliberately does
not. `export_lexicon.py` turns a defining repo's lexicon area into gazetteer
lists.
"""
