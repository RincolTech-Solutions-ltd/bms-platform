# The production VM of ADR-006 and ADR-063. Nothing here is applied until production day: the owner decided
# on 2026-10-18 that the server is created when production is ready, and this code is what makes that one
# reviewed command. Secrets (/opt/bms/.env, the registry login, cosign.pub) never pass through Terraform or
# its state: they are put on the host by hand (docs/runbooks/terraform-production.md).

locals {
  hostnames = concat(["bms", "bms-callbacks"], [for slug in var.tenant_slugs : "${slug}-bms"])

  labels = {
    project     = "bms-platform"
    environment = "production"
    managed_by  = "terraform"
  }
}

resource "hcloud_ssh_key" "admin" {
  for_each   = { for index, key in var.ssh_public_keys : tostring(index) => key }
  name       = "${var.name}-${each.key}"
  public_key = each.value
  labels     = local.labels
}

# Inbound: SSH from the admin addresses only; 80 and 443 (tcp) and 443 (udp, HTTP/3) from anywhere. Outbound is
# open (the puller, Docker, the DNS-01 challenge and the R2 backups all need it).
resource "hcloud_firewall" "prod" {
  name   = var.name
  labels = local.labels

  rule {
    direction   = "in"
    protocol    = "tcp"
    port        = "22"
    source_ips  = var.admin_cidrs
    description = "SSH from the team's addresses only"
  }
  rule {
    direction   = "in"
    protocol    = "tcp"
    port        = "80"
    source_ips  = ["0.0.0.0/0", "::/0"]
    description = "HTTP (redirect to HTTPS)"
  }
  rule {
    direction   = "in"
    protocol    = "tcp"
    port        = "443"
    source_ips  = ["0.0.0.0/0", "::/0"]
    description = "HTTPS"
  }
  rule {
    direction   = "in"
    protocol    = "udp"
    port        = "443"
    source_ips  = ["0.0.0.0/0", "::/0"]
    description = "HTTP/3"
  }
  rule {
    direction   = "in"
    protocol    = "icmp"
    source_ips  = ["0.0.0.0/0", "::/0"]
    description = "ping, for the uptime check"
  }
}

resource "hcloud_server" "prod" {
  name         = var.name
  server_type  = var.server_type
  image        = var.image
  location     = var.location
  ssh_keys     = [for key in hcloud_ssh_key.admin : key.id]
  firewall_ids = [hcloud_firewall.prod.id]
  backups      = var.backups
  labels       = local.labels

  # A mistyped `terraform destroy` or an image rebuild must not be able to delete the customers' database.
  delete_protection  = true
  rebuild_protection = true

  public_net {
    ipv4_enabled = true
    ipv6_enabled = var.enable_ipv6
  }

  user_data = templatefile("${path.module}/cloud-init.yaml.tftpl", {
    ops_ssh_keys = var.ssh_public_keys
    pull_service = file("${path.module}/../../deploy/systemd/bms-pull.service")
    pull_timer   = file("${path.module}/../../deploy/systemd/bms-pull.timer")
  })

  lifecycle {
    # cloud-init runs once, at first boot; editing it later must not replace the server.
    ignore_changes = [user_data, ssh_keys]
  }
}

data "cloudflare_zone" "this" {
  name = var.domain
}

# DNS only (not proxied): Caddy terminates TLS itself and obtains the wildcard certificate with the DNS-01
# challenge. Never the apex and never a wildcard of the company zone (docs/runbooks/provision-host.md section 3).
resource "cloudflare_record" "a" {
  for_each = toset(local.hostnames)
  zone_id  = data.cloudflare_zone.this.id
  name     = each.value
  type     = "A"
  content  = hcloud_server.prod.ipv4_address
  proxied  = false
  ttl      = 300
  comment  = "bms production (terraform)"
}

resource "cloudflare_record" "aaaa" {
  for_each = var.enable_ipv6 ? toset(local.hostnames) : toset([])
  zone_id  = data.cloudflare_zone.this.id
  name     = each.value
  type     = "AAAA"
  content  = hcloud_server.prod.ipv6_address
  proxied  = false
  ttl      = 300
  comment  = "bms production (terraform)"
}
