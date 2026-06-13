resource "aws_security_group" "app_sg" {
  name = "inventory-sg"

  ingress {
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    from_port   = 8080
    to_port     = 8080
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_key_pair" "inventory_key" {
  key_name   = var.key_name
  public_key = file(var.public_key_path)
}

resource "aws_instance" "app" {

  ami           = "ami-0c1638aa346a43fe8"
  instance_type = "t3.micro"

  key_name = aws_key_pair.inventory_key.key_name

  vpc_security_group_ids = [
    aws_security_group.app_sg.id
  ]

  # ★追加
  user_data = file("${path.module}/userdata.sh")

  tags = {
    Name = "inventory-app"
  }
}