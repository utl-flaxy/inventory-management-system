output "public_ip" {
  description = "Stable public IPv4: point app_domain's DNS A record to this EIP."
  value       = aws_eip.app.public_ip
}

output "instance_id" {
  description = "Production host instance ID."
  value       = aws_instance.app.id
}
