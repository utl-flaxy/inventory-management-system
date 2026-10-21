variable "account_id" {
  description = "Approved AWS commercial-partition account; no default or discovery."
  type        = string
  nullable    = false
  validation {
    condition     = can(regex("^[0-9]{12}$", var.account_id))
    error_message = "account_id must be a reviewed twelve-digit account ID."
  }
}

variable "region" {
  description = "Approved commercial AWS region; must match the backend and KMS region."
  type        = string
  nullable    = false
  validation {
    condition     = can(regex("^(af|ap|ca|eu|il|me|mx|sa|us)-(central|east|west|north|south|northeast|northwest|southeast|southwest)-[1-9]$", var.region))
    error_message = "region must be a commercial AWS region; other partitions require a separate review."
  }
}

variable "environment" {
  description = "Approved isolated environment, also present in every role name and object namespace."
  type        = string
  nullable    = false
  validation {
    condition     = contains(["sandbox", "staging", "production"], var.environment)
    error_message = "environment must be sandbox, staging or production."
  }
}

variable "roles" {
  description = "Five pre-existing, separately approved roles. Trust/MFA/SSO and provisioning permissions are external prerequisites, not created here."
  type = object({
    management = string
    operator   = string
    migration  = string
    recovery   = string
    audit      = string
  })
  nullable = false
  validation {
    condition = alltrue([for purpose, arn in var.roles : can(regex(
      "^arn:aws:iam::${var.account_id}:role/([A-Za-z0-9_+=,.@-]+/)*inventory-${var.environment}-state-${purpose}$", arn
    ))]) && length(distinct(values(var.roles))) == 5
    error_message = "Roles must be distinct, in the approved account, and named inventory-<environment>-state-<purpose>."
  }
}
