"""Secret/static gates and a separate, explicitly non-passing dependency audit.

Exit 1: a real gate violation. Exit 2: invalid input, incomplete analysis or an
operational failure. Diagnostics contain counts/IDs, never secret snippets.
"""
import argparse
from collections import Counter
from datetime import date, datetime, timedelta, timezone
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

SPOTBUGS = "com.github.spotbugs:spotbugs-maven-plugin:4.10.4.1:check"
TOOLS = {
    "gitleaks": {
        "version": "8.30.1",
        "linux": ("https://github.com/gitleaks/gitleaks/releases/download/v8.30.1/gitleaks_8.30.1_linux_x64.tar.gz",
                  "551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb", "gitleaks"),
        "windows": ("https://github.com/gitleaks/gitleaks/releases/download/v8.30.1/gitleaks_8.30.1_windows_x64.zip",
                    "d29144deff3a68aa93ced33dddf84b7fdc26070add4aa0f4513094c8332afc4e", "gitleaks.exe"),
    },
    "osv": {
        "version": "2.6.0",
        "linux": ("https://github.com/google/osv-scanner/releases/download/v2.6.0/osv-scanner_linux_amd64",
                  "ca69b3d3cd08f889a49dc0a383122f71cc528b83803671df5fd874d97485b108", None),
        "windows": ("https://github.com/google/osv-scanner/releases/download/v2.6.0/osv-scanner_windows_amd64.exe",
                    "e0ed7644118b717b028c249ee9d3515024e55e8510747ca08906eb96765354d6", None),
    },
}


class OperationalError(Exception):
    pass


class Violation(Exception):
    pass


def read_json(path):
    try:
        return json.loads(Path(path).read_text(encoding="utf-8-sig"))
    except (OSError, ValueError):
        raise OperationalError("Missing or invalid JSON report") from None


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=True) + "\n", encoding="utf-8")


def summary(message):
    print(message)
    output = os.environ.get("GITHUB_STEP_SUMMARY")
    if output:
        with open(output, "a", encoding="utf-8") as stream:
            stream.write(message + "\n\n")


def execute(arguments, cwd=None):
    try:
        return subprocess.run([str(arg) for arg in arguments], cwd=cwd, capture_output=True, timeout=180)
    except (OSError, subprocess.TimeoutExpired):
        raise OperationalError("Tool execution failed or timed out") from None


def checked_binary(path):
    path = Path(path).resolve()
    if not path.is_file():
        raise OperationalError("Scanner binary not found")
    return path


def verify_digest(data, expected):
    if hashlib.sha256(data).hexdigest() != expected:
        raise OperationalError("Scanner SHA256 mismatch")


def download(tool, output):
    platform = "windows" if os.name == "nt" else "linux"
    if __import__("platform").machine().lower() not in {"amd64", "x86_64"}:
        raise OperationalError("Only reviewed x64 binaries are supported")
    url, digest, member = TOOLS[tool][platform]
    try:
        with urllib.request.urlopen(url, timeout=60) as response:
            data = response.read(100_000_001)
        if len(data) > 100_000_000:
            raise OperationalError("Unexpected scanner asset size")
        verify_digest(data, digest)
        if member and platform == "windows":
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                data = archive.read(member)
        elif member:
            with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
                entry = archive.getmember(member)
                if not entry.isfile():
                    raise OperationalError("Unexpected scanner archive entry")
                data = archive.extractfile(entry).read()
    except (OSError, KeyError, ValueError, tarfile.TarError, zipfile.BadZipFile):
        raise OperationalError("Official scanner download/extraction failed") from None
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(data)
    output.chmod(0o755)
    summary(f"Installed {tool} {TOOLS[tool]['version']}; reviewed SHA256 verified")


def forbidden_terraform_path(name):
    """Reject private/generated Terraform inputs before reading their contents.

    Gitignore does not protect an already tracked or force-added file. Apply this
    policy across bootstrap, migration and future roots, independent of content.
    Provider dependency lockfiles are intentionally permitted.
    """
    path = PurePosixPath(name.replace("\\", "/"))
    base = path.name.lower()
    return (".terraform" in path.parts or ".aws" in path.parts or
            ".tfstate" in base or ".tfvars" in base or ".tfplan" in base or ".plan." in base or
            base.endswith((".tfbackend", ".backend.hcl", ".backend.conf", ".tfplan", ".plan")) or
            base in {"backend.hcl", "backend.conf", "plan", "credentials", "credentials.tfrc.json", ".terraformrc"} or
            base.startswith(("tfplan", "plan.", "credentials.", "backend-private", "backend.private")) or
            base.startswith("backend-") and base.endswith((".hcl", ".conf")))


def tracked_snapshot(root, destination):
    result = execute(["git", "ls-files", "--stage", "-z"], root)
    if result.returncode:
        raise OperationalError("Cannot enumerate tracked files")
    count = 0
    for entry in result.stdout.decode("utf-8").split("\0"):
        if not entry:
            continue
        metadata, name = entry.split("\t", 1)
        mode, _, stage = metadata.split()
        relative = PurePosixPath(name)
        if mode not in {"100644", "100755"} or stage != "0":
            raise OperationalError("Symlinks, submodules and unresolved entries are unsupported")
        if relative.is_absolute() or ".." in relative.parts or ".git" in relative.parts:
            raise OperationalError("Unsafe tracked path")
        if forbidden_terraform_path(name):
            raise Violation("Tracked private/generated Terraform file rejected (contents not read)")
        source = root / name
        if not source.is_file() or source.is_symlink() or not source.resolve().is_relative_to(root):
            raise OperationalError("Missing or unsafe tracked file")
        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
        count += 1
    if not count:
        raise OperationalError("No tracked files found")
    return count


def exceptions(path, root, today=None):
    today = today or datetime.now(timezone.utc).date()
    document = read_json(path)
    if not isinstance(document, dict) or set(document) != {"secret_false_positives"}:
        raise OperationalError("Invalid secret exception policy")
    result = {}
    for entry in document["secret_false_positives"]:
        if set(entry) != {"path", "line", "rule", "sha256_lf", "reason", "owner", "expires"}:
            raise OperationalError("Incomplete secret exception")
        relative = PurePosixPath(entry["path"])
        if relative.is_absolute() or ".." in relative.parts or "*" in entry["path"]:
            raise OperationalError("Secret exception must name one exact file")
        if not isinstance(entry["line"], int) or entry["line"] < 1 or not entry["reason"] or not entry["owner"]:
            raise OperationalError("Invalid secret exception metadata")
        try:
            expiry = date.fromisoformat(entry["expires"])
        except ValueError:
            raise OperationalError("Invalid exception expiry") from None
        if not today < expiry <= today + timedelta(days=90):
            raise OperationalError("Secret exception expired or exceeds 90 days")
        source = root / relative
        if not source.is_file() or source.is_symlink() or not source.resolve().is_relative_to(root):
            raise OperationalError("Secret exception source missing")
        digest = hashlib.sha256(source.read_bytes().replace(b"\r\n", b"\n")).hexdigest()
        if digest != entry["sha256_lf"]:
            raise OperationalError("Exception source changed; fresh review required")
        key = (entry["path"], entry["line"], entry["rule"])
        if key in result:
            raise OperationalError("Duplicate secret exception")
        result[key] = entry
    return result


def classify_secrets(exit_code, report, approved):
    if not isinstance(report, list) or exit_code not in {0, 1} or (exit_code == 1 and not report):
        raise OperationalError("Incomplete secret scan or scanner failure")
    if exit_code == 0 and report:
        raise OperationalError("Inconsistent secret scanner result")
    safe = []
    for finding in report:
        if (not isinstance(finding, dict) or not isinstance(finding.get("File"), str)
                or not isinstance(finding.get("StartLine"), int) or finding["StartLine"] < 1
                or not isinstance(finding.get("RuleID"), str) or not finding["RuleID"]):
            raise OperationalError("Incomplete secret finding")
        path = finding.get("File", "").replace("\\", "/")
        if not path or PurePosixPath(path).is_absolute() or ".." in PurePosixPath(path).parts:
            raise OperationalError("Invalid finding path")
        key = (path, finding.get("StartLine"), finding.get("RuleID"))
        safe.append({"path": path, "line": key[1], "rule": key[2], "documented_false_positive": key in approved})
    return safe


def run_secret(scanner, source, config, report):
    ignore = report.parent / "empty-ignore"
    ignore.write_text("", encoding="utf-8")
    return execute([scanner, "dir", ".", "--config", config, "--gitleaks-ignore-path", ignore,
                    "--ignore-gitleaks-allow", "--redact=100", "--no-banner", "--no-color",
                    "--report-format=json", "--report-path", report], source)


def secrets(root, scanner):
    approved = exceptions(root / ".github/security/exceptions.json", root)
    with tempfile.TemporaryDirectory(prefix="quality-secret-") as directory:
        temporary = Path(directory)
        snapshot = temporary / "snapshot"
        snapshot.mkdir()
        count = tracked_snapshot(root, snapshot)
        report = temporary / "redacted.json"
        result = run_secret(scanner, snapshot, root / ".github/security/gitleaks.toml", report)
        findings = classify_secrets(result.returncode, read_json(report), approved)
    unresolved = sum(not item["documented_false_positive"] for item in findings)
    write_json(root / "target/quality-gates/secrets.json",
               {"tracked_files": count, "findings": findings, "unresolved": unresolved})
    summary(f"secret-scan: files={count}, documented false positives={len(findings)-unresolved}, unresolved={unresolved}")
    if unresolved:
        raise Violation("Secret gate rejected unapproved findings (values redacted)")
    summary("secret-scan: PASS")


def secret_self_test(root, scanner):
    with tempfile.TemporaryDirectory(prefix="quality-secret-test-") as directory:
        source = Path(directory) / "input"
        source.mkdir()
        report = Path(directory) / "redacted.json"
        (source / "safe.txt").write_text("No credentials in this file.\n", encoding="utf-8")
        result = run_secret(scanner, source, root / ".github/security/gitleaks.toml", report)
        if result.returncode != 0 or classify_secrets(result.returncode, read_json(report), {}):
            raise OperationalError("Secret clean fixture did not pass")
        # Public, deliberately synthetic test token; never issued, validated, logged or committed as a credential.
        fixture = "ghp_" + "A0b1c2D3e4F5g6H7i8J9k0L1m2N3o4P5q6R7"
        (source / "bad.txt").write_text('token = "' + fixture + '"\n', encoding="utf-8")
        result = run_secret(scanner, source, root / ".github/security/gitleaks.toml", report)
        findings = classify_secrets(result.returncode, read_json(report), {})
        if result.returncode != 1 or not findings:
            raise OperationalError("Real secret scanner did not reject synthetic fixture")
        if fixture in report.read_text(encoding="utf-8") or fixture.encode() in result.stdout + result.stderr:
            raise OperationalError("Secret scanner redaction regression")
        # Execute the identical workflow entry point, not only the underlying scanner.
        (source / ".github/security").mkdir(parents=True)
        shutil.copyfile(root / ".github/security/gitleaks.toml", source / ".github/security/gitleaks.toml")
        write_json(source / ".github/security/exceptions.json", {"secret_false_positives": []})
        if execute(["git", "init", "--quiet", source]).returncode or execute(["git", "add", "."], source).returncode:
            raise OperationalError("Cannot prepare synthetic uncommitted repository")
        child = execute([sys.executable, Path(__file__).resolve(), "secrets", "--root", source, "--scanner", scanner])
        if child.returncode != 1 or fixture.encode() in child.stdout + child.stderr:
            raise OperationalError("Workflow secret entry point did not fail safely")
    summary("secret-self-test: clean PASS; scanner exit=1; workflow entry point exit=1; output redacted")


def static_report(path, classes):
    if not classes.is_dir() or not any(classes.rglob("*.class")):
        raise OperationalError("No compiled application classes")
    try:
        report = ET.parse(path).getroot()
        analyzed = int(report.find("FindBugsSummary").get("total_classes", "0"))
    except (OSError, ET.ParseError, AttributeError, ValueError):
        raise OperationalError("Missing or invalid static analysis report") from None
    if report.tag != "BugCollection" or analyzed == 0 or report.find("Errors") is None:
        raise OperationalError("Static analysis is empty/incomplete")
    errors = report.find("Errors")
    if int(errors.get("errors", "0")) or list(errors):
        raise OperationalError("Static analysis had errors or missing classes")
    return analyzed, report.findall("BugInstance")


def run_static(root, project=None):
    project = project or root
    classes = project / "target/classes"
    if not classes.is_dir() or not any(classes.rglob("*.class")):
        raise OperationalError("No compiled application classes")
    report = project / "target/spotbugsXml.xml"
    report.unlink(missing_ok=True)  # A skipped/failed run must not reuse yesterday's PASS.
    maven = root / ("mvnw.cmd" if os.name == "nt" else "mvnw")
    result = execute([maven, "--batch-mode", "--no-transfer-progress", "-f", project / "pom.xml", SPOTBUGS,
                      "-Dspotbugs.skip=false", "-Dspotbugs.failOnError=true", "-Dspotbugs.maxAllowedViolations=0",
                      "-Dspotbugs.effort=Max", "-Dspotbugs.threshold=Low", "-Dspotbugs.xmlOutput=true",
                      "-Dspotbugs.includeFilterFile=" + str(root / ".github/security/spotbugs-security.xml")], root)
    analyzed, bugs = static_report(report, classes)
    if result.returncode not in {0, 1} or (result.returncode and not bugs):
        raise OperationalError("Maven/static scanner failed without a complete violation report")
    return analyzed, bugs, result.returncode


def static(root):
    maven = root / ("mvnw.cmd" if os.name == "nt" else "mvnw")
    result = execute([maven, "--batch-mode", "--no-transfer-progress", "compile"], root)
    if result.returncode:
        raise OperationalError("Application compilation failed")
    analyzed, bugs, exit_code = run_static(root)
    write_json(root / "target/quality-gates/static.json",
               {"analyzed_classes": analyzed, "violations": len(bugs), "scanner_exit": exit_code})
    summary(f"java-static-analysis: classes={analyzed}, SECURITY violations={len(bugs)}, scanner_exit={exit_code}")
    if bugs or exit_code:
        raise Violation("Java security static gate rejected findings")
    summary("java-static-analysis: PASS")


def static_self_test(root):
    with tempfile.TemporaryDirectory(prefix="quality-static-test-") as directory:
        project = Path(directory)
        (project / "pom.xml").write_text('<project><modelVersion>4.0.0</modelVersion><groupId>quality.fixture</groupId>'
                                       '<artifactId>fixture</artifactId><version>1</version></project>', encoding="utf-8")
        classes = project / "target/classes"
        classes.mkdir(parents=True)
        java = project / "Probe.java"
        for unsafe in (False, True):
            statement = """s.executeQuery("select id from inventory where name = '" + name + "'");""" if unsafe else 's.executeQuery("select id from inventory");'
            java.write_text('public class Probe { public void query(java.sql.Statement s, String name) '
                            'throws java.sql.SQLException { ' + statement + ' } }', encoding="utf-8")
            if execute(["javac", "--release", "17", "-d", classes, java]).returncode:
                raise OperationalError("Static fixture compilation failed")
            analyzed, bugs, exit_code = run_static(root, project)
            if unsafe:
                if exit_code == 0 or not any(bug.get("type", "").startswith("SQL_") for bug in bugs):
                    raise OperationalError("Real static scanner did not reject nonconstant SQL")
            elif exit_code or bugs:
                raise OperationalError("Static clean fixture did not pass")
        # Reuse the project's wrapper distribution and the exact workflow CLI.
        # The fixture has already been compiled with Java 17, without application dependencies.
        shutil.copytree(root / ".mvn", project / ".mvn")
        for name in ("mvnw", "mvnw.cmd"):
            shutil.copy2(root / name, project / name)
        (project / ".github/security").mkdir(parents=True)
        shutil.copyfile(root / ".github/security/spotbugs-security.xml",
                        project / ".github/security/spotbugs-security.xml")
        child = execute([sys.executable, Path(__file__).resolve(), "static", "--root", project])
        if child.returncode != 1:
            raise OperationalError("Workflow static entry point did not reject unsafe fixture")
    summary("static-self-test: clean PASS; nonconstant SQL rejected; real Maven exit=1; workflow entry point exit=1")


def dependency_result(exit_code, report):
    if exit_code not in {0, 1} or not isinstance(report, dict) or not report.get("results"):
        raise OperationalError("Dependency scanner failed or found no packages")
    packages = [item for source in report["results"] for item in source.get("packages", [])]
    if not packages:
        raise OperationalError("Empty dependency analysis")
    findings = {vuln["id"]: vuln for package in packages for vuln in package.get("vulnerabilities", [])}
    if bool(findings) != (exit_code == 1):
        raise OperationalError("Inconsistent dependency result")
    return packages, findings


def dependency_coverage(packages, components):
    # OSV's CycloneDX extractor displays artifact names without the Maven group.
    # Verify every resolved artifact/version, not just the number of packages.
    expected = Counter((item["name"], item["version"]) for item in components)
    actual = Counter((item["package"]["name"].split(":")[-1], item["package"]["version"])
                     for item in packages if item["package"].get("ecosystem") == "Maven")
    if actual != expected:
        raise OperationalError("Dependency package coverage differs from resolved SBOM")


def dependencies(root, scanner):
    sbom = root / "target/bom.json"
    document = read_json(sbom)
    components = document.get("components", [])
    if document.get("bomFormat") != "CycloneDX" or not components or any(
            not item.get("purl", "").startswith("pkg:maven/") or not item.get("version") for item in components):
        raise OperationalError("Missing/invalid resolved Maven SBOM")
    output = root / "target/quality-gates/dependency-audit.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.unlink(missing_ok=True)
    with tempfile.TemporaryDirectory(prefix="quality-osv-") as directory:
        config = Path(directory) / "empty-config.toml"
        config.write_text("# No vulnerability suppression or package override.\n", encoding="utf-8")
        result = execute([scanner, "scan", "source", "-L", sbom, "--all-packages", "--config", config,
                          "--format=json", "--output-file", output], root)
    packages, findings = dependency_result(result.returncode, read_json(output))
    dependency_coverage(packages, components)
    counts = {}
    for item in findings.values():
        severity = item.get("database_specific", {}).get("severity", "UNKNOWN")
        counts[severity] = counts.get(severity, 0) + 1
    write_json(root / "target/quality-gates/dependency-summary.json",
               {"mode": "AUDIT_ONLY_NOT_A_SECURITY_PASS", "components": len(components), "advisories": len(findings),
                "severity": counts, "scanner_exit": result.returncode})
    # Explicit user decision: retain all findings for separate remediation; operational errors still fail.
    summary(f"dependency-audit: AUDIT ONLY, security result=FINDINGS UNREMEDIATED; packages={len(packages)}, advisories={len(findings)}, severity={counts}, scanner_exit={result.returncode}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("download", "secrets", "secret-self-test", "static", "static-self-test", "dependencies"))
    parser.add_argument("tool", nargs="?", choices=TOOLS)
    parser.add_argument("--root", default=".")
    parser.add_argument("--scanner")
    parser.add_argument("--output")
    args = parser.parse_args()
    root = Path(args.root).resolve()
    try:
        if args.command == "download":
            if not args.tool or not args.output:
                raise OperationalError("Tool and output are required")
            download(args.tool, args.output)
        elif args.command in {"secrets", "secret-self-test", "dependencies"}:
            scanner = checked_binary(args.scanner) if args.scanner else None
            if scanner is None:
                raise OperationalError("Scanner is required")
            {"secrets": secrets, "secret-self-test": secret_self_test, "dependencies": dependencies}[args.command](root, scanner)
        else:
            {"static": static, "static-self-test": static_self_test}[args.command](root)
    except Violation as error:
        print(str(error), file=sys.stderr)
        return 1
    except (OperationalError, OSError, ValueError, KeyError, TypeError) as error:
        # Do not print tool output, arbitrary exception messages or secret values.
        print("QUALITY GATE OPERATIONAL FAILURE: " + (str(error) if isinstance(error, OperationalError) else type(error).__name__), file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
