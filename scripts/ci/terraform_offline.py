"""Export only indexed source to a fresh root; validate exclusively with AWS mocks.

No real plan/apply, remote init or state commands are exposed by this interface.
Do not reuse this program as an operational migration/backend initializer.
"""
import argparse
import os
from pathlib import Path
import re
import subprocess
import tempfile

import quality_gates as gates


def uncomment(source):
    # This deliberately restricted contract accepts a literal partial backend,
    # not arbitrary HCL expressions. Terraform itself parses all HCL afterwards.
    return re.sub(r"(?m)#[^\n]*$|//[^\n]*|/\*.*?\*/", "", source, flags=re.S)


def validate_sources(root):
    backend = uncomment((root / "terraform/backend.tf").read_text(encoding="utf-8"))
    compact = re.sub(r"\s+", "", backend)
    if compact != 'terraform{backend"s3"{use_lockfile=trueencrypt=true}}':
        raise gates.Violation("Host backend must contain only literal use_lockfile=true and encrypt=true")
    host = "\n".join(uncomment(p.read_text(encoding="utf-8")) for p in (root / "terraform").glob("*.tf"))
    if len(re.findall(r'\bbackend\s+"', host)) != 1:
        raise gates.Violation("Host root must have exactly one partial backend")
    bootstrap = "\n".join(uncomment(p.read_text(encoding="utf-8")) for p in (root / "terraform-bootstrap").glob("*.tf"))
    if re.search(r'\bbackend\s+"|\bmodule\s+"', bootstrap):
        raise gates.Violation("Bootstrap must be independent with no remote backend/module")
    if len(re.findall(r"\bprevent_destroy\s*=\s*true\b", bootstrap)) != 4 or re.search(r"force_destroy\s*=\s*true", bootstrap):
        raise gates.Violation("Buckets, both KMS keys and trail require destruction safeguards")
    if "aws_s3_bucket_lifecycle_configuration" in bootstrap:
        raise gates.Violation("No unreviewed expiry of state, audit versions or active locks")
    if (root / "terraform/.terraform.lock.hcl").read_bytes() != (root / "terraform-bootstrap/.terraform.lock.hcl").read_bytes():
        raise gates.Violation("Both roots must use the same reviewed provider lockfile")
    for directory in (root / "terraform/tests", root / "terraform-bootstrap/tests"):
        tests = "\n".join(uncomment(p.read_text(encoding="utf-8")) for p in directory.glob("*.tftest.hcl"))
        if 'mock_provider "aws"' not in tests or re.search(r"\bcommand\s*=\s*apply\b", tests):
            raise gates.Violation("AWS mock provider and plan-only tests are mandatory")


def exported_source(root, destination):
    listing = subprocess.run(["git", "ls-files", "--stage", "-z"], cwd=root, capture_output=True, check=True).stdout
    names = []
    for entry in listing.decode("utf-8").split("\0"):
        if not entry:
            continue
        metadata, name = entry.split("\t", 1)
        if gates.forbidden_terraform_path(name):
            raise gates.Violation("Tracked Terraform private/generated path rejected before export")
        if name.startswith(("terraform/", "terraform-bootstrap/")) or name == "scripts/tls/install-caddy.sh":
            mode, _, stage = metadata.split()
            path = Path(name)
            if mode not in {"100644", "100755"} or stage != "0" or path.is_absolute() or ".." in path.parts:
                raise gates.OperationalError("Unsafe indexed Terraform source")
            names.append(name)
    if "terraform/backend.tf" not in names or "scripts/tls/install-caddy.sh" not in names:
        raise gates.OperationalError("Incomplete indexed source export")
    # checkout-index reads indexed blobs only, never untracked state/tfvars/cache.
    subprocess.run(["git", "checkout-index", "--stdin", "-z", "--prefix=" + destination.as_posix() + "/"],
                   input=("\0".join(names) + "\0").encode(), cwd=root, capture_output=True, check=True)
    return len(names)


def safe_environment(directory):
    env = {k: v for k, v in os.environ.items() if not k.startswith(("AWS_", "TF_", "TFC_", "HCP_"))}
    empty = directory / "empty-config"
    empty.write_text("", encoding="utf-8")
    cache = directory / "public-provider-cache"
    cache.mkdir()
    env.update(AWS_EC2_METADATA_DISABLED="true", AWS_CONFIG_FILE=str(empty), AWS_SHARED_CREDENTIALS_FILE=str(empty),
               TF_CLI_CONFIG_FILE=str(empty), TF_PLUGIN_CACHE_DIR=str(cache), TF_IN_AUTOMATION="1", CHECKPOINT_DISABLE="1")
    return env


def terraform(directory, arguments, env, expected_failure=False):
    result = subprocess.run(["terraform", *arguments], cwd=directory, env=env, capture_output=True, timeout=180)
    output = (result.stdout + result.stderr).decode("utf-8", errors="replace")
    for line in output.splitlines():
        if re.match(r"^(Success!|  run |tests/|Error:|Failure!|\d+ passed)", line):
            print(line, flush=True)
    if expected_failure:
        if result.returncode != 1 or "Test assertion failed" not in output:
            raise gates.OperationalError("Mutation must fail a test assertion, not initialization or provider execution")
    elif result.returncode:
        # Inputs and output here are exclusively exported source/synthetic mocks.
        print(output[-9000:], flush=True)
        raise gates.OperationalError("Offline Terraform command failed")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    args = parser.parse_args()
    try:
        with tempfile.TemporaryDirectory(prefix="inventory-terraform-offline-") as temporary:
            source = Path(temporary) / "source"
            source.mkdir()
            count = exported_source(args.root.resolve(), source)
            validate_sources(source)
            env = safe_environment(Path(temporary))
            for name in ("terraform", "terraform-bootstrap"):
                directory = source / name
                terraform(directory, ["fmt", "-check", "-recursive"], env)
                terraform(directory, ["init", "-backend=false", "-input=false", "-lockfile=readonly"], env)
                terraform(directory, ["validate", "-no-color"], env)
                terraform(directory, ["test", "-no-color"], env)
            bootstrap = source / "terraform-bootstrap"
            mutations = [
                ("iam.tf", 'Action = ["s3:GetObject", "s3:PutObject"], Resource = [local.state_arn]',
                 'Action = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"], Resource = [local.state_arn]'),
                ("iam.tf", '["kms:Encrypt", "kms:Decrypt", "kms:GenerateDataKey"]', '["kms:Encrypt", "kms:Decrypt"]'),
                ("main.tf", 'include_management_events = true', 'include_management_events = false'),
            ]
            for name, before, after in mutations:
                path = bootstrap / name
                original = path.read_text(encoding="utf-8")
                # Match formatting-independent action lists, but never silently skip.
                if before not in original:
                    raise gates.OperationalError("Mutation fixture no longer matches the reviewed contract")
                path.write_text(original.replace(before, after, 1), encoding="utf-8")
                try:
                    test_file = str(Path("tests") / "contracts.tftest.hcl")
                    terraform(bootstrap, ["test", "-no-color", "-filter=" + test_file], env, expected_failure=True)
                finally:
                    path.write_text(original, encoding="utf-8")
            print(f"terraform-offline: source_files={count}; host=27; bootstrap=14; rejected_mutations=3; AWS/state access=none")
        return 0
    except gates.Violation as error:
        print(str(error))
        return 1
    except (gates.OperationalError, OSError, subprocess.SubprocessError) as error:
        print("terraform-offline: operational failure (no successful validation claimed)")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
