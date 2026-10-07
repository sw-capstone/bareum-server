from __future__ import annotations

import json
import os
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from dataclasses import asdict
from pathlib import Path

from project_harness.secret_scan import ScanPolicy, SecretScanError, parse_findings, scan

ROOT = Path(__file__).resolve().parents[2]


class ReportTests(unittest.TestCase):
    def test_classification_and_metadata_only(self) -> None:
        policy = ScanPolicy.read(ROOT / "harness/secret-scan-policy.toml")
        raw = json.dumps([
            {"RuleID": rule, "File": "config.env", "StartLine": 1,
             "Secret": "sensitive-value", "Match": "sensitive-value", "Author": "person"}
            for rule in ("private-key", "generic-api-key", "new-unreviewed-rule")
        ])
        findings = parse_findings(raw, policy)
        self.assertEqual([f.level for f in findings], ["error", "warning", "warning"])
        safe = json.dumps([asdict(f) for f in findings])
        self.assertNotIn("sensitive-value", safe)
        self.assertNotIn("person", safe)

    def test_invalid_report_fails(self) -> None:
        policy = ScanPolicy.read(ROOT / "harness/secret-scan-policy.toml")
        for raw in ('{}', '[{"RuleID":"private-key"}]', '[{"RuleID":"private-key","File":"x","StartLine":0}]'):
            with self.subTest(raw=raw), self.assertRaises(SecretScanError):
                parse_findings(raw, policy)

    def test_scanner_version_mismatch_fails(self) -> None:
        with patch("project_harness.secret_scan.subprocess.run",
                   return_value=subprocess.CompletedProcess([], 0, stdout="0.0.0")):
            with self.assertRaises(SecretScanError):
                scan(ROOT, "git", "--all")

    def test_scanner_execution_error_fails(self) -> None:
        with patch("project_harness.secret_scan.subprocess.run", side_effect=[
            subprocess.CompletedProcess([], 0, stdout="8.30.1"),
            subprocess.CompletedProcess([], 1, stderr="private-value"),
        ]):
            with self.assertRaises(SecretScanError):
                scan(ROOT, "git", "--all")

    def test_inconsistent_exit_status_and_report_fails(self) -> None:
        policy = ScanPolicy.read(ROOT / "harness/secret-scan-policy.toml")
        with patch("project_harness.secret_scan.ScanPolicy.read", return_value=policy), \
             patch("project_harness.secret_scan.Path.read_text", return_value="[]"), \
             patch("project_harness.secret_scan.subprocess.run", side_effect=[
                 subprocess.CompletedProcess([], 0, stdout="8.30.1"),
                 subprocess.CompletedProcess([], 10),
             ]):
            with self.assertRaises(SecretScanError):
                scan(ROOT, "git", "--all")


@unittest.skipUnless(os.environ.get("GITLEAKS_BINARY"), "GITLEAKS_BINARY required for integration")
class ScannerTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "harness").mkdir()
        shutil.copyfile(ROOT / ".gitleaks.toml", self.root / ".gitleaks.toml")
        shutil.copyfile(ROOT / "harness/secret-scan-policy.toml", self.root / "harness/secret-scan-policy.toml")

    def run_scan(self, *args: str, binary: str | None = None) -> subprocess.CompletedProcess[str]:
        env = dict(os.environ, PYTHONPATH=str(ROOT / "harness/src"))
        if binary:
            env["GITLEAKS_BINARY"] = binary
        return subprocess.run(
            ["python3", "-m", "project_harness.secret_scan", "--root", str(self.root), *args],
            env=env, capture_output=True, text=True, timeout=60,
        )

    def test_method_call_and_placeholders_are_clean(self) -> None:
        (self.root / "Test.java").write_text(
            "RefreshToken token = persist(RefreshToken.create(member, UUID.randomUUID(), now.plusSeconds(3600)));\n"
            "String password = request.password();\n", encoding="utf-8")
        (self.root / ".env").write_text("DB_PASSWORD=replace-me\nJWT_SECRET=${JWT_SECRET}\n", encoding="utf-8")
        result = self.run_scan("--mode", "dir")
        self.assertEqual(result.returncode, 0, result.stdout)
        report = json.loads((self.root / "harness/reports/secret-scan.json").read_text())
        self.assertEqual(report["findings"], [])

    def test_ambiguous_password_warns_without_leaking_value(self) -> None:
        value = "Generated" + "Password!928"
        (self.root / ".env").write_text(f"DB_PASSWORD={value}\n", encoding="utf-8")
        result = self.run_scan("--mode", "dir")
        self.assertEqual(result.returncode, 0, result.stdout)
        safe = (self.root / "harness/reports/secret-scan.json").read_text()
        self.assertGreater(json.loads(safe)["warnings"], 0)
        self.assertNotIn(value, result.stdout + result.stderr + safe)

    def test_private_key_blocks(self) -> None:
        subprocess.run(["openssl", "genrsa", "-out", str(self.root / "key.pem"), "2048"],
                       check=True, capture_output=True)
        result = self.run_scan("--mode", "dir")
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("private-key", result.stdout)
        self.assertNotIn("BEGIN PRIVATE", result.stdout + result.stderr)

    def test_missing_binary_fails(self) -> None:
        result = self.run_scan("--mode", "dir", binary=str(self.root / "absent"))
        self.assertEqual(result.returncode, 2)
        self.assertFalse((self.root / "harness/reports/secret-scan.json").exists())

    def test_github_classic_token_blocks_without_leaking_value(self) -> None:
        self.assert_github_token_blocks("github-pat", "ghp_" + "Ab3Cd4Ef5Gh6Ij7Kl8Mn9Op0Qr1St2Uv3Wx4")

    def test_github_fine_grained_token_blocks_without_leaking_value(self) -> None:
        self.assert_github_token_blocks(
            "github-fine-grained-pat",
            "github_pat_" + "Ab3Cd4Ef5Gh6Ij7Kl8Mn9O" + "_" +
            ("p0Qr1St2Uv3Wx4Yz5Ab6Cd7Ef8Gh9Ij0Kl1Mn2Op3Qr4St5Uv6Wx7Yz8Ab9Cd0"[:59]),
        )

    def assert_github_token_blocks(self, rule: str, value: str) -> None:
        (self.root / "github.env").write_text("GITHUB_TOKEN=" + value + "\n", encoding="utf-8")
        result = self.run_scan("--mode", "dir")
        self.assertEqual(result.returncode, 1, result.stdout)
        safe = (self.root / "harness/reports/secret-scan.json").read_text()
        findings = json.loads(safe)["findings"]
        self.assertTrue(any(f["rule"] == rule and f["level"] == "error" for f in findings))
        self.assertNotIn(value, result.stdout + result.stderr + safe)

    def test_github_placeholder_does_not_block(self) -> None:
        (self.root / "github.env").write_text(
            "GITHUB_TOKEN=ghp_replace-me\nOTHER_TOKEN=github_pat_placeholder\n", encoding="utf-8")
        result = self.run_scan("--mode", "dir")
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_warning_formats_and_hash_boundary(self) -> None:
        value = "Generated" + "Password!928"
        cases = {
            "quoted.java": f'String password = "{value}";',
            "block.java": 'String password = """\n' + value + '\n""";',
            "block.yaml": "password: |\n  " + value,
            "config.ini": "password=" + value,
            "url.properties": "url=jdbc:postgresql://user:" + value + "@localhost/db",
            "query.properties": "url=jdbc:mysql://localhost/db?password=" + value,
            "hash.java": 'String passwordHash = "' + "a" * 64 + '";',
            "both.java": 'String passwordHash = "' + "a" * 64 + '"; String password = "' + value + '";',
        }
        for name, content in cases.items():
            (self.root / name).write_text(content + "\n", encoding="utf-8")
        result = self.run_scan("--mode", "dir")
        self.assertEqual(result.returncode, 0, result.stdout)
        report = json.loads((self.root / "harness/reports/secret-scan.json").read_text())
        paths = {Path(f["path"]).name for f in report["findings"]}
        self.assertEqual(paths, set(cases) - {"hash.java"})
        self.assertNotIn(value, result.stdout + result.stderr + json.dumps(report))

    def test_deleted_intermediate_commit_is_still_detected(self) -> None:
        def git(*args: str) -> str:
            return subprocess.run(["git", "-C", str(self.root), *args], check=True,
                                  capture_output=True, text=True).stdout.strip()
        git("init")
        git("config", "user.name", "Fixture")
        git("config", "user.email", "fixture@example.invalid")
        git("config", "commit.gpgsign", "false")
        git("add", ".")
        git("commit", "-m", "base")
        base = git("rev-parse", "HEAD")
        (self.root / "application.properties").write_text(
            "db.password=" + "GeneratedPassword!928\n", encoding="utf-8")
        git("add", ".")
        git("commit", "-m", "candidate")
        (self.root / "application.properties").unlink()
        git("add", "-u")
        git("commit", "-m", "remove")
        result = self.run_scan("--log-opts=" + base + "..HEAD")
        self.assertEqual(result.returncode, 0, result.stdout)
        report = json.loads((self.root / "harness/reports/secret-scan.json").read_text())
        self.assertGreater(report["warnings"], 0)
        self.assertTrue(all(f["commit"] for f in report["findings"]))
