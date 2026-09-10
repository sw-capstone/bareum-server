#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export PYTHONPATH="${root}/harness/src"
cd "$root"
python3 -m unittest discover -s harness/tests -p 'test_*.py' -v
if find tests -type f -name 'test_*.py' -print -quit | grep -q .; then
  python3 -m unittest discover -s tests -p 'test_*.py' -v
else
  echo "Product tests: NOT APPLICABLE (no test_*.py files registered)"
fi
