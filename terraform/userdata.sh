#!/bin/bash

dnf update -y

# Git

dnf install -y git

# Docker

dnf install -y docker

# Docker起動

systemctl enable docker
systemctl start docker

# ec2-user を docker グループに追加

usermod -aG docker ec2-user

# Docker Compose plugin

mkdir -p /usr/local/lib/docker/cli-plugins

curl -SL https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64 
-o /usr/local/lib/docker/cli-plugins/docker-compose

chmod +x /usr/local/lib/docker/cli-plugins/docker-compose

# リポジトリ取得

cd /home/ec2-user

git clone https://github.com/utl-flaxy/inventory-management-system.git

cd inventory-management-system

docker compose up -d