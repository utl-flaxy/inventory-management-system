#!/bin/bash

apt-get update -y

# Git
apt-get install -y git

# Docker
apt-get install -y docker.io

# Docker起動
systemctl enable docker
systemctl start docker

# ubuntuユーザーをdockerグループに追加
usermod -aG docker ubuntu

# Docker Composeプラグイン
mkdir -p /usr/local/lib/docker/cli-plugins

curl -SL https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64 \
-o /usr/local/lib/docker/cli-plugins/docker-compose

chmod +x /usr/local/lib/docker/cli-plugins/docker-compose

# リポジトリ取得
cd /home/ubuntu

git clone https://github.com/utl-flaxy/inventory-management-system.git

cd inventory-management-system

# rootで起動
docker compose up -d