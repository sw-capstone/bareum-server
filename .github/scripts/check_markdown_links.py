"""Check that local Markdown links point to files inside the repository.

CI-DOC-001: the link points outside the repository.
CI-DOC-002: the linked file does not exist.
Anchors, web links and mail links are not checked.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path
from urllib.parse import unquote

SKIP_PARTS = {".git", ".gradle", ".venv", "build", "dist", "node_modules", "out", "reports", "__pycache__"}
LINK_PATTERN = re.compile(r"(?<!!)\[[^\]]+\]\(([^)]+)\)")

Finding = tuple[str, str, str]


def check_markdown_links(root: Path) -> list[Finding]:
    findings: list[Finding] = []
    root = root.resolve()
    for path in root.rglob("*.md"):
        if not path.is_file() or SKIP_PARTS.intersection(path.relative_to(root).parts):
            continue
        relative = str(path.relative_to(root))
        for match in LINK_PATTERN.finditer(path.read_text(encoding="utf-8")):
            target = match.group(1).strip()
            if target.startswith(("http://", "https://", "mailto:", "#")):
                continue
            target_path = (path.parent / unquote(target.split("#", 1)[0])).resolve()
            try:
                target_path.relative_to(root)
            except ValueError:
                findings.append(("CI-DOC-001", relative, f"링크가 저장소 밖을 가리킵니다: {target}"))
                continue
            if not target_path.exists():
                findings.append(("CI-DOC-002", relative, f"없는 문서 링크: {target}"))
    return findings


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", type=Path, default=Path.cwd())
    findings = check_markdown_links(parser.parse_args(argv).root)
    for check_id, path, message in findings:
        print(f"ERROR {check_id} {path}: {message}")
    print(f"Markdown link checks: {'FAILED' if findings else 'PASSED'}")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
