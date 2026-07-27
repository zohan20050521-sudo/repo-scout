"""`python -m repo_scout_eval ...` 等价入口。"""

from __future__ import annotations

import sys

from .cli import main

if __name__ == "__main__":
    sys.exit(main())
