variable "name" {
  description = "Name of the server, its firewall and its labels."
  type        = string
  default     = "bms-prod"
}

variable "location" {
  description = "Hetzner location (for example fsn1, nbg1, hel1, ash). No default on purpose: the processing region must be decided and recorded in an ADR before production data arrives (NFR-DP-07, SDD chapter 9)."
  type        = string
}

variable "server_type" {
  description = "Server type. The documented size is 2 vCPU, 4 GB RAM, 40 GB disk: Hetzner calls it cx23 (Cost-Optimized, x86, limited availability, seen in the console on 2026-10-18 at about 6.49 USD a month plus 0.60 for IPv4, excluding VAT). If cx23 is out of stock in the chosen location, use the Regular Performance equivalent."
  type        = string
  default     = "cx23"
}

variable "image" {
  description = "Operating system image. Deliberately the 24.04 LTS, not the newest release: cloud-init installs Docker from Docker's own apt repository, which may not carry packages for a brand-new Ubuntu release yet."
  type        = string
  default     = "ubuntu-24.04"
}

variable "ssh_public_keys" {
  description = "Public keys of the people allowed to sign in as `ops`. Hetzner installs them for root as well; sshd refuses root and passwords (cloud-init)."
  type        = list(string)
  validation {
    condition     = length(var.ssh_public_keys) > 0
    error_message = "Give at least one SSH public key."
  }
}

variable "admin_cidrs" {
  description = "Addresses allowed to reach SSH (port 22), for example [\"203.0.113.7/32\"]. Nothing else is opened for SSH."
  type        = list(string)
  validation {
    condition     = length(var.admin_cidrs) > 0 && !contains(var.admin_cidrs, "0.0.0.0/0") && !contains(var.admin_cidrs, "::/0")
    error_message = "Give at least one address and never the whole internet."
  }
}

variable "backups" {
  description = "Hetzner's own daily server backups (about a fifth of the server price). They are in addition to the encrypted off-site database backups of backup.sh, not instead of them."
  type        = bool
  default     = true
}

variable "enable_ipv6" {
  description = "Give the server a public IPv6 address and publish AAAA records."
  type        = bool
  default     = true
}

variable "domain" {
  description = "The DNS zone the platform and tenant hosts live in. It must exist in the existing Cloudflare account that the token belongs to."
  type        = string
  default     = "rincoltech.com"
}

variable "tenant_slugs" {
  description = "One DNS record `<slug>-bms.<domain>` per production tenant. Add a slug here when a tenant goes live."
  type        = list(string)
  default     = []
}
