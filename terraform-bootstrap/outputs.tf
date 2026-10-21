# Non-credential coordinates only. Do not pipe outputs into init automatically.
output "backend_coordinates" {
  value = {
    bucket               = aws_s3_bucket.protected["state"].bucket
    key                  = local.state_key
    region               = var.region
    kms_key_id           = aws_kms_key.state.arn
    allowed_account_ids  = [var.account_id]
    workspace_key_prefix = local.workspace_prefix
  }
}
