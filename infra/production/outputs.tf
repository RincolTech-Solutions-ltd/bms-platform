output "ipv4" {
  description = "Public IPv4 address of the production server."
  value       = hcloud_server.prod.ipv4_address
}

output "ipv6" {
  description = "Public IPv6 address of the production server (empty when IPv6 is off)."
  value       = hcloud_server.prod.ipv6_address
}

output "hostnames" {
  description = "The DNS names this module publishes."
  value       = [for host in local.hostnames : "${host}.${var.domain}"]
}

output "next_steps" {
  description = "What is still done by hand after the server exists."
  value       = "Follow docs/runbooks/terraform-production.md from step 4: /opt/bms/.env, cosign.pub, the read:packages login, the first files, then the first promote."
}
