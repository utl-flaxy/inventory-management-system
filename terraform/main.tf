locals {
  common_tags = {
    Project   = "inventory-management-system"
    ManagedBy = "Terraform"
  }
}

data "aws_availability_zones" "available" {
  state = "available"

  filter {
    name   = "opt-in-status"
    values = ["opt-in-not-required"]
  }
}

# AWS publishes the current official AL2023 x86_64 image for each region.
data "aws_ssm_parameter" "al2023" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

resource "aws_vpc" "app" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = "inventory-vpc" }
}

resource "aws_subnet" "public" {
  vpc_id            = aws_vpc.app.id
  cidr_block        = var.public_subnet_cidr
  availability_zone = sort(data.aws_availability_zones.available.names)[0]
  # Only the app instance explicitly requests a public IP.
  map_public_ip_on_launch = false

  tags = { Name = "inventory-public-subnet" }
}

resource "aws_internet_gateway" "app" {
  vpc_id = aws_vpc.app.id

  tags = { Name = "inventory-igw" }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.app.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.app.id
  }

  tags = { Name = "inventory-public-route" }
}

resource "aws_route_table_association" "public" {
  subnet_id      = aws_subnet.public.id
  route_table_id = aws_route_table.public.id
}

resource "aws_security_group" "app_sg" {
  name_prefix = "inventory-sg-"
  description = "SSH from the operator CIDR; application ports remain private"
  vpc_id      = aws_vpc.app.id

  ingress {
    description = "Operator SSH"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.ssh_ingress_cidr]
  }

  # Package, GitHub, Maven and container downloads require outbound access.
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "inventory-sg" }
}

resource "aws_key_pair" "inventory_key" {
  key_name   = var.key_name
  public_key = file(pathexpand(var.public_key_path))

  tags = { Name = "inventory-key" }
}

resource "aws_instance" "app" {
  ami                         = data.aws_ssm_parameter.al2023.value
  instance_type               = var.instance_type
  subnet_id                   = aws_subnet.public.id
  associate_public_ip_address = true
  key_name                    = aws_key_pair.inventory_key.key_name
  vpc_security_group_ids      = [aws_security_group.app_sg.id]

  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 1
  }

  root_block_device {
    encrypted             = true
    delete_on_termination = true
    volume_type           = "gp3"
    volume_size           = 30
  }

  user_data                   = file("${path.module}/userdata.sh")
  user_data_replace_on_change = true
  # Bootstrap needs the route before its package/download requests can succeed.
  depends_on = [aws_route_table_association.public]

  tags = { Name = "inventory-app" }
}
