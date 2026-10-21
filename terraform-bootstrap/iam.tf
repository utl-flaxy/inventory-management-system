locals {
  operator_statements = [
    {
      Sid       = "ListNamespace", Effect = "Allow", Action = ["s3:ListBucket"], Resource = [local.state_bucket_arn],
      Condition = { StringEquals = { "s3:prefix" = local.list_prefixes } }
    },
    {
      Sid = "StateReadWrite", Effect = "Allow", Action = ["s3:GetObject", "s3:PutObject"], Resource = [local.state_arn]
    },
    {
      Sid = "LockReadWriteDelete", Effect = "Allow", Action = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"], Resource = [local.lock_arn]
    },
    {
      Sid      = "StateKeyUse", Effect = "Allow", Action = ["kms:Encrypt", "kms:Decrypt", "kms:GenerateDataKey"],
      Resource = [aws_kms_key.state.arn], Condition = local.state_kms_condition
    },
    # Explicit boundaries survive accidental unrelated identity-policy additions.
    {
      Sid = "NoStateDeletion", Effect = "Deny", Action = ["s3:DeleteObject", "s3:DeleteObjectVersion"], Resource = [local.state_arn]
    },
    {
      Sid = "NoWorkspaceObjects", Effect = "Deny", Action = ["s3:*"], Resource = ["${local.state_bucket_arn}/${local.workspace_prefix}/*"]
    }
  ]
}

# Existing role trust policies and other attached permissions MUST be audited in
# TF-C; this root adds only the backend permissions, not host provisioner powers.
resource "aws_iam_role_policy" "operator" {
  name   = "inventory-state-${var.environment}"
  role   = reverse(split("/", var.roles.operator))[0]
  policy = jsonencode({ Version = "2012-10-17", Statement = local.operator_statements })
}

resource "aws_iam_role_policy" "recovery" {
  name = "inventory-state-recovery-${var.environment}"
  role = reverse(split("/", var.roles.recovery))[0]
  policy = jsonencode({ Version = "2012-10-17", Statement = concat(local.operator_statements, [
    {
      Sid       = "ListStateVersions", Effect = "Allow", Action = ["s3:ListBucketVersions"], Resource = [local.state_bucket_arn],
      Condition = { StringEquals = { "s3:prefix" = [local.state_key] } }
    },
    {
      Sid = "ReadStateVersion", Effect = "Allow", Action = ["s3:GetObjectVersion"], Resource = [local.state_arn]
    }
  ]) })
}

resource "aws_iam_role_policy" "audit" {
  name = "inventory-state-audit-${var.environment}"
  role = reverse(split("/", var.roles.audit))[0]
  policy = jsonencode({ Version = "2012-10-17", Statement = [
    {
      Sid       = "ListAudit", Effect = "Allow", Action = ["s3:ListBucket", "s3:ListBucketVersions"], Resource = [local.audit_bucket_arn],
      Condition = { StringLike = { "s3:prefix" = ["${local.log_prefix}*"] } }
    },
    {
      Sid = "ReadAudit", Effect = "Allow", Action = ["s3:GetObject", "s3:GetObjectVersion"], Resource = ["${local.audit_bucket_arn}/${local.log_prefix}*"]
    },
    {
      Sid       = "DecryptAudit", Effect = "Allow", Action = ["kms:Decrypt"], Resource = [aws_kms_key.audit.arn],
      Condition = { StringEquals = { "kms:ViaService" = "s3.${var.region}.amazonaws.com" }, StringLike = { "kms:EncryptionContext:aws:cloudtrail:arn" = local.trail_context_arn } }
    }
  ] })
}
