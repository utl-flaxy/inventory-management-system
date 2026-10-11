import importlib.util
from datetime import date, timedelta
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace

spec = importlib.util.spec_from_file_location("quality_gates", Path(__file__).parents[1] / "quality_gates.py")
gates = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gates)


class QualityGatePolicyTest(unittest.TestCase):
    def test_checksum_mismatch_rejects_download(self):
        with self.assertRaises(gates.OperationalError):
            gates.verify_digest(b"altered binary", "0" * 64)

    def test_reviewed_checksum_accepts_exact_bytes(self):
        gates.verify_digest(b"reviewed binary", hashlib.sha256(b"reviewed binary").hexdigest())

    def test_missing_scanner_fails_closed(self):
        with self.assertRaises(gates.OperationalError):
            gates.checked_binary("/scanner-does-not-exist")

    def test_missing_report_fails_closed(self):
        with self.assertRaises(gates.OperationalError):
            gates.read_json("/report-does-not-exist")

    def test_scanner_failure_cannot_be_a_clean_secret_scan(self):
        with self.assertRaises(gates.OperationalError):
            gates.classify_secrets(127, [], {})

    def test_secret_failure_requires_findings(self):
        with self.assertRaises(gates.OperationalError):
            gates.classify_secrets(1, [], {})

    def test_secret_success_cannot_hide_findings(self):
        with self.assertRaises(gates.OperationalError):
            gates.classify_secrets(0, [{"File": "public.txt", "StartLine": 1, "RuleID": "rule"}], {})

    def test_unapproved_finding_is_not_a_false_positive(self):
        result = gates.classify_secrets(1, [{"File": "public.txt", "StartLine": 1, "RuleID": "rule"}], {})
        self.assertFalse(result[0]["documented_false_positive"])
        self.assertEqual({"path", "line", "rule", "documented_false_positive"}, set(result[0]))

    def test_secret_report_rejects_escaping_path(self):
        with self.assertRaises(gates.OperationalError):
            gates.classify_secrets(1, [{"File": "../outside.txt", "StartLine": 1, "RuleID": "rule"}], {})

    def test_only_exact_reviewed_location_is_exempt(self):
        approved = {("public.txt", 1, "rule"): {}}
        result = gates.classify_secrets(1, [{"File": "public.txt", "StartLine": 2, "RuleID": "rule"}], approved)
        self.assertFalse(result[0]["documented_false_positive"])

    def test_malformed_secret_findings_fail_closed(self):
        for finding in ({}, {"File": "a", "StartLine": 0, "RuleID": "rule"}, "invalid"):
            with self.assertRaises(gates.OperationalError):
                gates.classify_secrets(1, [finding], {})

    def test_tool_startup_and_timeout_are_operational_errors(self):
        import subprocess
        for error in (OSError("not executable"), subprocess.TimeoutExpired("scanner", 180)):
            with patch.object(gates.subprocess, "run", side_effect=error):
                with self.assertRaises(gates.OperationalError):
                    gates.execute(["scanner"])

    def test_no_tracked_files_is_an_error(self):
        with tempfile.TemporaryDirectory() as temporary:
            with patch.object(gates, "execute", return_value=type("Run", (), {"returncode": 0, "stdout": b""})()):
                with self.assertRaises(gates.OperationalError):
                    gates.tracked_snapshot(Path(temporary), Path(temporary))

    def test_untracked_private_file_is_not_copied(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "root"
            root.mkdir()
            output = Path(temporary) / "snapshot"
            output.mkdir()
            (root / "public.txt").write_text("public content")
            (root / ".env").write_text("not scanned")
            record = b"100644 " + b"a" * 40 + b" 0\tpublic.txt\0"
            with patch.object(gates, "execute", return_value=type("Run", (), {"returncode": 0, "stdout": record})()):
                self.assertEqual(1, gates.tracked_snapshot(root, output))
            self.assertTrue((output / "public.txt").is_file())
            self.assertFalse((output / ".env").exists())

    def test_expired_changed_and_unbounded_exceptions_rejected(self):
        today = date(2026, 10, 12)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "public.txt"
            source.write_text("documented fixture\n")
            entry = {"path": "public.txt", "line": 1, "rule": "rule",
                     "sha256_lf": hashlib.sha256(source.read_bytes().replace(b"\r\n", b"\n")).hexdigest(),
                     "reason": "Public synthetic fixture", "owner": "Maintainer", "expires": "2026-11-11"}
            policy = root / "exceptions.json"
            gates.write_json(policy, {"secret_false_positives": [entry]})
            self.assertEqual(1, len(gates.exceptions(policy, root, today)))
            for expiry in (today.isoformat(), (today + timedelta(days=91)).isoformat()):
                entry["expires"] = expiry
                gates.write_json(policy, {"secret_false_positives": [entry]})
                with self.assertRaises(gates.OperationalError):
                    gates.exceptions(policy, root, today)
            entry["expires"] = "2026-11-11"
            gates.write_json(policy, {"secret_false_positives": [entry]})
            source.write_text("changed content")
            with self.assertRaises(gates.OperationalError):
                gates.exceptions(policy, root, today)

    def test_empty_static_input_is_not_a_pass(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with self.assertRaises(gates.OperationalError):
                gates.static_report(root / "report.xml", root)

    def test_missing_static_report_is_not_a_pass(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "Probe.class").write_bytes(b"fixture")
            with self.assertRaises(gates.OperationalError):
                gates.static_report(root / "missing.xml", root)

    def test_empty_and_failed_static_analysis_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "Probe.class").write_bytes(b"fixture")
            report = root / "report.xml"
            for summary, errors in (('total_classes="0"', '<Errors errors="0"/>'),
                                    ('total_classes="1"', '<Errors errors="1"><Error/></Errors>'),
                                    ('total_classes="1"', '<Errors errors="0"><MissingClass/></Errors>')):
                report.write_text("<BugCollection><FindBugsSummary " + summary + "/>" + errors + "</BugCollection>")
                with self.assertRaises(gates.OperationalError):
                    gates.static_report(report, root)

    def test_static_violation_stays_in_report(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "Probe.class").write_bytes(b"fixture")
            report = root / "report.xml"
            report.write_text('<BugCollection><FindBugsSummary total_classes="1"/><Errors errors="0"/>'
                              '<BugInstance type="SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE"/></BugCollection>')
            analyzed, bugs = gates.static_report(report, root)
            self.assertEqual(1, analyzed)
            self.assertEqual(1, len(bugs))

    def test_static_gate_success_violation_and_compile_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with patch.object(gates, "execute", return_value=SimpleNamespace(returncode=0)):
                with patch.object(gates, "run_static", return_value=(1, [], 0)):
                    gates.static(root)
                with patch.object(gates, "run_static", return_value=(1, [object()], 1)):
                    with self.assertRaises(gates.Violation):
                        gates.static(root)
                with patch.object(gates, "run_static", side_effect=gates.OperationalError("scanner failed")):
                    with self.assertRaises(gates.OperationalError):
                        gates.static(root)
            with patch.object(gates, "execute", return_value=SimpleNamespace(returncode=1)):
                with self.assertRaises(gates.OperationalError):
                    gates.static(root)

    def test_static_scanner_crash_is_not_a_gate_violation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            classes = root / "target/classes"
            classes.mkdir(parents=True)
            (classes / "Probe.class").write_bytes(b"fixture")
            def failed_scanner(*args, **kwargs):
                (root / "target/spotbugsXml.xml").write_text(
                    '<BugCollection><FindBugsSummary total_classes="1"/><Errors errors="0"/>'
                    '<BugInstance type="SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE"/></BugCollection>')
                return SimpleNamespace(returncode=2)
            with patch.object(gates, "execute", side_effect=failed_scanner):
                with self.assertRaises(gates.OperationalError):
                    gates.run_static(root)

    def test_cli_operational_failure_returns_two(self):
        with patch.object(gates.sys, "argv", ["quality_gates.py", "secrets"]):
            self.assertEqual(2, gates.main())

    def test_cli_gate_violation_returns_one(self):
        with patch.object(gates.sys, "argv", ["quality_gates.py", "static"]):
            with patch.object(gates, "static", side_effect=gates.Violation("synthetic violation")):
                self.assertEqual(1, gates.main())

    def test_dependency_network_error_is_not_a_finding(self):
        with self.assertRaises(gates.OperationalError):
            gates.dependency_result(127, {"results": []})

    def test_no_dependency_packages_is_an_error(self):
        with self.assertRaises(gates.OperationalError):
            gates.dependency_result(128, {"results": []})

    def test_inconsistent_dependency_result_is_rejected(self):
        report = {"results": [{"packages": [{"vulnerabilities": [{"id": "GHSA-fixture"}]}]}]}
        with self.assertRaises(gates.OperationalError):
            gates.dependency_result(0, report)

    def test_audit_retains_findings_without_claiming_security_pass(self):
        report = {"results": [{"packages": [{"vulnerabilities": [{"id": "GHSA-fixture"}]}]}]}
        packages, findings = gates.dependency_result(1, report)
        self.assertEqual(1, len(packages))
        self.assertEqual({"GHSA-fixture"}, set(findings))

    def test_dependency_coverage_checks_identity_not_only_count(self):
        components = [{"name": "a", "version": "1"}, {"name": "b", "version": "2"}]
        packages = [{"package": {"name": name, "version": version, "ecosystem": "Maven"}}
                    for name, version in (("group:a", "1"), ("b", "2"))]
        gates.dependency_coverage(packages, components)
        for incorrect in (packages[:1], [packages[0], packages[0]]):
            with self.assertRaises(gates.OperationalError):
                gates.dependency_coverage(incorrect, components)


if __name__ == "__main__":
    unittest.main()
