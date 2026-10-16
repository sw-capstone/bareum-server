"""Exercise the real Bash generator with simulated SSM responses and real jq."""
import base64
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "prepare-server-env.sh"
PREFIX = "/bareum/dev/server/"
PRIVATE_MARKER = "fixture-private-marker"
AWS_STUB = r'''#!/usr/bin/env bash
set -euo pipefail
names=()
collect=false
decrypt=false
while (( $# )); do
  case "$1" in
    --names) collect=true ;;
    --with-decryption) collect=false; decrypt=true ;;
    --*) collect=false ;;
    *) if [[ "$collect" == true ]]; then names+=("$1"); fi ;;
  esac
  shift
done
[[ "$decrypt" == true && ${#names[@]} -gt 0 && ${#names[@]} -le 10 ]]
printf '%s\n' "${names[@]}" >> "$AWS_REQUESTS"
case "${AWS_MODE:-}" in
  fail) printf '%s\n' "$AWS_PRIVATE_MARKER" >&2; exit 1 ;;
  late-fail) if [[ ${names[0]} == */SIGNUP_TERMS_AGE_OVER_14_VERSION ]]; then exit 1; fi ;;
  malformed) printf '%s\n' "$AWS_PRIVATE_MARKER"; exit 0 ;;
esac
jq -n --slurpfile fixture "$AWS_FIXTURE" --args '
  $ARGS.positional as $names |
  {Parameters: [$names[] as $name | $fixture[0][] | select(.Name == $name)],
   InvalidParameters: [$names[] as $name | select([$fixture[0][] | select(.Name == $name)] | length == 0) | $name]}
' "${names[@]}"
'''


class PrepareServerEnvTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="bareum-env-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.env_dir = self.root / "infra" / "env"
        scripts = self.root / "infra" / "scripts"
        self.env_dir.mkdir(parents=True)
        scripts.mkdir()
        self.script = scripts / SCRIPT.name
        shutil.copyfile(SCRIPT, self.script)
        self.template = self.env_dir / ".env.server.template"
        self.template.write_text("SERVER_PORT=8080\nDB_PASSWORD=discarded-template\n", encoding="utf-8")
        self.output = self.env_dir / ".env.server"
        self.original = b"SERVER_PORT=8080\nPREVIOUS_ENV=preserve\n"
        self.output.write_bytes(self.original)
        self.deploy = self.env_dir / ".env.deploy"
        self.deploy.write_text("AUTH_ENV_ENABLED=true\n", encoding="utf-8")
        self.fixture = self.root / "fixture.json"
        self.requests = self.root / "requests"
        self.bin = self.root / "bin"
        self.bin.mkdir()
        aws = self.bin / "aws"
        aws.write_text(AWS_STUB, encoding="utf-8", newline="\n")
        aws.chmod(0o700)
        if os.name == "nt":
            jq_binary = Path(shutil.which("jq")).as_posix()
            (self.bin / "jq").write_text(
                '#!/usr/bin/env bash\nargs=()\nfor arg in "$@"; do\n'
                '  if [[ -f "$arg" ]]; then arg=$(cygpath -w "$arg"); fi\n'
                '  args+=("$arg")\ndone\nexec "' + jq_binary + '" --binary "${args[@]}"\n',
                encoding="utf-8")
        self.values = {
            "LOGIN_JWT_SIGNING_KEY": base64.b64encode(b"J" * 32).decode(),
            "DB_URL": "jdbc:postgresql://db.example.invalid/bareum",
            "DB_USERNAME": "fixture_user",
            "DB_PASSWORD": PRIVATE_MARKER + "-$'\"=end",
            "EMAIL_VERIFICATION_HASH_SECRET": base64.b64encode(b"H" * 32).decode(),
            "EMAIL_VERIFICATION_OUTBOX_ENCRYPTION_KEY": base64.b64encode(b"O" * 32).decode(),
            "SPRING_MAIL_HOST": "smtp.example.invalid",
            "SPRING_MAIL_PORT": "587",
            "SPRING_MAIL_USERNAME": "sender@example.invalid",
            "SPRING_MAIL_PASSWORD": PRIVATE_MARKER + "-mail",
            "SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH": "true",
            "SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_ENABLE": "false",
            "SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE": "true",
            "SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_REQUIRED": "true",
            "EMAIL_VERIFICATION_MAIL_FROM": "sender@example.invalid",
            "LOGIN_ALLOWED_ORIGINS": "https://web.example.invalid,https://preview.example.invalid",
            "EMAIL_VERIFICATION_MINIMUM_SEND_INTERVAL_SECONDS": "60",
            "EMAIL_VERIFICATION_MAX_SENDS_PER_HOUR": "10",
            "EMAIL_VERIFICATION_MAX_SENDS_PER_DAY": "30",
            "EMAIL_VERIFICATION_MAX_VERIFICATION_ATTEMPTS": "5",
            "SIGNUP_TERMS_AGE_OVER_14_VERSION": "approved-age",
            "SIGNUP_TERMS_TERMS_OF_SERVICE_VERSION": "approved-service",
            "SIGNUP_TERMS_PRIVACY_POLICY_VERSION": "approved-privacy",
            "SIGNUP_TERMS_MARKETING_CONSENT_VERSION": "approved-marketing",
        }
        self.secure = {"DB_PASSWORD", "LOGIN_JWT_SIGNING_KEY", "EMAIL_VERIFICATION_HASH_SECRET",
                       "EMAIL_VERIFICATION_OUTBOX_ENCRYPTION_KEY", "SPRING_MAIL_PASSWORD"}
        self.environment = os.environ.copy()
        self.environment.update({
            "PATH": str(self.bin) + os.pathsep + os.environ["PATH"],
            "AWS_FIXTURE": self.fixture.as_posix(), "AWS_REQUESTS": self.requests.as_posix(),
            "AWS_PRIVATE_MARKER": PRIVATE_MARKER,
            "MSYS_NO_PATHCONV": "1",
        })
        self.bash = os.environ.get("BASH_BINARY", "bash")

    def run_generator(self, *args, extra_parameters=()):
        parameters = [{"Name": PREFIX + key, "Value": value,
                       "Type": "SecureString" if key in self.secure else "String"}
                      for key, value in self.values.items()]
        parameters.extend(extra_parameters)
        self.fixture.write_text(json.dumps(parameters), encoding="utf-8")
        result = subprocess.run([self.bash, self.script.as_posix(), *args],
                                env=self.environment, capture_output=True, text=True, timeout=30)
        self.assertNotIn(PRIVATE_MARKER, result.stdout + result.stderr)
        self.assertFalse(list(self.env_dir.glob(".env.prepare.*")))
        return result

    def assert_failure_preserves_environment(self, *args, **kwargs):
        result = self.run_generator(*args, **kwargs)
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(self.original, self.output.read_bytes())

    def test_auth_success_has_exact_raw_values_and_only_requested_keys(self):
        self.template.write_text("SERVER_PORT=8080\nDB_PASSWORD=discarded\nSPRING_MAIL_PASSWORD=discarded\n", encoding="utf-8")
        result = self.run_generator()
        self.assertEqual(0, result.returncode, result.stderr)
        generated = dict(line.split("=", 1) for line in self.output.read_text().splitlines() if "=" in line)
        self.assertEqual({"SERVER_PORT": "8080", **self.values}, generated)
        self.assertNotIn("AUTH_ENV_ENABLED", generated)
        if os.name != "nt":
            self.assertEqual(0o600, stat.S_IMODE(self.output.stat().st_mode))
        names = self.requests.read_text().splitlines()
        self.assertEqual(29, len(names))
        self.assertEqual(29, len(set(names)))

    def test_email_mode_preserves_nine_parameters_and_ignores_inherited_activation(self):
        self.deploy.unlink()
        self.environment["AUTH_ENV_ENABLED"] = "true"
        self.assertEqual(0, self.run_generator().returncode)
        self.assertEqual({PREFIX + key for key in (
            "DB_URL", "DB_USERNAME", "DB_PASSWORD", "EMAIL_VERIFICATION_HASH_SECRET",
            "EMAIL_VERIFICATION_OUTBOX_ENCRYPTION_KEY", "SPRING_MAIL_PASSWORD",
            "SPRING_MAIL_HOST", "SPRING_MAIL_USERNAME", "EMAIL_VERIFICATION_MAIL_FROM")},
                         set(self.requests.read_text().splitlines()))
        self.assertIn("EMAIL_VERIFICATION_HASH_SECRET=", self.output.read_text())
        self.assertNotIn("LOGIN_JWT_SIGNING_KEY=", self.output.read_text())

    def test_email_mode_preserves_v2_template_without_enabling_confirmation(self):
        self.deploy.write_text("AUTH_ENV_ENABLED=false\n", encoding="utf-8")
        template = SCRIPT.parents[1] / "env" / ".env.server.template"
        self.template.write_text(template.read_text(encoding="utf-8"), encoding="utf-8")
        self.assertEqual(0, self.run_generator().returncode)
        generated = dict(line.split("=", 1) for line in self.output.read_text(encoding="utf-8").splitlines()
                         if "=" in line and not line.startswith("#"))
        for line in template.read_text(encoding="utf-8").splitlines():
            if "=" in line and not line.startswith("#"):
                key, value = line.split("=", 1)
                self.assertEqual(value, generated[key], key)
        self.assertNotIn("EMAIL_VERIFICATION_MAX_VERIFICATION_ATTEMPTS", generated)
        self.assertNotIn("LOGIN_JWT_SIGNING_KEY", generated)

    def test_email_mode_still_requires_all_base_parameters(self):
        self.deploy.write_text("AUTH_ENV_ENABLED=false\n", encoding="utf-8")
        for key in ("DB_PASSWORD", "EMAIL_VERIFICATION_HASH_SECRET",
                    "EMAIL_VERIFICATION_OUTBOX_ENCRYPTION_KEY", "SPRING_MAIL_HOST",
                    "SPRING_MAIL_USERNAME", "SPRING_MAIL_PASSWORD", "EMAIL_VERIFICATION_MAIL_FROM"):
            with self.subTest(key=key):
                previous = self.values.pop(key)
                self.assert_failure_preserves_environment()
                self.values[key] = previous

    def test_email_mode_validates_keys_and_secure_string(self):
        self.deploy.write_text("AUTH_ENV_ENABLED=false\n", encoding="utf-8")
        for key, value in (("EMAIL_VERIFICATION_HASH_SECRET", "not-base64"),
                           ("EMAIL_VERIFICATION_HASH_SECRET", base64.b64encode(b"H" * 31).decode()),
                           ("EMAIL_VERIFICATION_OUTBOX_ENCRYPTION_KEY", base64.b64encode(b"O" * 33).decode()),
                           ("DB_URL", "   ")):
            with self.subTest(key=key):
                previous = self.values[key]
                self.values[key] = value
                self.assert_failure_preserves_environment()
                self.values[key] = previous
        self.secure.remove("SPRING_MAIL_PASSWORD")
        self.assert_failure_preserves_environment()

    def test_full_auth_replaces_legacy_aliases_only_when_ssm_supplies_the_policy(self):
        self.template.write_text("SERVER_PORT=8080\n"
                                 "AUTH_EMAILVERIFICATION_SIGNUPCODETTLSECONDS=300\n"
                                 "AUTH_EMAILVERIFICATION_MINIMUMSENDINTERVALSECONDS=99\n"
                                 "AUTH_EMAILVERIFICATION_MAXSENDSPERHOUR=99\n"
                                 "AUTH_EMAILVERIFICATION_MAXSENDSPERDAY=99\n"
                                 "AUTH_EMAILVERIFICATION_PASSWORDRESETCODETTLSECONDS=300\n",
                                 encoding="utf-8")
        self.assertEqual(0, self.run_generator().returncode)
        generated = self.output.read_text()
        self.assertNotIn("AUTH_EMAILVERIFICATION_MINIMUMSENDINTERVALSECONDS=", generated)
        self.assertNotIn("AUTH_EMAILVERIFICATION_MAXSENDSPERHOUR=", generated)
        self.assertNotIn("AUTH_EMAILVERIFICATION_MAXSENDSPERDAY=", generated)
        self.assertIn("AUTH_EMAILVERIFICATION_SIGNUPCODETTLSECONDS=300", generated)
        self.assertIn("AUTH_EMAILVERIFICATION_PASSWORDRESETCODETTLSECONDS=300", generated)
        self.values["EMAIL_VERIFICATION_PASSWORD_RESET_CODE_TTL_SECONDS"] = "420"
        self.values["EMAIL_VERIFICATION_PASSWORD_RESET_VERIFICATION_TTL_SECONDS"] = "600"
        self.assertEqual(0, self.run_generator().returncode)
        self.assertNotIn("AUTH_EMAILVERIFICATION_PASSWORDRESETCODETTLSECONDS=", self.output.read_text())
        self.assertIn("EMAIL_VERIFICATION_PASSWORD_RESET_CODE_TTL_SECONDS=420", self.output.read_text())

    def test_explicit_auth_flag_overrides_disabled_file(self):
        self.deploy.write_text("AUTH_ENV_ENABLED=false\n", encoding="utf-8")
        self.assertEqual(0, self.run_generator("--with-auth").returncode)

    def test_invalid_or_duplicate_activation_fails_before_lookup(self):
        for value in ("yes", "true\nAUTH_ENV_ENABLED=false", ""):
            with self.subTest(value=value):
                self.deploy.write_text("AUTH_ENV_ENABLED=" + value + "\n", encoding="utf-8")
                self.assert_failure_preserves_environment()
                self.assertFalse(self.requests.exists())

    def test_unknown_arguments_are_rejected(self):
        self.assert_failure_preserves_environment("--unknown")
        self.assertFalse(self.requests.exists())

    def test_required_parameters_cannot_be_missing(self):
        for key in ("DB_URL", "LOGIN_JWT_SIGNING_KEY", "EMAIL_VERIFICATION_HASH_SECRET", "SPRING_MAIL_PASSWORD",
                    "SIGNUP_TERMS_PRIVACY_POLICY_VERSION"):
            with self.subTest(key=key):
                value = self.values.pop(key)
                self.assert_failure_preserves_environment()
                self.values[key] = value

    def test_aws_failure_and_malformed_json_do_not_leak_or_replace_environment(self):
        for mode in ("fail", "late-fail", "malformed"):
            with self.subTest(mode=mode):
                self.environment["AWS_MODE"] = mode
                self.assert_failure_preserves_environment()

    def test_duplicate_ssm_parameters_are_rejected(self):
        self.assert_failure_preserves_environment(extra_parameters=[
            {"Name": PREFIX + "DB_URL", "Value": "duplicate", "Type": "String"}])

    def test_all_credentials_require_secure_string(self):
        for key in tuple(self.secure):
            with self.subTest(key=key):
                self.secure.remove(key)
                self.assert_failure_preserves_environment()
                self.secure.add(key)

    def test_empty_and_multiline_values_are_rejected(self):
        for value in ("", "unsafe\nINJECTED=true", "unsafe\rvalue", "unsafe\x00value"):
            with self.subTest(value=value):
                self.values["SPRING_MAIL_PASSWORD"] = value
                self.assert_failure_preserves_environment()

    def test_key_encoding_and_lengths_are_checked(self):
        for key, value in (("LOGIN_JWT_SIGNING_KEY", "not-base64"),
                           ("LOGIN_JWT_SIGNING_KEY", base64.b64encode(b"J" * 31).decode()),
                           ("EMAIL_VERIFICATION_HASH_SECRET", "not-base64"),
                           ("EMAIL_VERIFICATION_HASH_SECRET", base64.b64encode(b"H" * 31).decode()),
                           ("EMAIL_VERIFICATION_OUTBOX_ENCRYPTION_KEY", base64.b64encode(b"O" * 33).decode())):
            with self.subTest(key=key, value=value):
                previous = self.values[key]
                self.values[key] = value
                self.assert_failure_preserves_environment()
                self.values[key] = previous

    def test_invalid_limits_and_ports_are_rejected(self):
        for key, value in (("SPRING_MAIL_PORT", "65536"),
                           ("EMAIL_VERIFICATION_MAX_SENDS_PER_HOUR", "0"),
                           ("EMAIL_VERIFICATION_MAX_SENDS_PER_DAY", "2147483648"),
                           ("EMAIL_VERIFICATION_MAX_VERIFICATION_ATTEMPTS", "-1")):
            with self.subTest(key=key, value=value):
                previous = self.values[key]
                self.values[key] = value
                self.assert_failure_preserves_environment()
                self.values[key] = previous

    def test_tls_is_required_and_implicit_tls_is_supported(self):
        self.values["SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_REQUIRED"] = "false"
        self.assert_failure_preserves_environment()
        self.values["SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_ENABLE"] = "true"
        self.values["SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE"] = "false"
        self.assertEqual(0, self.run_generator().returncode)

    def test_cors_does_not_allow_http_wildcards_or_paths(self):
        for origin in ("http://web.example.invalid", "https://*.example.invalid", "https://web.example.invalid/path"):
            with self.subTest(origin=origin):
                self.values["LOGIN_ALLOWED_ORIGINS"] = origin
                self.assert_failure_preserves_environment()

    def test_unapproved_placeholder_and_overlong_term_versions_are_rejected(self):
        for value in ("   ", "<pending>", "v" * 31):
            with self.subTest(value=value):
                self.values["SIGNUP_TERMS_PRIVACY_POLICY_VERSION"] = value
                self.assert_failure_preserves_environment()

    def test_google_and_reset_parameters_are_optional_but_validated_when_present(self):
        self.values.update({"GOOGLE_CLIENT_IDS": "test-web.apps.googleusercontent.com",
                            "GOOGLE_SIGNUP_TTL_SECONDS": "600",
                            "EMAIL_VERIFICATION_PASSWORD_RESET_CODE_TTL_SECONDS": "300",
                            "EMAIL_VERIFICATION_PASSWORD_RESET_VERIFICATION_TTL_SECONDS": "600"})
        self.assertEqual(0, self.run_generator().returncode)
        self.output.write_bytes(self.original)
        self.values["GOOGLE_CLIENT_IDS"] = "unapproved-client"
        self.assert_failure_preserves_environment()
        del self.values["GOOGLE_CLIENT_IDS"]
        del self.values["EMAIL_VERIFICATION_PASSWORD_RESET_VERIFICATION_TTL_SECONDS"]
        self.assert_failure_preserves_environment()

    @unittest.skipIf(os.name == "nt", "POSIX symlink permissions are checked by Linux CI")
    def test_output_symlink_does_not_change_its_target(self):
        target = self.root / "untouched"
        target.write_bytes(self.original)
        self.output.unlink()
        self.output.symlink_to(target)
        self.assert_failure_preserves_environment()
        self.assertEqual(self.original, target.read_bytes())

    def test_recovery_cooldown_is_optional_and_validated_before_environment_replacement(self):
        self.values["EMAIL_VERIFICATION_ATTEMPT_COOLDOWN_SECONDS"] = "900"
        self.assertEqual(0, self.run_generator().returncode)
        self.output.write_bytes(self.original)
        for value in ("0", "-1", "86401", "not-a-duration"):
            with self.subTest(value=value):
                self.values["EMAIL_VERIFICATION_ATTEMPT_COOLDOWN_SECONDS"] = value
                self.assert_failure_preserves_environment()


if __name__ == "__main__":
    unittest.main()
