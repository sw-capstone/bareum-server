from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from check_markdown_links import check_markdown_links, main

ROOT = Path(__file__).resolve().parents[2]


class MarkdownLinkChecksTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def write(self, relative: str, text: str) -> None:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def test_repository_links_pass(self) -> None:
        self.assertEqual(check_markdown_links(ROOT), [])

    def test_missing_target_is_reported(self) -> None:
        self.write("docs/a.md", "[b](b.md)")
        self.assertEqual(check_markdown_links(self.root), [("CI-DOC-002", "docs/a.md", "없는 문서 링크: b.md")])

    def test_link_outside_repository_is_reported(self) -> None:
        self.write("docs/a.md", "[x](../../outside.md)")
        self.assertEqual([f[0] for f in check_markdown_links(self.root)], ["CI-DOC-001"])

    def test_existing_target_with_anchor_passes(self) -> None:
        self.write("docs/b.md", "# 제목")
        self.write("docs/a.md", "[b](b.md#제목) [same](#x) [web](https://example.invalid) ![img](missing.png)")
        self.assertEqual(check_markdown_links(self.root), [])

    def test_encoded_path_is_resolved(self) -> None:
        self.write("docs/한글 문서.md", "")
        self.write("docs/a.md", "[k](%ED%95%9C%EA%B8%80%20%EB%AC%B8%EC%84%9C.md)")
        self.assertEqual(check_markdown_links(self.root), [])

    def test_skipped_directories_are_ignored(self) -> None:
        self.write("build/a.md", "[b](missing.md)")
        self.write("node_modules/x/a.md", "[b](missing.md)")
        self.assertEqual(check_markdown_links(self.root), [])

    def test_failure_returns_non_zero(self) -> None:
        self.write("a.md", "[b](missing.md)")
        self.assertEqual(main(["--root", str(self.root)]), 1)


if __name__ == "__main__":
    unittest.main()
