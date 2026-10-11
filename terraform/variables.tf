variable "region" {
  description = "AWS region supporting Amazon Linux 2023 and the selected instance type."
  type        = string
  default     = "ap-northeast-1"
  nullable    = false

  validation {
    condition     = can(regex("^[a-z]{2}(-[a-z]+)+-[0-9]+$", var.region))
    error_message = "region must be an AWS region name."
  }
}

variable "vpc_cidr" {
  description = "Canonical IPv4 VPC CIDR (/16 through /24); avoid existing network overlap."
  type        = string
  default     = "10.20.0.0/16"
  nullable    = false

  validation {
    condition = can(cidrnetmask(var.vpc_cidr)) && try(
      var.vpc_cidr == "${cidrhost(var.vpc_cidr, 0)}/${split("/", var.vpc_cidr)[1]}" &&
    tonumber(split("/", var.vpc_cidr)[1]) >= 16 && tonumber(split("/", var.vpc_cidr)[1]) <= 24, false)
    error_message = "vpc_cidr must be a canonical IPv4 network with a /16 through /24 prefix."
  }
}

variable "public_subnet_cidr" {
  description = "One canonical IPv4 public subnet (/24 through /28) contained in vpc_cidr."
  type        = string
  default     = "10.20.1.0/24"
  nullable    = false

  validation {
    condition = can(cidrnetmask(var.public_subnet_cidr)) && try(
      var.public_subnet_cidr == "${cidrhost(var.public_subnet_cidr, 0)}/${split("/", var.public_subnet_cidr)[1]}" &&
      tonumber(split("/", var.public_subnet_cidr)[1]) >= 24 &&
      tonumber(split("/", var.public_subnet_cidr)[1]) <= 28 &&
    cidrhost("${cidrhost(var.public_subnet_cidr, 0)}/${split("/", var.vpc_cidr)[1]}", 0) == cidrhost(var.vpc_cidr, 0), false)
    error_message = "public_subnet_cidr must be a canonical IPv4 /24 through /28 network inside vpc_cidr."
  }
}

variable "ssh_ingress_cidr" {
  description = "Trusted operator IPv4 network for SSH, ideally a single /32 address; no default."
  type        = string
  nullable    = false

  validation {
    condition     = can(cidrnetmask(var.ssh_ingress_cidr)) && try(tonumber(split("/", var.ssh_ingress_cidr)[1]) > 0, false)
    error_message = "ssh_ingress_cidr must be a valid IPv4 CIDR and must not allow the entire internet (/0)."
  }
}

variable "instance_type" {
  description = "Supported x86_64 T3 instance size; Compose and AMI are pinned to x86_64."
  type        = string
  default     = "t3.micro"
  nullable    = false

  validation {
    condition     = contains(["t3.micro", "t3.small", "t3.medium", "t3.large", "t3.xlarge", "t3.2xlarge"], var.instance_type)
    error_message = "instance_type must be a supported T3 size (micro through 2xlarge); ARM instances are not supported."
  }
}

variable "key_name" {
  description = "Unique EC2 key pair name; only the public key is uploaded."
  type        = string
  nullable    = false

  validation {
    condition     = can(regex("^[A-Za-z0-9_-]{1,255}$", var.key_name))
    error_message = "key_name must contain 1-255 letters, digits, underscores or hyphens."
  }
}

variable "public_key_path" {
  description = "Path to an existing OpenSSH PUBLIC key file, never a private key."
  type        = string
  nullable    = false

  validation {
    condition = can(regex("^(ssh-ed25519|ssh-rsa|ecdsa-sha2-nistp(256|384|521)) [A-Za-z0-9+/=]+( |$)",
    trimspace(file(pathexpand(var.public_key_path)))))
    error_message = "public_key_path must point to a readable OpenSSH public key file."
  }
}
