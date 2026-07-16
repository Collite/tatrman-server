# SPDX-License-Identifier: Apache-2.0
"""Make the sibling modules (`report`, `build_corpus`) importable from tests/
without packaging — the runner is a script tree, not an installed package.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
