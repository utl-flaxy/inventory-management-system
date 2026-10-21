import contextlib
import copy
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import quality_gates as gates
import terraform_offline as offline
import terraform_preflight as preflight
import terraform_synthetic as synthetic

REPO = Path(__file__).resolve().parents[3]


class SyntheticSafetyTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.fixture = self.root / "terraform-sandbox-tests"
        shutil.copytree(REPO / "terraform-sandbox-tests", self.fixture)

    def read(self, name):
        return json.loads((self.fixture / name).read_text(encoding="utf-8"))

    def write(self, name, value):
        (self.fixture / name).write_text(json.dumps(value), encoding="utf-8")

    def test_reviewed_source_is_offline_and_always_blocked(self):
        decision = synthetic.validate(self.fixture)
        self.assertEqual("BLOCKED", decision["decision"])
        self.assertEqual((0, 0, False), (decision["approved"], decision["executed"], decision["cloud_execution_available"]))

    def test_real_gate_entry_point_never_returns_success_or_executes_a_command(self):
        with patch.object(sys, "argv", ["terraform_preflight.py"]), patch.object(subprocess, "run") as execute:
            with contextlib.redirect_stdout(io.StringIO()) as output:
                self.assertEqual(1, preflight.main())
            execute.assert_not_called()
        self.assertEqual("BLOCKED", json.loads(output.getvalue())["decision"])

    def test_each_unapproved_gate_cannot_be_marked_ready(self):
        for identifier in preflight.GATES:
            document = self.read("preflight.public.json")
            document["gates"][identifier] = "READY"
            with self.subTest(identifier=identifier), self.assertRaises(gates.Violation):
                preflight.blocked_record(document)

    def test_gate_rejects_operation_and_approval_arguments_without_echo(self):
        for arguments in (["--operation", "apply"], ["--approval-ticket", "synthetic-private-marker"], ["--allow-cloud"]):
            with patch.object(sys, "argv", ["terraform_preflight.py", *arguments]):
                with contextlib.redirect_stderr(io.StringIO()) as output, self.assertRaises(SystemExit) as result:
                    preflight.main()
            self.assertEqual(2, result.exception.code)
            self.assertNotIn("synthetic-private-marker", output.getvalue())
            self.assertIn("cloud execution denied", output.getvalue())

    def test_all_ready_or_forged_tickets_cannot_authorize_execution(self):
        for status in ("READY", "APPROVED", "APPROVED: synthetic-ticket-1", {"status": "READY", "ticket": "synthetic-ticket-1"}):
            document = self.read("preflight.public.json")
            document["gates"] = {key: status for key in preflight.GATES}
            with self.subTest(status=type(status).__name__), self.assertRaises(gates.Violation):
                preflight.blocked_record(document)

    def test_missing_and_unknown_gates_are_rejected(self):
        for identifier in preflight.GATES:
            document = self.read("preflight.public.json")
            del document["gates"][identifier]
            with self.assertRaises(gates.Violation):
                preflight.blocked_record(document)
        document["gates"]["B99"] = "BLOCKED"
        with self.assertRaises(gates.Violation):
            preflight.blocked_record(document)

    def test_duplicate_json_fields_and_non_json_constants_are_rejected(self):
        for text in ('{"B01":"BLOCKED","B01":"READY"}', '{"gates":{},"gates":{}}', '{"value":NaN}', '{'):
            with self.assertRaises(gates.Violation):
                preflight.strict_json(text)

    def test_unexecuted_cloud_tests_cannot_be_reported_pass(self):
        for identifier in preflight.TESTS:
            document = self.read("preflight.public.json")
            document["tests"][identifier] = "PASS"
            with self.assertRaises(gates.Violation):
                preflight.blocked_record(document)

    def test_cleanup_kms_and_digest_remain_blocked(self):
        for identifier in ("C01", "C02", "C05"):
            document = self.read("preflight.public.json")
            document["unresolved"][identifier] = "RESOLVED"
            with self.assertRaises(gates.Violation):
                preflight.blocked_record(document)

    def test_state_body_credentials_and_approval_details_are_not_public_fields(self):
        for field, value in (("resources", []), ("serial", 1), ("outputs", {}), ("token", "synthetic-not-a-secret"),
                             ("credentials", {}), ("approval_ticket", "synthetic-ticket-1")):
            document = self.read("preflight.public.json")
            document[field] = value
            with self.assertRaises(gates.Violation):
                preflight.blocked_record(document)

    def test_invalid_preflight_types_and_empty_records_fail_closed(self):
        for document in ({}, [], None, {"schema_version": True}, "READY"):
            with self.assertRaises(gates.Violation):
                preflight.blocked_record(document)

    def test_backend_coordinates_and_lock_disable_are_rejected(self):
        original = self.read("backend.tf.json")
        for field, value in (("bucket", "synthetic-not-a-bucket"), ("key", "synthetic-not-a-state"),
                             ("region", "synthetic-region"), ("profile", "synthetic-profile"),
                             ("use_lockfile", False), ("encrypt", False), ("encrypt", 1)):
            document = copy.deepcopy(original)
            document["terraform"]["backend"]["s3"][field] = value
            self.write("backend.tf.json", document)
            with self.assertRaises(gates.Violation):
                synthetic.validate(self.fixture)

    def test_host_or_bootstrap_root_cannot_masquerade_as_synthetic(self):
        for root in ("terraform", "terraform-bootstrap"):
            with self.assertRaises(gates.Violation):
                synthetic.validate(REPO / root)

    def test_no_external_provider_module_state_or_provisioner_capability(self):
        original = self.read("main.tf.json")
        changes = [dict(original, module={"host": {"source": "../terraform"}}),
                   dict(original, provider={"aws": {}}), dict(original, data={"terraform_remote_state": {}}),
                   dict(original, resource={"aws_s3_bucket": {"forbidden": {}}})]
        provisioner = copy.deepcopy(original)
        provisioner["resource"]["terraform_data"]["fixture"]["provisioner"] = {"local-exec": {"command": "forbidden"}}
        changes.append(provisioner)
        for document in changes:
            self.write("main.tf.json", document)
            with self.assertRaises(gates.Violation):
                synthetic.validate(self.fixture)

    def test_apply_and_implicit_apply_test_runs_are_rejected(self):
        original = self.read("tests/contracts.tftest.json")
        for command in ("apply", None):
            document = copy.deepcopy(original)
            if command is None:
                del document["run"]["defaults"]["command"]
            else:
                document["run"]["defaults"]["command"] = command
            self.write("tests/contracts.tftest.json", document)
            with self.assertRaises(gates.Violation):
                synthetic.validate(self.fixture)

    def test_test_expressions_cannot_read_files_or_override_modules(self):
        original = self.read("tests/contracts.tftest.json")
        for expression in ('${file("forbidden") == "synthetic-a"}', '${provider::external::read()}'):
            document = copy.deepcopy(original)
            document["run"]["defaults"]["assert"][0]["condition"] = expression
            self.write("tests/contracts.tftest.json", document)
            with self.assertRaises(gates.Violation):
                synthetic.validate(self.fixture)
        document = copy.deepcopy(original)
        document["run"]["defaults"]["module"] = {"source": "../terraform"}
        self.write("tests/contracts.tftest.json", document)
        with self.assertRaises(gates.Violation):
            synthetic.validate(self.fixture)

    def test_apply_remote_init_state_and_plan_commands_never_reach_terraform(self):
        commands = [["apply"], ["plan"], ["destroy"], ["state", "pull"], ["force-unlock", "synthetic"],
                    ["init"], ["init", "-backend=true"], ["init", "-backend=false", "-input=false"],
                    ["test", "-no-color", "-var=fixture_label=unreviewed"], ["test", "-no-color", "-test-directory=../terraform/tests"]]
        for arguments in commands:
            with patch.object(offline.subprocess, "run") as execute, self.assertRaises(gates.Violation):
                offline.terraform(self.fixture, arguments, {})
            execute.assert_not_called()

    def test_accidentally_reintroduced_credentials_or_cli_args_are_rejected(self):
        for key in ("AWS_ACCESS_KEY_ID", "AWS_PROFILE", "AWS_WEB_IDENTITY_TOKEN_FILE", "AWS_CONTAINER_CREDENTIALS_FULL_URI",
                    "TF_TOKEN_app_terraform_io", "TF_CLI_ARGS_test", "TF_VAR_sequence", "TF_LOG", "aws_access_key_id"):
            with patch.object(offline.subprocess, "run") as execute, self.assertRaises(gates.Violation):
                offline.terraform(self.fixture, ["test", "-no-color"], {key: "synthetic"})
            execute.assert_not_called()

    def test_force_added_state_plan_token_and_backend_files_stop_export_before_body_read(self):
        subprocess.run(["git", "init", "--quiet", self.root], check=True, capture_output=True)
        for name in ("errored.tfstate", "saved.tfplan", "private.tfbackend", "token.json", "renamed-state.json"):
            path = self.fixture / name
            path.write_text('{"serial":1,"resources":[],"token":"synthetic-not-a-secret"}', encoding="utf-8")
            subprocess.run(["git", "add", "-f", str(path)], cwd=self.root, check=True, capture_output=True)
            with patch.object(Path, "read_text", side_effect=AssertionError("No source body read allowed")):
                with self.assertRaises(gates.Violation):
                    offline.exported_source(self.root, self.root / "export")
            subprocess.run(["git", "rm", "--cached", "--quiet", str(path)], cwd=self.root, check=True, capture_output=True)

    def test_sanitized_environment_disables_all_aws_credential_providers(self):
        names = {"AWS_PROFILE": "synthetic", "AWS_CONFIG_FILE": "forbidden", "AWS_SHARED_CREDENTIALS_FILE": "forbidden",
                 "AWS_CONTAINER_CREDENTIALS_RELATIVE_URI": "forbidden", "TF_CLI_CONFIG_FILE": "forbidden", "HCP_TOKEN": "synthetic"}
        with patch.dict(os.environ, names):
            env = offline.safe_environment(self.root)
        self.assertEqual("true", env["AWS_EC2_METADATA_DISABLED"])
        for key in ("AWS_PROFILE", "AWS_CONTAINER_CREDENTIALS_RELATIVE_URI", "HCP_TOKEN"):
            self.assertNotIn(key, env)
        self.assertEqual("", Path(env["AWS_CONFIG_FILE"]).read_text(encoding="utf-8"))
        self.assertEqual("", Path(env["AWS_SHARED_CREDENTIALS_FILE"]).read_text(encoding="utf-8"))

    def test_environment_names_are_filtered_before_credential_values_are_read(self):
        class UnreadableCredentials(dict):
            def __getitem__(self, key):
                if key.upper().startswith(("AWS_", "TF_", "TFC_", "HCP_")):
                    raise AssertionError("Credential value must not be read")
                return super().__getitem__(key)

        names = UnreadableCredentials(AWS_ACCESS_KEY_ID=None, AWS_CONFIG_FILE=None, TF_TOKEN_app_terraform_io=None, PATH="synthetic")
        with patch.object(os, "environ", names):
            env = offline.safe_environment(self.root)
        self.assertEqual("synthetic", env["PATH"])
        self.assertNotIn("AWS_ACCESS_KEY_ID", env)

    def test_extra_source_and_external_provider_lock_are_rejected(self):
        for name in ("extra.tf", ".terraform.lock.hcl", "state.json"):
            path = self.fixture / name
            path.write_text("synthetic", encoding="utf-8")
            with self.assertRaises(gates.Violation):
                synthetic.validate(self.fixture)
            path.unlink()
