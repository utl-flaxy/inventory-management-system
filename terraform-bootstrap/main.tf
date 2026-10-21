locals {
  name             = "inv-${var.environment}-${var.account_id}-${var.region}"
  state_bucket_arn = "arn:aws:s3:::${local.name}-state"
  audit_bucket_arn = "arn:aws:s3:::${local.name}-audit"
  state_key        = "inventory/${var.environment}/${var.region}/host/terraform.tfstate"
  workspace_prefix = "inventory/${var.environment}/${var.region}/workspaces"
  state_arn        = "${local.state_bucket_arn}/${local.state_key}"
  lock_arn         = "${local.state_arn}.tflock"
  trail_arn        = "arn:aws:cloudtrail:${var.region}:${var.account_id}:trail/${local.name}-state"
  log_prefix       = "state-audit/AWSLogs/${var.account_id}/"
  list_prefixes    = [local.state_key, "${local.workspace_prefix}/"]
  key_admin_actions = [
    "kms:DescribeKey", "kms:GetKeyPolicy", "kms:PutKeyPolicy", "kms:EnableKeyRotation",
    "kms:GetKeyRotationStatus", "kms:ListResourceTags", "kms:TagResource", "kms:UntagResource", "kms:UpdateKeyDescription"
  ]
  key_admin = {
    Sid    = "KeyAdministration", Effect = "Allow", Principal = { AWS = var.roles.management },
    Action = local.key_admin_actions, Resource = "*"
  }
  # No Bucket Keys: retain per-object encryption context restrictions.
  state_kms_condition = {
    StringEquals = {
      "kms:ViaService"                   = "s3.${var.region}.amazonaws.com"
      "kms:EncryptionContext:aws:s3:arn" = [local.state_arn, local.lock_arn]
    }
  }
}

# Account-level Public Access Block is an organizational prerequisite. This root
# hardens its two buckets without changing unrelated account-level settings.
resource "aws_s3_bucket" "protected" {
  for_each      = toset(["state", "audit"])
  bucket        = "${local.name}-${each.key}"
  force_destroy = false
  lifecycle { prevent_destroy = true }
}

resource "aws_s3_bucket_public_access_block" "protected" {
  for_each                = aws_s3_bucket.protected
  bucket                  = each.value.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "protected" {
  for_each = aws_s3_bucket.protected
  bucket   = each.value.id
  rule { object_ownership = "BucketOwnerEnforced" }
}

resource "aws_s3_bucket_versioning" "protected" {
  for_each = aws_s3_bucket.protected
  bucket   = each.value.id
  versioning_configuration { status = "Enabled" }
}

resource "aws_kms_key" "state" {
  description             = "Dedicated Terraform state and native lockfile key"
  enable_key_rotation     = true
  deletion_window_in_days = 30
  policy = jsonencode({ Version = "2012-10-17", Statement = [local.key_admin, {
    Sid    = "StateUse", Effect = "Allow", Principal = { AWS = [var.roles.operator, var.roles.recovery] },
    Action = ["kms:Encrypt", "kms:Decrypt", "kms:GenerateDataKey"], Resource = "*", Condition = local.state_kms_condition
  }] })
  # Keep the AWS policy-lockout safety check enabled (provider default false).
  bypass_policy_lockout_safety_check = false
  lifecycle { prevent_destroy = true }
}

resource "aws_kms_key" "audit" {
  description             = "Separate CloudTrail audit key; state operators cannot decrypt logs"
  enable_key_rotation     = true
  deletion_window_in_days = 30
  policy = jsonencode({ Version = "2012-10-17", Statement = [local.key_admin,
    {
      Sid       = "TrailEncrypt", Effect = "Allow", Principal = { Service = "cloudtrail.amazonaws.com" },
      Action    = ["kms:GenerateDataKey*"], Resource = "*",
      Condition = { StringEquals = { "aws:SourceArn" = local.trail_arn, "kms:EncryptionContext:aws:cloudtrail:arn" = local.trail_arn } }
    },
    {
      Sid    = "TrailDescribe", Effect = "Allow", Principal = { Service = "cloudtrail.amazonaws.com" },
      Action = ["kms:DescribeKey"], Resource = "*", Condition = { StringEquals = { "aws:SourceArn" = local.trail_arn } }
    },
    {
      Sid       = "AuditRead", Effect = "Allow", Principal = { AWS = var.roles.audit },
      Action    = ["kms:Decrypt"], Resource = "*",
      Condition = { StringEquals = { "kms:ViaService" = "s3.${var.region}.amazonaws.com", "kms:EncryptionContext:aws:cloudtrail:arn" = local.trail_arn } }
    }
  ] })
  bypass_policy_lockout_safety_check = false
  lifecycle { prevent_destroy = true }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "protected" {
  for_each = aws_s3_bucket.protected
  bucket   = each.value.id
  rule {
    bucket_key_enabled = false
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = each.key == "state" ? aws_kms_key.state.arn : aws_kms_key.audit.arn
    }
  }
}

locals {
  tls_deny = { for purpose, bucket in aws_s3_bucket.protected : purpose => {
    Sid       = "TLSOnly", Effect = "Deny", Principal = "*", Action = "s3:*", Resource = [bucket.arn, "${bucket.arn}/*"],
    Condition = { Bool = { "aws:SecureTransport" = "false", "aws:PrincipalIsAWSService" = "false" } }
  } }
}

resource "aws_s3_bucket_policy" "state" {
  bucket = aws_s3_bucket.protected["state"].id
  policy = jsonencode({ Version = "2012-10-17", Statement = [local.tls_deny.state,
    {
      Sid    = "ProtectStateVersions", Effect = "Deny", Principal = "*",
      Action = ["s3:DeleteObject", "s3:DeleteObjectVersion"], Resource = [local.state_arn]
    },
    {
      Sid    = "NoPermanentLockDeletion", Effect = "Deny", Principal = "*",
      Action = ["s3:DeleteObjectVersion"], Resource = [local.lock_arn]
    },
    {
      Sid       = "StateRolesOnly", Effect = "Deny", Principal = "*",
      Action    = ["s3:GetObject", "s3:GetObjectVersion", "s3:PutObject", "s3:DeleteObject"], Resource = [local.state_arn, local.lock_arn],
      Condition = { ArnNotEquals = { "aws:PrincipalArn" = [var.roles.operator, var.roles.recovery] } }
    },
    {
      Sid       = "RequireKms", Effect = "Deny", Principal = "*", Action = ["s3:PutObject"], Resource = ["${local.state_bucket_arn}/*"],
      Condition = { StringNotEquals = { "s3:x-amz-server-side-encryption" = "aws:kms" } }
    },
    {
      Sid       = "RequireApprovedKey", Effect = "Deny", Principal = "*", Action = ["s3:PutObject"], Resource = ["${local.state_bucket_arn}/*"],
      Condition = { StringNotEquals = { "s3:x-amz-server-side-encryption-aws-kms-key-id" = aws_kms_key.state.arn } }
    }
  ] })
  depends_on = [aws_s3_bucket_public_access_block.protected, aws_s3_bucket_ownership_controls.protected]
}

resource "aws_s3_bucket_policy" "audit" {
  bucket = aws_s3_bucket.protected["audit"].id
  policy = jsonencode({ Version = "2012-10-17", Statement = [local.tls_deny.audit,
    {
      Sid       = "TrailAclCheck", Effect = "Allow", Principal = { Service = "cloudtrail.amazonaws.com" },
      Action    = "s3:GetBucketAcl", Resource = local.audit_bucket_arn,
      Condition = { StringEquals = { "aws:SourceArn" = local.trail_arn } }
    },
    {
      Sid       = "TrailDelivery", Effect = "Allow", Principal = { Service = "cloudtrail.amazonaws.com" },
      Action    = "s3:PutObject", Resource = "${local.audit_bucket_arn}/${local.log_prefix}*",
      Condition = { StringEquals = { "aws:SourceArn" = local.trail_arn, "s3:x-amz-acl" = "bucket-owner-full-control" } }
    },
    {
      Sid    = "ProtectAuditVersions", Effect = "Deny", Principal = "*",
      Action = ["s3:DeleteObject", "s3:DeleteObjectVersion"], Resource = "${local.audit_bucket_arn}/*"
    },
    {
      Sid       = "AuditReadersOnly", Effect = "Deny", Principal = "*",
      Action    = ["s3:GetObject", "s3:GetObjectVersion"], Resource = "${local.audit_bucket_arn}/*",
      Condition = { ArnNotEquals = { "aws:PrincipalArn" = [var.roles.audit] } }
    }
  ] })
  depends_on = [aws_s3_bucket_public_access_block.protected, aws_s3_bucket_ownership_controls.protected]
}

resource "aws_cloudtrail" "state" {
  name                          = "${local.name}-state"
  s3_bucket_name                = aws_s3_bucket.protected["audit"].id
  s3_key_prefix                 = "state-audit"
  kms_key_id                    = aws_kms_key.audit.arn
  enable_logging                = true
  enable_log_file_validation    = true
  is_multi_region_trail         = true
  include_global_service_events = true
  event_selector {
    read_write_type           = "All"
    include_management_events = true
    # All state bucket objects, including failed attempts at reserved workspaces.
    # Audit bucket is excluded to avoid recursive data-event logging.
    data_resource {
      type   = "AWS::S3::Object"
      values = ["${local.state_bucket_arn}/"]
    }
  }
  depends_on = [aws_s3_bucket_policy.audit, aws_s3_bucket_server_side_encryption_configuration.protected, aws_s3_bucket_versioning.protected]
  lifecycle { prevent_destroy = true }
}
