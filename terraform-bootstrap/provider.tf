# Independent bootstrap root. Initial state uses approved encrypted PRIVATE local
# storage/backup, never the bucket this root would create. No remote backend here.
terraform {
  required_version = "= 1.15.6"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

provider "aws" {
  region              = var.region
  allowed_account_ids = [var.account_id]
  assume_role {
    role_arn = var.roles.management
  }
  default_tags {
    tags = { Project = "inventory-management-system", Environment = var.environment, ManagedBy = "Terraform", Purpose = "remote-state" }
  }
}
