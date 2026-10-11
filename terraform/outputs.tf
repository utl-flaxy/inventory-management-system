output "public_ip" {
  description = "Host public IPv4 address (SSH only; no public application ingress)."
  value       = aws_instance.app.public_ip
}

output "instance_id" {
  description = "Production host instance ID."
  value       = aws_instance.app.id
}
