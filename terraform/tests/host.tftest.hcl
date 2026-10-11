# All runs use a mocked provider and plan only: no credentials, AWS reads or writes.
mock_provider "aws" {
  override_during = plan
  mock_resource "aws_vpc" {
    defaults = { id = "test-vpc" }
  }
  mock_resource "aws_subnet" {
    defaults = { id = "test-subnet" }
  }
  mock_resource "aws_route_table" {
    defaults = { id = "test-route-table" }
  }
  mock_resource "aws_instance" {
    defaults = { id = "test-instance" }
  }
  mock_resource "aws_eip" {
    defaults = { id = "test-allocation", public_ip = "203.0.113.20" }
  }
  mock_data "aws_availability_zones" {
    defaults = { names = ["ap-northeast-1c", "ap-northeast-1a"] }
  }
  mock_data "aws_ssm_parameter" {
    defaults = { value = "mock-official-image" }
  }
}

variables {
  app_domain         = "api.example.com"
  region             = "ap-northeast-1"
  vpc_cidr           = "10.20.0.0/16"
  public_subnet_cidr = "10.20.1.0/24"
  ssh_ingress_cidr   = "203.0.113.10/32"
  instance_type      = "t3.micro"
  key_name           = "inventory-test"
  public_key_path    = "tests/fixtures/operator.pub"
}

run "safe_host" {
  command = plan

  assert {
    condition = (aws_subnet.public.vpc_id == aws_vpc.app.id &&
      aws_subnet.public.availability_zone == "ap-northeast-1a" &&
      aws_route_table.public.vpc_id == aws_vpc.app.id &&
      aws_internet_gateway.app.vpc_id == aws_vpc.app.id &&
      aws_route_table_association.public.subnet_id == aws_subnet.public.id &&
      aws_route_table_association.public.route_table_id == aws_route_table.public.id &&
      aws_instance.app.subnet_id == aws_subnet.public.id &&
    aws_security_group.app_sg.vpc_id == aws_vpc.app.id)
    error_message = "Every host/network resource must use the explicit VPC and public subnet."
  }
  assert {
    condition = (length(aws_security_group.app_sg.ingress) == 3 &&
      toset([for rule in aws_security_group.app_sg.ingress : rule.from_port]) == toset([22, 80, 443]) &&
      alltrue([for rule in aws_security_group.app_sg.ingress : rule.protocol == "tcp" && rule.from_port == rule.to_port &&
        try(length(rule.ipv6_cidr_blocks), 0) == 0 &&
        (rule.from_port == 22 ? rule.cidr_blocks == tolist([var.ssh_ingress_cidr]) :
    contains([80, 443], rule.from_port) && rule.cidr_blocks == tolist(["0.0.0.0/0"]))]))
    error_message = "Only public TCP80/443 and operator SSH are allowed; no 8080/2019/IPv6 ingress."
  }
  assert {
    condition = (aws_eip.app.domain == "vpc" && aws_eip_association.app.allocation_id == aws_eip.app.id &&
      aws_eip_association.app.instance_id == aws_instance.app.id && output.public_ip == aws_eip.app.public_ip &&
    !aws_instance.app.associate_public_ip_address)
    error_message = "The stable EIP must be explicitly associated and exposed instead of an ephemeral IP."
  }
  assert {
    condition = (length(aws_instance.app.user_data) <= 16384 &&
    !strcontains(aws_instance.app.user_data, "__CADDY") && !strcontains(aws_instance.app.user_data, "__APP_DOMAIN__"))
    error_message = "Bootstrap must render every input and fit EC2's 16KiB user-data limit."
  }
  assert {
    condition = (aws_instance.app.metadata_options[0].http_tokens == "required" &&
      aws_instance.app.metadata_options[0].http_put_response_hop_limit == 1 &&
      aws_instance.app.root_block_device[0].encrypted &&
      aws_instance.app.root_block_device[0].delete_on_termination &&
      aws_instance.app.root_block_device[0].volume_type == "gp3" &&
      aws_instance.app.root_block_device[0].volume_size == 30 &&
      !aws_instance.app.associate_public_ip_address &&
      aws_instance.app.ami == data.aws_ssm_parameter.al2023.value &&
      aws_instance.app.tags["Name"] == "inventory-app" &&
      local.common_tags["Project"] == "inventory-management-system" &&
    local.common_tags["ManagedBy"] == "Terraform")
    error_message = "The production host hardening, official image, public IP and tags must be maintained."
  }
}

run "custom_network_and_size" {
  command = plan
  variables {
    app_domain         = "inventory.example.com"
    vpc_cidr           = "10.30.0.0/16"
    public_subnet_cidr = "10.30.2.0/24"
    instance_type      = "t3.small"
  }
}

run "world_open_ssh" {
  command = plan
  variables { ssh_ingress_cidr = "0.0.0.0/0" }
  expect_failures = [var.ssh_ingress_cidr]
}

run "equivalent_world_open_ssh" {
  command = plan
  variables { ssh_ingress_cidr = "203.0.113.1/0" }
  expect_failures = [var.ssh_ingress_cidr]
}

run "invalid_ssh" {
  command = plan
  variables { ssh_ingress_cidr = "not-a-cidr" }
  expect_failures = [var.ssh_ingress_cidr]
}

run "ipv6_ssh" {
  command = plan
  variables { ssh_ingress_cidr = "::/0" }
  expect_failures = [var.ssh_ingress_cidr]
}

run "invalid_vpc" {
  command = plan
  variables { vpc_cidr = "not-a-cidr" }
  expect_failures = [var.vpc_cidr]
}

run "subnet_outside_vpc" {
  command = plan
  variables { public_subnet_cidr = "10.21.1.0/24" }
  expect_failures = [var.public_subnet_cidr]
}

run "subnet_host_bits" {
  command = plan
  variables { public_subnet_cidr = "10.20.1.1/24" }
  expect_failures = [var.public_subnet_cidr]
}

run "subnet_too_wide" {
  command = plan
  variables { public_subnet_cidr = "10.20.0.0/15" }
  expect_failures = [var.public_subnet_cidr]
}

run "arm_instance" {
  command = plan
  variables { instance_type = "t4g.micro" }
  expect_failures = [var.instance_type]
}

run "invalid_region" {
  command = plan
  variables { region = "not-a-region" }
  expect_failures = [var.region]
}

run "missing_public_key" {
  command = plan
  variables { public_key_path = "tests/fixtures/missing.pub" }
  expect_failures = [var.public_key_path]
}

run "non_public_key" {
  command = plan
  variables { public_key_path = "userdata.sh" }
  expect_failures = [var.public_key_path]
}

run "domain_localhost" {
  command = plan
  variables { app_domain = "localhost" }
  expect_failures = [var.app_domain]
}

run "domain_ip" {
  command = plan
  variables { app_domain = "127.0.0.1" }
  expect_failures = [var.app_domain]
}

run "domain_scheme" {
  command = plan
  variables { app_domain = "https://example.com" }
  expect_failures = [var.app_domain]
}

run "domain_path" {
  command = plan
  variables { app_domain = "example.com/path" }
  expect_failures = [var.app_domain]
}

run "domain_port" {
  command = plan
  variables { app_domain = "example.com:443" }
  expect_failures = [var.app_domain]
}

run "domain_wildcard" {
  command = plan
  variables { app_domain = "*.example.com" }
  expect_failures = [var.app_domain]
}

run "domain_empty" {
  command = plan
  variables { app_domain = "" }
  expect_failures = [var.app_domain]
}

run "domain_spaces" {
  command = plan
  variables { app_domain = "api example.com" }
  expect_failures = [var.app_domain]
}

run "domain_label" {
  command = plan
  variables { app_domain = "-api.example.com" }
  expect_failures = [var.app_domain]
}

run "domain_long_label" {
  command = plan
  variables { app_domain = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.example.com" }
  expect_failures = [var.app_domain]
}

run "domain_local_suffix" {
  command = plan
  variables { app_domain = "api.localhost" }
  expect_failures = [var.app_domain]
}

run "domain_newline" {
  command = plan
  variables { app_domain = "api.example.com\nmalicious" }
  expect_failures = [var.app_domain]
}

run "domain_trailing_dot" {
  command = plan
  variables { app_domain = "api.example.com." }
  expect_failures = [var.app_domain]
}
