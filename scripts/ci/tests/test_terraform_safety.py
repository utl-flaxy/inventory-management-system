import contextlib
import io
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import quality_gates as gates
import terraform_offline as offline


class TerraformSafetyTest(unittest.TestCase):
    def test_private_paths_rejected_across_all_roots_without_inspecting_values(self):
        for root in ("terraform", "terraform-bootstrap", "private/migration"):
            for name in ("terraform.tfstate", "terraform.tfstate.backup", "errored.tfstate", "local.tfvars", "local.auto.tfvars.json",
                         ".terraform/terraform.tfstate", "account.backend.hcl", "backend-prod.hcl", "backend.conf",
                         "private.tfbackend", "saved.tfplan", "saved.tfplan.json", "tfplan.json", "change.plan", "change.plan.json",
                         "plan", "plan.json", ".terraformrc", ".aws/credentials"):
                with self.subTest(root=root, name=name):
                    self.assertTrue(gates.forbidden_terraform_path(f"{root}/{name}"))

    def test_public_sources_and_dependency_locks_remain_allowed(self):
        for name in ("terraform/backend.tf", "terraform/.terraform.lock.hcl", "terraform-bootstrap/.terraform.lock.hcl",
                     "terraform-bootstrap/tests/contracts.tftest.hcl", "docs/operations/terraform-remote-state.md"):
            self.assertFalse(gates.forbidden_terraform_path(name))

    def test_gitignore_covers_new_roots_and_keeps_provider_lock(self):
        repo = Path(__file__).resolve().parents[3]
        names = [f"migration/{s}" for s in ("terraform.tfstate", "errored.tfstate", "private.tfvars.json", "account.tfbackend",
                 "account.backend.hcl", "backend-prod.hcl", "saved.tfplan", "tfplan", ".terraform/providers/cache")]
        result = subprocess.run(["git", "check-ignore", "--no-index", "--stdin", "-z"], input=("\0".join(names) + "\0").encode(),
                                cwd=repo, capture_output=True, check=True)
        self.assertEqual(set(names), set(result.stdout.decode().rstrip("\0").split("\0")))
        result = subprocess.run(["git", "check-ignore", "--no-index", "terraform-bootstrap/.terraform.lock.hcl"], cwd=repo, capture_output=True)
        self.assertEqual(1, result.returncode)

    def test_force_added_private_file_fails_actual_secret_entry_point_before_read(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / ".github/security").mkdir(parents=True)
            (root / ".github/security/exceptions.json").write_text('{"secret_false_positives":[]}', encoding="utf-8")
            (root / ".gitignore").write_text("*.tfstate*\n", encoding="utf-8")
            subprocess.run(["git", "init", "--quiet", root], check=True, capture_output=True)
            private = root / "errored.tfstate"
            private.write_text("synthetic marker; deliberately contains no credential", encoding="utf-8")
            subprocess.run(["git", "add", "-f", ".github", ".gitignore", private.name], cwd=root, check=True, capture_output=True)
            with patch.object(gates, "run_secret") as scanner, contextlib.redirect_stdout(io.StringIO()) as output:
                with patch.object(sys, "argv", ["quality_gates.py", "secrets", "--root", str(root), "--scanner", sys.executable]):
                    self.assertEqual(1, gates.main())
                scanner.assert_not_called()
            self.assertNotIn("synthetic marker", output.getvalue())

    def fixture(self, directory):
        repo = Path(__file__).resolve().parents[3]
        root = Path(directory)
        for name in ("terraform", "terraform-bootstrap"):
            (root / name / "tests").mkdir(parents=True)
            for source in (repo / name).glob("*.tf"):
                (root / name / source.name).write_bytes(source.read_bytes())
            (root / name / ".terraform.lock.hcl").write_bytes((repo / name / ".terraform.lock.hcl").read_bytes())
            for source in (repo / name / "tests").glob("*.tftest.hcl"):
                (root / name / "tests" / source.name).write_bytes(source.read_bytes())
        return root

    def test_checked_in_contract_is_accepted(self):
        with tempfile.TemporaryDirectory() as temporary:
            offline.validate_sources(self.fixture(temporary))

    def test_backend_lock_disable_missing_encrypt_and_extra_inputs_fail(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = self.fixture(temporary)
            backend = root / "terraform/backend.tf"
            original = backend.read_text(encoding="utf-8")
            for text in (original.replace("use_lockfile = true", "use_lockfile = false"),
                         original.replace("encrypt      = true", ""), original.replace("encrypt      = true", 'bucket = "unreviewed"'),
                         original.replace("true", "var.flag")):
                backend.write_text(text, encoding="utf-8")
                with self.assertRaises(gates.Violation):
                    offline.validate_sources(root)

    def test_bootstrap_remote_backend_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = self.fixture(temporary)
            (root / "terraform-bootstrap/backend.tf").write_text('terraform { backend "s3" {} }', encoding="utf-8")
            with self.assertRaises(gates.Violation):
                offline.validate_sources(root)

    def test_destroy_protection_removal_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = self.fixture(temporary)
            main = root / "terraform-bootstrap/main.tf"
            main.write_text(main.read_text(encoding="utf-8").replace("prevent_destroy = true", "prevent_destroy = false", 1), encoding="utf-8")
            with self.assertRaises(gates.Violation):
                offline.validate_sources(root)

    def test_apply_fixture_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = self.fixture(temporary)
            tests = root / "terraform-bootstrap/tests/contracts.tftest.hcl"
            tests.write_text(tests.read_text(encoding="utf-8").replace("command = plan", "command = apply", 1), encoding="utf-8")
            with self.assertRaises(gates.Violation):
                offline.validate_sources(root)

    def test_credentials_and_cli_overrides_are_not_inherited(self):
        with tempfile.TemporaryDirectory() as temporary, patch.dict(os.environ, {
            "AWS_ACCESS_KEY_ID": "synthetic", "TF_VAR_account_id": "synthetic", "TF_CLI_ARGS_init": "synthetic", "TF_LOG": "TRACE"
        }):
            env = offline.safe_environment(Path(temporary))
            for name in ("AWS_ACCESS_KEY_ID", "TF_VAR_account_id", "TF_CLI_ARGS_init", "TF_LOG"):
                self.assertNotIn(name, env)
            self.assertEqual("true", env["AWS_EC2_METADATA_DISABLED"])
            self.assertEqual("", Path(env["AWS_CONFIG_FILE"]).read_text(encoding="utf-8"))

    def test_mutation_must_fail_an_assertion_not_a_scanner_error(self):
        for output in (b"Success! 0 passed, 0 failed.", b"Success! 26 passed, 0 failed.", b"incomplete"):
            with patch.object(offline.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, output, b"")):
                with self.assertRaises(gates.OperationalError):
                    offline.terraform(Path("."), ["test"], {}, expected_tests=27)
        for code, output in ((0, b"Success!"), (1, b"Provider error"), (2, b"Test assertion failed")):
            with patch.object(offline.subprocess, "run", return_value=subprocess.CompletedProcess([], code, output, b"")):
                with self.assertRaises(gates.OperationalError):
                    offline.terraform(Path("."), ["test"], {}, expected_failure=True)
        with patch.object(offline.subprocess, "run", return_value=subprocess.CompletedProcess([], 1, b"Test assertion failed", b"")):
            offline.terraform(Path("."), ["test"], {}, expected_failure=True)
