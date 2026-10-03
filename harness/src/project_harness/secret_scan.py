"""Run Gitleaks and publish metadata-only findings under the team's blocking policy."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import tempfile
import tomllib
from dataclasses import asdict, dataclass
from pathlib import Path


class SecretScanError(RuntimeError):
    """The scanner or its report could not be trusted."""


@dataclass(frozen=True, slots=True)
class ScanPolicy:
    version: str
    gitleaks_version: str
    blocking_rules: frozenset[str]

    @classmethod
    def read(cls, path: Path) -> ScanPolicy:
        data = tomllib.loads(path.read_text(encoding="utf-8"))
        match data:
            case {"version": str(version), "gitleaks_version": str(scanner), "blocking_rules": list(rules)} if all(isinstance(rule, str) for rule in rules):
                return cls(version, scanner, frozenset(rules))
            case _:
                raise SecretScanError("Invalid secret scan policy")


@dataclass(frozen=True, slots=True)
class SecretFinding:
    rule: str
    path: str
    line: int
    commit: str
    level: str


def parse_findings(raw: str, policy: ScanPolicy) -> list[SecretFinding]:
    """Discard secret, match, author and message fields at the report boundary."""
    data = json.loads(raw)
    if not isinstance(data, list):
        raise SecretScanError("Invalid Gitleaks report")
    findings: list[SecretFinding] = []
    for entry in data:
        match entry:
            case {"RuleID": str(rule), "File": str(path), "StartLine": int(line), **rest} if line > 0 and re.fullmatch(r"[a-z0-9-]+", rule):
                commit = rest.get("Commit", "")
                if not isinstance(commit, str) or (commit and not re.fullmatch(r"[0-9a-f]{40,64}", commit)):
                    raise SecretScanError("Invalid Gitleaks commit")
                findings.append(SecretFinding(rule, path, line, commit, "error" if rule in policy.blocking_rules else "warning"))
            case _:
                raise SecretScanError("Invalid Gitleaks finding")
    return findings


def scan(root: Path, mode: str, log_opts: str) -> list[SecretFinding]:
    policy = ScanPolicy.read(root / "harness/secret-scan-policy.toml")
    binary = os.environ.get("GITLEAKS_BINARY", "gitleaks")
    version = subprocess.run([binary, "version"], capture_output=True, text=True, check=True, timeout=10)
    if version.stdout.strip().removeprefix("v") != policy.gitleaks_version:
        raise SecretScanError("Gitleaks version does not match the pinned policy")
    with tempfile.TemporaryDirectory(prefix="bareum-secret-scan-") as directory:
        report = Path(directory) / "findings.json"
        command = [binary, mode, str(root), "--config", str(root / ".gitleaks.toml"),
                   "--redact=100", "--no-banner", "--exit-code=10", "--report-format=json", "--report-path", str(report)]
        if mode == "git":
            command.append(f"--log-opts={log_opts}")
        result = subprocess.run(command, capture_output=True, text=True, timeout=120)
        if result.returncode not in (0, 10):
            raise SecretScanError("Gitleaks execution failed; raw output withheld")
        findings = parse_findings(report.read_text(encoding="utf-8"), policy)
        if (result.returncode == 10) != bool(findings):
            raise SecretScanError("Gitleaks exit status and report disagree")
        return findings


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--mode", choices=("git", "dir"), default="git")
    parser.add_argument("--log-opts", default="--all")
    args = parser.parse_args()
    root = args.root.resolve()
    report = root / "harness/reports/secret-scan.json"
    report.parent.mkdir(parents=True, exist_ok=True)
    report.unlink(missing_ok=True)
    try:
        findings = scan(root, args.mode, args.log_opts)
        policy = ScanPolicy.read(root / "harness/secret-scan-policy.toml")
        errors = sum(finding.level == "error" for finding in findings)
        warnings = len(findings) - errors
        report.write_text(json.dumps({"policy_version": policy.version, "gitleaks_version": policy.gitleaks_version,
                                     "mode": args.mode, "log_opts": args.log_opts,
                                     "errors": errors, "warnings": warnings,
                                     "findings": [asdict(finding) for finding in findings]}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        for finding in findings:
            # JSON escaping prevents control characters and workflow commands in filenames.
            print(f"{finding.level.upper()} HAR-SEC-001 " + json.dumps(asdict(finding), ensure_ascii=True))
        print(f"Secret scan: {errors} blocking findings, {warnings} warnings")
        return 1 if errors else 0
    except (OSError, UnicodeError, json.JSONDecodeError, tomllib.TOMLDecodeError, SecretScanError,
            subprocess.CalledProcessError, subprocess.TimeoutExpired):
        print("ERROR HAR-SEC-001 Secret scan could not complete; inspect installation, config and Git range.")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
