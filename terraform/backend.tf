# Partial configuration: approved bucket/key/region/KMS/account/workspace prefix
# belong in a checkout-external, non-credential backend file (TF-C/D only).
# CI and offline review MUST initialize with -backend=false.
terraform {
  backend "s3" {
    use_lockfile = true
    encrypt      = true
  }
}
