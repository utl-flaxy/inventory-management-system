"""Restricted JSON Terraform dialect for the public, plan-only synthetic root.

This is a capability allowlist, not a general HCL parser. Expanding its dialect
requires review. In particular terraform_data may otherwise run provisioners.
"""
import re

import quality_gates as gates
from terraform_preflight import blocked_record, strict_json

FILES = {"README.md", "backend.tf.json", "main.tf.json", "tests/contracts.tftest.json", "preflight.public.json"}
# Run identity, exact public input and expected failing variable form one
# contract. Eight passing runs alone must not erase the five negative cases.
RUN_CONTRACTS = {
    "defaults": ({}, None),
    "separate_fixture": ({"fixture_label": "synthetic-b", "sequence": 1}, None),
    "upper_boundary": ({"sequence": 2}, None),
    "unknown_label": ({"fixture_label": "invalid-fixture"}, "var.fixture_label"),
    "empty_label": ({"fixture_label": ""}, "var.fixture_label"),
    "negative_sequence": ({"sequence": -1}, "var.sequence"),
    "above_boundary": ({"sequence": 3}, "var.sequence"),
    "fractional_sequence": ({"sequence": 0.5}, "var.sequence"),
}


def require(condition):
    if not condition:
        raise gates.Violation("Synthetic root exceeds the reviewed offline capability contract")


def validate(directory):
    paths = {p.relative_to(directory).as_posix() for p in directory.rglob("*") if p.is_file()}
    require(paths == FILES)
    require(not any(p.is_symlink() for p in directory.rglob("*")))
    read = lambda name: strict_json((directory / name).read_text(encoding="utf-8"))
    backend = read("backend.tf.json")
    require(backend == {"terraform": {"backend": {"s3": {"use_lockfile": True, "encrypt": True}}}})
    require(all(type(value) is bool for value in backend["terraform"]["backend"]["s3"].values()))
    config = read("main.tf.json")
    require(isinstance(config, dict) and set(config) == {"terraform", "variable", "resource", "output"})
    require(config["terraform"] == {"required_version": "= 1.15.6"})
    # No provider/module/data/import/provisioner, functions, files or remote state
    # can be smuggled through the single permitted resource/output expression.
    require(config["resource"] == {"terraform_data": {"fixture": {"input": {
        "label": "${var.fixture_label}", "sequence": "${var.sequence}", "purpose": "offline-plan-only"}}}})
    require(config["output"] == {"planned_fixture": {"value": "${terraform_data.fixture.input}"}})
    variables = config["variable"]
    require(isinstance(variables, dict) and set(variables) == {"fixture_label", "sequence"})
    for name, kind, default, condition in (
        ("fixture_label", "string", "synthetic-a", '${contains(["synthetic-a", "synthetic-b"], var.fixture_label)}'),
        ("sequence", "number", 0, "${var.sequence >= 0 && var.sequence <= 2 && floor(var.sequence) == var.sequence}"),
    ):
        entry = variables[name]
        require(isinstance(entry, dict) and set(entry) == {"type", "default", "validation"})
        require(entry["type"] == kind and type(entry["default"]) is type(default) and entry["default"] == default)
        validation = entry["validation"]
        require(isinstance(validation, dict) and set(validation) == {"condition", "error_message"})
        require(validation["condition"] == condition)
        require(isinstance(validation["error_message"], str) and validation["error_message"] in {
            "Only public synthetic labels are accepted.", "Use an integer from zero through two."})
    tests = read("tests/contracts.tftest.json")
    require(isinstance(tests, dict) and set(tests) == {"run"} and isinstance(tests["run"], dict) and set(tests["run"]) == set(RUN_CONTRACTS))
    for name, run in tests["run"].items():
        expected_inputs, expected_failure = RUN_CONTRACTS[name]
        require(isinstance(run, dict) and set(run) <= {"command", "variables", "assert", "expect_failures"})
        require(run.get("command") == "plan")  # Terraform's omitted default is apply.
        inputs = run.get("variables", {})
        require(isinstance(inputs, dict) and set(inputs) == set(expected_inputs))
        require(all(type(inputs[key]) is type(value) and inputs[key] == value for key, value in expected_inputs.items()))
        if expected_failure is not None:
            require(set(run) == {"command", "variables", "expect_failures"} and run["expect_failures"] == [expected_failure])
        else:
            require("expect_failures" not in run)
            require(isinstance(run.get("assert"), list) and len(run["assert"]) > 0)
            for assertion in run["assert"]:
                require(isinstance(assertion, dict) and set(assertion) == {"condition", "error_message"})
                # Only known public comparisons joined by &&. No functions,
                # interpolation nesting, resource creation or external reads.
                expression = assertion["condition"]
                require(isinstance(expression, str) and expression.startswith("${") and expression.endswith("}"))
                atom = r'(?:output\.planned_fixture|terraform_data\.fixture\.input)\.(?:label|sequence|purpose) == (?:[012]|"(?:synthetic-[ab]|offline-plan-only)")'
                require(re.fullmatch(atom + r"(?: && " + atom + r")*", expression[2:-1]) is not None)
                require(isinstance(assertion["error_message"], str) and re.fullmatch(r"[A-Za-z .-]{1,100}", assertion["error_message"]) is not None)
    return blocked_record(read("preflight.public.json"))
