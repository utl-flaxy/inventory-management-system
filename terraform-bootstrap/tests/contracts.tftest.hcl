# Synthetic account and roles only. All runs plan with mocked AWS; no backend.
mock_provider "aws" {
  override_during = plan
}

override_resource {
  override_during = plan
  target          = aws_s3_bucket.protected["state"]
  values = {
    id  = "inv-sandbox-123456789012-ap-northeast-1-state"
    arn = "arn:aws:s3:::inv-sandbox-123456789012-ap-northeast-1-state"
  }
}
override_resource {
  override_during = plan
  target          = aws_s3_bucket.protected["audit"]
  values = {
    id  = "inv-sandbox-123456789012-ap-northeast-1-audit"
    arn = "arn:aws:s3:::inv-sandbox-123456789012-ap-northeast-1-audit"
  }
}

override_resource {
  override_during = plan
  target          = aws_kms_key.state
  values          = { arn = "arn:aws:kms:ap-northeast-1:123456789012:key/11111111-1111-4111-8111-111111111111" }
}
override_resource {
  override_during = plan
  target          = aws_kms_key.audit
  values          = { arn = "arn:aws:kms:ap-northeast-1:123456789012:key/22222222-2222-4222-8222-222222222222" }
}

variables {
  account_id  = "123456789012"
  region      = "ap-northeast-1"
  environment = "sandbox"
  roles = {
    management = "arn:aws:iam::123456789012:role/inventory-sandbox-state-management"
    operator   = "arn:aws:iam::123456789012:role/inventory-sandbox-state-operator"
    migration  = "arn:aws:iam::123456789012:role/inventory-sandbox-state-migration"
    recovery   = "arn:aws:iam::123456789012:role/inventory-sandbox-state-recovery"
    audit      = "arn:aws:iam::123456789012:role/inventory-sandbox-state-audit"
  }
}

run "protected_storage" {
  command = plan
  assert {
    condition = length(aws_s3_bucket.protected) == 2 && alltrue([for b in aws_s3_bucket.protected : !b.force_destroy]) && alltrue([
      for b in aws_s3_bucket_public_access_block.protected : b.block_public_acls && b.block_public_policy && b.ignore_public_acls && b.restrict_public_buckets
      ]) && alltrue([for b in aws_s3_bucket_versioning.protected : b.versioning_configuration[0].status == "Enabled"]) && alltrue([
      for b in aws_s3_bucket_ownership_controls.protected : b.rule[0].object_ownership == "BucketOwnerEnforced"
      ]) && alltrue([for b in aws_s3_bucket_server_side_encryption_configuration.protected : alltrue([
        for r in b.rule : !r.bucket_key_enabled && alltrue([for e in r.apply_server_side_encryption_by_default : e.sse_algorithm == "aws:kms"])
    ])])
    error_message = "Both buckets require non-destructive, private, versioned, owner-enforced SSE-KMS storage without Bucket Keys."
  }
}

run "operator_permissions" {
  command = plan
  assert {
    condition = alltrue([for s in jsondecode(aws_iam_role_policy.operator.policy).Statement : s.Effect != "Allow" || (
      s.Sid == "ListNamespace" ? s.Action == ["s3:ListBucket"] && s.Resource == [local.state_bucket_arn] && s.Condition.StringEquals["s3:prefix"] == [local.state_key, "${local.workspace_prefix}/"] :
      s.Sid == "StateReadWrite" ? toset(s.Action) == toset(["s3:GetObject", "s3:PutObject"]) && s.Resource == [local.state_arn] :
      s.Sid == "LockReadWriteDelete" ? toset(s.Action) == toset(["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]) && s.Resource == [local.lock_arn] :
      s.Sid == "StateKeyUse" ? toset(s.Action) == toset(["kms:Encrypt", "kms:Decrypt", "kms:GenerateDataKey"]) && s.Resource == [aws_kms_key.state.arn] && s.Condition == local.state_kms_condition : false
    )]) && length([for s in jsondecode(aws_iam_role_policy.operator.policy).Statement : s if s.Effect == "Allow"]) == 4
    error_message = "Operator permissions must be exact: state Get/Put, lock Get/Put/Delete, constrained List and three KMS operations."
  }
  assert {
    condition = anytrue([for s in jsondecode(aws_iam_role_policy.operator.policy).Statement : s.Sid == "NoWorkspaceObjects" && s.Effect == "Deny" && s.Resource == ["${local.state_bucket_arn}/${local.workspace_prefix}/*"]]) && anytrue([
      for s in jsondecode(aws_iam_role_policy.operator.policy).Statement : s.Sid == "NoStateDeletion" && s.Effect == "Deny" && s.Resource == [local.state_arn] && toset(s.Action) == toset(["s3:DeleteObject", "s3:DeleteObjectVersion"])
    ])
    error_message = "Nondefault workspace object access and state deletion must be explicitly denied without denying lock deletion."
  }
}

run "state_resource_policy" {
  command = plan
  assert {
    condition = alltrue([for sid in ["TLSOnly", "ProtectStateVersions", "NoPermanentLockDeletion", "StateRolesOnly", "RequireKms", "RequireApprovedKey"] : contains([
      for s in jsondecode(aws_s3_bucket_policy.state.policy).Statement : s.Sid if s.Effect == "Deny"
      ], sid)]) && alltrue([for s in jsondecode(aws_s3_bucket_policy.state.policy).Statement :
      s.Sid == "ProtectStateVersions" ? s.Resource == [local.state_arn] && toset(s.Action) == toset(["s3:DeleteObject", "s3:DeleteObjectVersion"]) :
      s.Sid == "NoPermanentLockDeletion" ? s.Resource == [local.lock_arn] && s.Action == ["s3:DeleteObjectVersion"] :
      s.Sid == "RequireApprovedKey" ? s.Condition.StringNotEquals["s3:x-amz-server-side-encryption-aws-kms-key-id"] == aws_kms_key.state.arn :
      s.Sid == "RequireKms" ? s.Condition.StringNotEquals["s3:x-amz-server-side-encryption"] == "aws:kms" :
      s.Sid == "StateRolesOnly" ? toset(s.Condition.ArnNotEquals["aws:PrincipalArn"]) == toset([var.roles.operator, var.roles.migration, var.roles.recovery]) && toset(s.Action) == toset(["s3:GetObject", "s3:GetObjectVersion", "s3:PutObject", "s3:DeleteObject"]) && toset(s.Resource) == toset([local.state_arn, local.lock_arn]) :
      s.Condition.Bool == { "aws:SecureTransport" = "false", "aws:PrincipalIsAWSService" = "false" }
    ])
    error_message = "State policy must reject deletion, wrong encryption/key/principal and non-TLS, while preserving ordinary unlock."
  }
}

run "recovery_separation" {
  command = plan
  assert {
    condition = length(jsondecode(aws_iam_role_policy.recovery.policy).Statement) == length(local.operator_statements) + 2 && alltrue([
      for s in jsondecode(aws_iam_role_policy.recovery.policy).Statement : s.Sid == "ReadStateVersion" ? s.Action == ["s3:GetObjectVersion"] && s.Resource == [local.state_arn] :
      s.Sid == "ListStateVersions" ? s.Action == ["s3:ListBucketVersions"] && s.Resource == [local.state_bucket_arn] && s.Condition.StringEquals["s3:prefix"] == [local.state_key] : contains(local.operator_statements, s)
    ]) && aws_iam_role_policy.migration.policy == aws_iam_role_policy.operator.policy && aws_iam_role_policy.migration.role != aws_iam_role_policy.operator.role && aws_iam_role_policy.migration.role != aws_iam_role_policy.recovery.role && aws_iam_role_policy.operator.role != aws_iam_role_policy.recovery.role && aws_iam_role_policy.audit.role != aws_iam_role_policy.operator.role
    error_message = "Recovery adds only bounded version listing/read, with different pre-existing roles and no permanent deletion."
  }
}

run "key_separation" {
  command = plan
  assert {
    condition = aws_kms_key.state.arn != aws_kms_key.audit.arn && alltrue([for k in [aws_kms_key.state, aws_kms_key.audit] :
      k.enable_key_rotation && k.deletion_window_in_days == 30 && !k.bypass_policy_lockout_safety_check && alltrue([
        for s in jsondecode(k.policy).Statement : s.Sid != "KeyAdministration" || (s.Principal.AWS == var.roles.management &&
        toset(s.Action) == toset(local.key_admin_actions) && !contains(s.Action, "kms:Decrypt") && !contains(s.Action, "kms:ScheduleKeyDeletion"))
      ])
      ]) && alltrue([for s in jsondecode(aws_kms_key.state.policy).Statement : s.Sid != "StateUse" || (
        toset(s.Principal.AWS) == toset([var.roles.operator, var.roles.migration, var.roles.recovery]) && s.Condition == local.state_kms_condition
    )])
    error_message = "State/log keys, key administration and decrypt roles must remain separate, with rotation and policy-lockout checks."
  }
}

run "audit_delivery" {
  command = plan
  assert {
    condition = aws_cloudtrail.state.enable_logging && aws_cloudtrail.state.enable_log_file_validation && aws_cloudtrail.state.is_multi_region_trail && aws_cloudtrail.state.include_global_service_events && aws_cloudtrail.state.kms_key_id == aws_kms_key.audit.arn && alltrue([
      for e in aws_cloudtrail.state.event_selector : e.include_management_events && e.read_write_type == "All" && alltrue([
        for d in e.data_resource : d.type == "AWS::S3::Object" && toset(d.values) == toset(["${local.state_bucket_arn}/"])
      ]) && length(e.data_resource) == 1
    ]) && length(aws_cloudtrail.state.event_selector) == 1
    error_message = "CloudTrail must record read/write state+lock data and management/KMS events in protected separate storage."
  }
  assert {
    condition = alltrue([for sid in ["TrailDelivery", "TrailAclCheck", "ProtectAuditVersions", "AuditReadersOnly", "TLSOnly"] : contains([for s in jsondecode(aws_s3_bucket_policy.audit.policy).Statement : s.Sid], sid)]) && alltrue([
      for s in jsondecode(aws_s3_bucket_policy.audit.policy).Statement : s.Effect != "Allow" || (
        s.Principal.Service == "cloudtrail.amazonaws.com" && s.Condition.StringEquals["aws:SourceArn"] == local.trail_arn && (
          s.Sid == "TrailDelivery" ? s.Action == "s3:PutObject" && s.Resource == "${local.audit_bucket_arn}/${local.log_prefix}*" && s.Condition.StringEquals["s3:x-amz-acl"] == "bucket-owner-full-control" :
          s.Sid == "TrailAclCheck" && s.Action == "s3:GetBucketAcl" && s.Resource == local.audit_bucket_arn
        )
      )
      ]) && alltrue([for s in jsondecode(aws_kms_key.audit.policy).Statement : s.Sid != "TrailEncrypt" || (
        s.Principal.Service == "cloudtrail.amazonaws.com" && s.Action == ["kms:GenerateDataKey*"] && s.Condition.StringEquals["aws:SourceArn"] == local.trail_arn && s.Condition.StringLike["kms:EncryptionContext:aws:cloudtrail:arn"] == "arn:aws:cloudtrail:*:123456789012:trail/inv-sandbox-123456789012-ap-northeast-1-state"
    )])
    error_message = "CloudTrail delivery must be bound to its exact source ARN, account path and separate audit key."
  }
  assert {
    condition = length(jsondecode(aws_iam_role_policy.audit.policy).Statement) == 3 && alltrue([
      for s in jsondecode(aws_iam_role_policy.audit.policy).Statement : s.Effect == "Allow" && (
        s.Sid == "ListAudit" ? toset(s.Action) == toset(["s3:ListBucket", "s3:ListBucketVersions"]) && s.Resource == [local.audit_bucket_arn] && s.Condition.StringLike["s3:prefix"] == ["${local.log_prefix}*"] :
        s.Sid == "ReadAudit" ? toset(s.Action) == toset(["s3:GetObject", "s3:GetObjectVersion"]) && s.Resource == ["${local.audit_bucket_arn}/${local.log_prefix}*"] :
        s.Sid == "DecryptAudit" ? s.Action == ["kms:Decrypt"] && s.Resource == [aws_kms_key.audit.arn] && s.Condition.StringLike["kms:EncryptionContext:aws:cloudtrail:arn"] == "arn:aws:cloudtrail:*:123456789012:trail/inv-sandbox-123456789012-ap-northeast-1-state" : false
      )
      ]) && alltrue([for s in jsondecode(aws_s3_bucket_policy.audit.policy).Statement :
      s.Sid == "ProtectAuditVersions" ? s.Effect == "Deny" && toset(s.Action) == toset(["s3:DeleteObject", "s3:DeleteObjectVersion"]) && s.Resource == "${local.audit_bucket_arn}/*" :
      s.Sid != "AuditReadersOnly" || s.Condition.ArnNotEquals["aws:PrincipalArn"] == [var.roles.audit]
    ])
    error_message = "Audit readers get only bounded log listing/read/decrypt, and log deletion is denied."
  }
}

run "backend_coordinates" {
  command = plan
  assert {
    condition     = output.backend_coordinates.key == "inventory/sandbox/ap-northeast-1/host/terraform.tfstate" && output.backend_coordinates.workspace_key_prefix == "inventory/sandbox/ap-northeast-1/workspaces" && output.backend_coordinates.allowed_account_ids == ["123456789012"] && output.backend_coordinates.kms_key_id == aws_kms_key.state.arn
    error_message = "Backend coordinates must bind the environment/account/region to the exact default-workspace state and reserved workspace prefix."
  }
}

run "invalid_account" {
  command = plan
  variables { account_id = "invalid" }
  expect_failures = [var.account_id]
}
run "wrong_account_roles" {
  command = plan
  variables { account_id = "999999999999" }
  expect_failures = [var.roles]
}
run "wrong_environment_roles" {
  command = plan
  variables { environment = "production" }
  expect_failures = [var.roles]
}
run "invalid_environment" {
  command = plan
  variables { environment = "../other" }
  expect_failures = [var.environment]
}
run "invalid_region" {
  command = plan
  variables { region = "not-a-region" }
  expect_failures = [var.region]
}
run "unsupported_partition" {
  command = plan
  variables { region = "cn-north-1" }
  expect_failures = [var.region]
}
run "shared_roles" {
  command = plan
  variables {
    roles = {
      management = "arn:aws:iam::123456789012:role/inventory-sandbox-state-operator"
      operator   = "arn:aws:iam::123456789012:role/inventory-sandbox-state-operator"
      migration  = "arn:aws:iam::123456789012:role/inventory-sandbox-state-migration"
      recovery   = "arn:aws:iam::123456789012:role/inventory-sandbox-state-recovery"
      audit      = "arn:aws:iam::123456789012:role/inventory-sandbox-state-audit"
    }
  }
  expect_failures = [var.roles]
}
